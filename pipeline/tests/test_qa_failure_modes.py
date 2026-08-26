from types import SimpleNamespace
from unittest.mock import patch

import pytest

from publikclip_pipeline.asr.stage import AsrStage
from publikclip_pipeline.engine import EngineError, PipelineEngine
from publikclip_pipeline.jobs import queue


class ValidVideoStage(queue.Stage):
    name = "ingest"
    schema_version = 1

    def run(self, ctx):
        return {"media_path": "/fixtures/valid.mp4", "audio_path": "/fixtures/audio16k.wav"}


class BrokenVideoStage(queue.Stage):
    name = "ingest"
    schema_version = 1

    def run(self, ctx):
        raise queue.StageError("Video probe failed", "MEDIA_INVALID")


class FfmpegFailureStage(queue.Stage):
    name = "render"
    schema_version = 1

    def run(self, ctx):
        raise queue.StageError("FFmpeg failed", "FFMPEG_FAILED")


def test_valid_video_contract_completes_without_external_models(tmp_path, monkeypatch):
    monkeypatch.setenv("PUBLIKCLIP_HOME", str(tmp_path / "home"))
    engine = PipelineEngine(lambda: [ValidVideoStage()])
    job = engine.create_job(str(tmp_path / "valid.mp4"))
    result = engine.start_job(job.id)
    assert result.job_id == job.id
    assert engine.get_job_status(job.id).status == "done"


def test_broken_video_is_a_stable_engine_error(tmp_path, monkeypatch):
    monkeypatch.setenv("PUBLIKCLIP_HOME", str(tmp_path / "home"))
    engine = PipelineEngine(lambda: [BrokenVideoStage()])
    job = engine.create_job(str(tmp_path / "broken.mp4"))
    with pytest.raises(EngineError) as error:
        engine.start_job(job.id)
    assert error.value.code == "MEDIA_INVALID"
    assert engine.get_job_status(job.id).status == "failed"


def test_no_audio_is_rejected_before_loading_the_model(tmp_path):
    context = SimpleNamespace(
        prior={"ingest": {"audio_path": str(tmp_path / "audio16k.wav")}},
        job_dir=tmp_path,
        emit=lambda *_args, **_kwargs: None,
    )
    with patch("publikclip_pipeline.asr.stage._point_caches_at_home") as cache_setup:
        with pytest.raises(queue.StageError) as error:
            AsrStage().run(context)
    assert error.value.code == "ASR_AUDIO_INVALID"
    cache_setup.assert_not_called()


def test_missing_model_is_safe_and_retryable_after_valid_audio(tmp_path):
    audio = tmp_path / "audio16k.wav"
    audio.write_bytes(b"0" * 45)
    context = SimpleNamespace(
        prior={"ingest": {"audio_path": str(audio)}},
        job_dir=tmp_path,
        emit=lambda *_args, **_kwargs: None,
    )
    with patch.dict("sys.modules", {"torch": None, "whisperx": None}):
        with pytest.raises(queue.StageError) as error:
            AsrStage().run(context)
    assert error.value.code == "ASR_MODEL_UNAVAILABLE"


def test_ffmpeg_failure_does_not_create_a_successful_engine_result(tmp_path, monkeypatch):
    monkeypatch.setenv("PUBLIKCLIP_HOME", str(tmp_path / "home"))
    engine = PipelineEngine(lambda: [FfmpegFailureStage()])
    job = engine.create_job(str(tmp_path / "valid.mp4"))
    with pytest.raises(EngineError) as error:
        engine.start_job(job.id)
    assert error.value.code == "FFMPEG_FAILED"
    assert engine.get_job_status(job.id).status == "failed"
