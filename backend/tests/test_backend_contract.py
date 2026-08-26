from __future__ import annotations

import threading
from pathlib import Path

from backend.engine import EngineEvent, FacadePublikclipEngine


class PublicResult:
    def to_dict(self):
        return {"job_id": "engine-1", "artifacts": [{"clip": 0, "path": "/tmp/clip.mp4"}]}


class PublicReference:
    id = "engine-1"

    def to_dict(self):
        return {"id": self.id, "job_id": self.id, "contract_version": 1}


class StubPublicEngine:
    def __init__(self):
        self.created = []
        self.cancelled = []

    def create_job(self, source, settings=None, *, source_type=None):
        self.created.append((source, dict(settings), source_type))
        return PublicReference()

    def start_job(self, job_id, on_progress=None):
        if on_progress:
            on_progress(type("Event", (), {"stage": "ingest", "fraction": 0.5, "message": "halfway"})())
        return PublicResult()

    def resume_job(self, job_id, settings=None, on_progress=None):
        return PublicResult()

    def cancel_job(self, job_id):
        self.cancelled.append(job_id)

    def render_clip(self, job_id, clip_index, on_progress=None):
        return {"clip": clip_index, "artifact": {"filename": "clip.mp4"}}


def test_facade_maps_public_engine_events_and_results(tmp_path: Path):
    adapter = FacadePublikclipEngine(tmp_path / "pipeline", tmp_path / "home")
    public = StubPublicEngine()
    adapter._engine = public
    events: list[EngineEvent] = []

    result = adapter.run(
        "/tmp/source.mp4",
        tmp_path / "job",
        {"mode": "fast", "llm": "local"},
        None,
        events.append,
        threading.Event(),
    )

    assert result["ok"] is True
    assert result["job_id"] == "engine-1"
    assert public.created == [("/tmp/source.mp4", {"mode": "fast", "llm": "local", "processing_mode": "fast", "llm_mode": "local"}, "file")]
    assert events[0].kind == "job"
    assert events[-1].stage == "ingest"
    assert events[-1].progress == 0.5


def test_facade_does_not_leak_raw_engine_exception():
    adapter = FacadePublikclipEngine(Path("/tmp/pipeline"), Path("/tmp/home"))

    class Broken:
        def create_job(self, *args, **kwargs):
            raise RuntimeError("secret=top-secret /private/internal/file.mp4")

    adapter._engine = Broken()
    try:
        adapter.run("/tmp/source.mp4", Path("/tmp/job"), {}, None, lambda _: None, threading.Event())
    except Exception as error:  # noqa: BLE001 - contract assertion
        assert getattr(error, "code") == "ENGINE_FAILED"
        assert "top-secret" not in str(error)
        assert "/private/internal/file.mp4" not in str(error)
    else:
        raise AssertionError("adapter should normalize public engine errors")
