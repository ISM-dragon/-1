from __future__ import annotations

import threading
from pathlib import Path

from backend.engine import EngineEvent, PipelineFacadeEngine, SubprocessPublikclipEngine
from publikclip_pipeline.engine import JobRef, JobResults, JobStatus, ProgressEvent


def test_jsonl_event_mapping(tmp_path: Path):
    events: list[EngineEvent] = []
    adapter = SubprocessPublikclipEngine(tmp_path)
    assert adapter._emit_line('{"event":"job","job_id":"eng-1"}', events.append) is None
    assert adapter._emit_line('{"event":"progress","stage":"render","fraction":1.4,"message":"done"}', events.append) is None
    result = adapter._emit_line('{"event":"result","ok":true,"job_id":"eng-1"}', events.append)
    assert result == {"event": "result", "ok": True, "job_id": "eng-1"}
    assert [event.kind for event in events] == ["job", "progress", "result"]
    assert events[0].engine_job_id == "eng-1"
    assert events[1].progress == 1.0


def test_unavailable_pipeline_is_reported(tmp_path: Path):
    adapter = SubprocessPublikclipEngine(tmp_path)
    available, message = adapter.available()
    assert available is False
    assert "not available" in message


class PublicFacadeDouble:
    def __init__(self, home: Path):
        self.home = home
        self.calls: list[tuple[str, str]] = []
        self.engine_job_id = "engine-public-1"
        self.artifact = self.home / "jobs" / self.engine_job_id / "clips" / "clip_00.mp4"
        self.artifact.parent.mkdir(parents=True, exist_ok=True)
        self.artifact.write_bytes(b"direct facade mp4")

    def create_job(self, source, settings=None, *, source_type=None):
        self.calls.append(("create_job", source))
        return JobRef(self.engine_job_id, 1.0, source, source_type or "file")

    def start_job(self, job_id, on_progress=None):
        self.calls.append(("start_job", job_id))
        if on_progress:
            on_progress(ProgressEvent(job_id, "ingest", 1.0, "done"))
        return self.results(job_id)

    def resume_job(self, job_id, settings=None, on_progress=None):
        self.calls.append(("resume_job", job_id))
        return self.start_job(job_id, on_progress)

    def get_status(self, job_id):
        return JobStatus(job_id, "done", "render", 1.0, "done", None, None, False, False, {"render": "done"})

    def get_progress(self, job_id):
        return {"job_id": job_id, "stage": "render", "fraction": 1.0, "progress": 1.0, "message": "done", "stages": {"render": "done"}}

    def cancel_job(self, job_id):
        self.calls.append(("cancel_job", job_id))
        return self.get_status(job_id)

    def get_results(self, job_id):
        self.calls.append(("get_results", job_id))
        return self.results(job_id)

    def results(self, job_id):
        return JobResults(
            job_id,
            ingest={"title": "Direct"},
            events=None,
            candidates=None,
            score={"clips": [{"score": 88, "start": 0, "end": 2}]},
            render={"outputs": [{"clip": 0, "path": str(self.artifact)}]},
            artifacts=[{"clip": 0, "path": str(self.artifact)}],
        )

    def render_clip(self, job_id, clip, on_progress=None):
        self.calls.append(("render_clip", f"{job_id}:{clip}"))
        return {"clip": clip, "path": str(self.artifact), "edited": True}


def test_pipeline_facade_adapter_translates_public_records_and_events(tmp_path: Path):
    facade = PublicFacadeDouble(tmp_path / "engine-home")
    adapter = PipelineFacadeEngine(tmp_path / "pipeline", tmp_path / "engine-home", facade=facade)
    events: list[EngineEvent] = []

    final = adapter.run("https://example.com/video.mp4", tmp_path / "backend-job", {}, None, events.append, threading.Event())

    assert final["ok"] is True
    assert final["job_id"] == "engine-public-1"
    assert final["clips"][0]["filename"] == "clip_00.mp4"
    assert final["clips"][0]["download_ready"] is True
    assert [event.kind for event in events] == ["job", "progress"]
    assert events[-1].stage == "ingest"
    assert facade.calls[:2] == [("create_job", "https://example.com/video.mp4"), ("start_job", "engine-public-1")]


def test_pipeline_facade_adapter_normalizes_public_errors(tmp_path: Path):
    class BrokenFacade:
        def create_job(self, *args, **kwargs):
            from publikclip_pipeline.engine import EngineError
            raise EngineError("bad source", "INVALID_SOURCE", recoverable=False)

    adapter = PipelineFacadeEngine(tmp_path / "pipeline", tmp_path / "home", facade=BrokenFacade())
    try:
        adapter.create_job("bad")
    except Exception as error:
        assert error.__class__.__name__ == "EngineError"
        assert error.code == "INVALID_SOURCE"
        assert error.recoverable is False
    else:
        raise AssertionError("expected normalized EngineError")



def test_pipeline_facade_adapter_exposes_full_lifecycle_surface(tmp_path: Path):
    facade = PublicFacadeDouble(tmp_path / "engine-home")
    adapter = PipelineFacadeEngine(tmp_path / "pipeline", tmp_path / "engine-home", facade=facade)

    job = adapter.create_job("/tmp/source.mp4", {"mode": "fast"})
    assert job.id == "engine-public-1"
    assert adapter.get_status(job.id).status == "done"
    assert adapter.get_progress(job.id)["fraction"] == 1.0
    assert adapter.get_results(job.id).job_id == job.id
    assert adapter.cancel_job(job.id).id == job.id
    rendered = adapter.render_clip(job.id, 0)
    assert rendered["filename"] == "clip_00.mp4"
    assert rendered["download_ready"] is True
    assert {name for name, _ in facade.calls} >= {"create_job", "get_results", "cancel_job", "render_clip"}


def test_pipeline_facade_adapter_cancels_running_public_job(tmp_path: Path):
    facade = PublicFacadeDouble(tmp_path / "engine-home")
    started = threading.Event()
    cancelled = threading.Event()

    def blocking_start(job_id, on_progress=None):
        started.set()
        while not cancelled.wait(0.01):
            pass
        from publikclip_pipeline.engine import EngineError
        raise EngineError("public job cancelled", "JOB_CANCELLED", recoverable=False)

    def cancel(job_id):
        cancelled.set()
        return facade.get_status(job_id)

    facade.start_job = blocking_start
    facade.cancel_job = cancel
    adapter = PipelineFacadeEngine(tmp_path / "pipeline", tmp_path / "engine-home", facade=facade)
    cancel_event = threading.Event()
    outcome: list[Exception] = []

    def run() -> None:
        try:
            adapter.run("/tmp/source.mp4", tmp_path / "backend-job", {}, None, lambda _event: None, cancel_event)
        except Exception as error:  # noqa: BLE001 - assertion target
            outcome.append(error)

    worker = threading.Thread(target=run)
    worker.start()
    assert started.wait(1)
    cancel_event.set()
    worker.join(2)

    assert not worker.is_alive()
    assert cancelled.is_set()
    assert outcome and outcome[0].code == "JOB_CANCELLED"
