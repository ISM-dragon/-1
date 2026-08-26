"""Contract tests for the public processing engine."""

from __future__ import annotations

import json

import pytest

from publikclip_pipeline import config
from publikclip_pipeline.engine import EngineError, PipelineEngine, ProgressEvent
from publikclip_pipeline.jobs import queue


@pytest.fixture(autouse=True)
def isolated_home(tmp_path, monkeypatch):
    monkeypatch.setenv("PUBLIKCLIP_HOME", str(tmp_path / "home"))


class CountingStage(queue.Stage):
    name = "counting"
    schema_version = 1

    def __init__(self):
        self.runs = 0

    def run(self, ctx):
        self.runs += 1
        return {"runs": self.runs}


class FailingStage(queue.Stage):
    name = "failing"
    schema_version = 1

    def run(self, ctx):
        raise queue.StageError("controlled failure", "CONTROLLED_FAILURE")


def test_public_engine_runs_and_emits_contract_events():
    stage = CountingStage()
    engine = PipelineEngine(lambda: [stage])
    job = engine.create_job("/tmp/input.mp4", config.Settings())
    events: list[ProgressEvent] = []

    result = engine.start_job(job.id, events.append)

    assert result.job_id == job.id
    assert result.ingest is None
    assert events[-1].stage == "counting"
    assert events[-1].fraction == 1.0
    status = engine.get_job_status(job.id)
    assert status.status == "done"
    assert status.stages == {"counting": "done"}


def test_checkpoint_is_reused_by_resume():
    stage = CountingStage()
    engine = PipelineEngine(lambda: [stage])
    job = engine.create_job("/tmp/input.mp4")

    engine.start_job(job.id)
    result = engine.resume_job(job.id)

    assert stage.runs == 1
    assert result.job_id == job.id
    assert json.loads((config.jobs_dir() / job.id / "counting.json").read_text())["data"] == {"runs": 1}


def test_failure_is_converted_to_stable_engine_error_and_resume_skips_checkpoint():
    stage = CountingStage()
    use_fixed_stage = False

    class FixedStage(queue.Stage):
        name = "failing"
        schema_version = 1

        def run(self, ctx):
            return {"recovered": True}

    def factory():
        return [stage, FixedStage()] if use_fixed_stage else [stage, FailingStage()]

    engine = PipelineEngine(factory)
    job = engine.create_job("/tmp/input.mp4")
    with pytest.raises(EngineError) as error:
        engine.start_job(job.id)
    assert error.value.code == "CONTROLLED_FAILURE"
    assert engine.get_job_status(job.id).status == "failed"

    use_fixed_stage = True
    result = engine.resume_job(job.id)
    assert stage.runs == 1
    assert result.job_id == job.id


def test_cancel_and_resume_preserve_job_identity():
    engine = PipelineEngine(lambda: [CountingStage()])
    job = engine.create_job("/tmp/input.mp4")

    cancelled = engine.cancel_job(job.id)
    assert cancelled.status == "cancelled"
    assert cancelled.cancel_requested is True

    # Resume clears the durable cancellation marker and keeps the same id.
    result = engine.resume_job(job.id)
    assert result.job_id == job.id
    assert engine.get_job_status(job.id).status == "done"


def test_get_clip_reads_score_and_render_checkpoints():
    engine = PipelineEngine(lambda: [])
    job = engine.create_job("/tmp/input.mp4")
    stored = queue.get_job(job.id)
    assert stored is not None
    queue.write_checkpoint(stored, "score", 1, {"clips": [{"score": 91, "start": 1, "end": 4}]})
    queue.write_checkpoint(stored, "render", 1, {"outputs": [{"clip": 0, "path": "/tmp/clip.mp4"}]})

    clip = engine.get_clip(job.id, 0)
    assert clip.index == 0
    assert clip.score == {"score": 91, "start": 1, "end": 4}
    assert clip.artifact == {"clip": 0, "path": "/tmp/clip.mp4"}


def test_missing_job_has_safe_error_code():
    engine = PipelineEngine(lambda: [])
    with pytest.raises(EngineError) as error:
        engine.get_job_status("missing")
    assert error.value.code == "JOB_NOT_FOUND"



def test_direct_lifecycle_aliases_and_durable_progress():
    stage = CountingStage()
    engine = PipelineEngine(lambda: [stage])
    job = engine.create_job("/tmp/input.mp4")

    result = engine.start_job(job.id)

    assert engine.status(job.id) == engine.get_job_status(job.id)
    assert engine.results(job.id) == engine.get_job_results(job.id)
    assert engine.progress(job.id)["fraction"] == 1.0
    assert queue.get_progress(job.id)["stage"] == "counting"
    assert engine.render(job.id).job_id == result.job_id


def test_status_and_progress_use_public_stage_names():
    engine = PipelineEngine(lambda: [])
    job = engine.create_job("/tmp/input.mp4")
    queue.record_progress(job.id, "diarize", 0.5, "working")

    status = engine.status(job.id)
    progress = engine.progress(job.id)

    assert status.stage == "diarization"
    assert progress["stage"] == "diarization"
    assert progress["fraction"] == 0.0625



def test_required_facade_method_names_are_supported():
    engine = PipelineEngine(lambda: [])
    job = engine.create_job("/tmp/input.mp4")

    assert engine.get_status(job.id) == engine.get_job_status(job.id)
    assert engine.get_progress(job.id) == engine.progress(job.id)
    assert engine.get_results(job.id) == engine.get_job_results(job.id)
