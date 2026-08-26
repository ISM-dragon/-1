"""Focused reliability regressions for pipeline boundaries and side artifacts."""

from __future__ import annotations

import json
from types import SimpleNamespace

import numpy as np
import pytest

from publikclip_pipeline import config
from publikclip_pipeline.asr.stage import AsrStage
from publikclip_pipeline.camera.stage import CameraStage
from publikclip_pipeline.candidates.stage import CandidatesStage
from publikclip_pipeline.diarize.stage import _load_cached_embeddings
from publikclip_pipeline.events import stage as events_stage
from publikclip_pipeline.ingest import normalize
from publikclip_pipeline.models import registry
from publikclip_pipeline.models.registry import ModelSpec
from publikclip_pipeline.jobs import queue
from publikclip_pipeline.render import renderer
from publikclip_pipeline.render.stage import RenderStage
from publikclip_pipeline.scoring import frames, llm


def test_checkpoint_with_json_array_is_a_cache_miss(tmp_path, monkeypatch):
    monkeypatch.setenv("PUBLIKCLIP_HOME", str(tmp_path / "home"))
    job = queue.create_job("file", "/tmp/source.mp4", json.dumps({}))
    queue.checkpoint_path(job, "ingest").write_text("[]")
    assert queue.read_checkpoint(job, "ingest", 1) is None
    queue.checkpoint_path(job, "asr").write_text(json.dumps({"stage": "ingest", "schema_version": 1, "data": {}}))
    assert queue.read_checkpoint(job, "asr", 1) is None


def test_routed_provider_failure_is_normalized(monkeypatch):
    from publikclip_pipeline.scoring import providers

    def fail():
        raise providers.ProviderError("secret details must not escape")

    monkeypatch.setattr(llm.ProviderRouter, "from_disk", fail)
    with pytest.raises(llm.LlmError, match="provider profile is invalid") as raised:
        llm.RoutedProviderClient()
    assert raised.value.code == "PROVIDER_CONFIG_INVALID"
    assert "secret details" not in str(raised.value)


def test_corrupt_llm_cache_is_ignored_and_removed(tmp_path, monkeypatch):
    monkeypatch.setenv("PUBLIKCLIP_HOME", str(tmp_path / "home"))
    cache_dir = tmp_path / "cache"
    monkeypatch.setattr(llm, "_cache_dir", lambda: cache_dir)
    cache_dir.mkdir()
    cache_file = cache_dir / f"{llm._cache_key('gemini', 'm', 'p', {}, [])}.json"
    cache_file.write_text("not json")
    assert llm._read_cached_json(cache_file) is None
    assert not cache_file.exists()


def test_diarize_corrupt_embeddings_are_recomputed(tmp_path):
    path = tmp_path / "diar_embeddings.npy"
    path.write_bytes(b"truncated numpy")
    assert _load_cached_embeddings(path, expected_windows=2) is None
    assert not path.exists()


def test_candidates_corrupt_curves_are_a_stage_error(tmp_path):
    curves = tmp_path / "curves.json"
    curves.write_text("{")
    ctx = SimpleNamespace(
        prior={
            "ingest": {"probe": {"duration_sec": 10}},
            "diarize": {"segments": [], "turns": []},
            "events": {"curves_path": str(curves), "timeline": []},
        },
        job_dir=tmp_path,
        settings=SimpleNamespace(processing_mode="balanced"),
        emit=lambda *args, **kwargs: None,
    )
    with pytest.raises(queue.StageError, match="curves.json"):
        CandidatesStage().run(ctx)


def test_extract_frames_timeout_is_degraded(tmp_path, monkeypatch):
    def timeout(*args, **kwargs):
        raise __import__("subprocess").TimeoutExpired("ffmpeg", 120)

    monkeypatch.setattr(frames.subprocess, "run", timeout)
    assert frames.extract_frames("video.mp4", [1.0], tmp_path) == []


def test_render_cmd_is_cleaned_on_timeout(monkeypatch, tmp_path):
    monkeypatch.setattr(renderer, "videotoolbox_available", lambda: False)
    monkeypatch.setattr(renderer.ffmpeg_bin, "ffmpeg", lambda: "ffmpeg")

    def timeout(*args, **kwargs):
        raise __import__("subprocess").TimeoutExpired("ffmpeg", kwargs.get("timeout", 0))

    monkeypatch.setattr(renderer.subprocess, "run", timeout)
    out = tmp_path / "out.mp4"
    with pytest.raises(RuntimeError, match="timed out"):
        renderer.render_clip(
            "src.mp4", out, 0.0, 2.0,
            {"fps": 25, "frames": [[0, 0, 100, 100]]}, None, None,
            src_w=100, src_h=100, timeout=1,
        )
    assert not out.with_suffix(".cmd").exists()


def test_verify_output_handles_ffprobe_failure(monkeypatch, tmp_path):
    monkeypatch.setattr(renderer.ffmpeg_bin, "ffprobe", lambda: "ffprobe")
    monkeypatch.setattr(
        renderer.subprocess,
        "run",
        lambda *args, **kwargs: SimpleNamespace(returncode=1, stdout="not json", stderr="bad probe"),
    )
    result = renderer.verify_output(tmp_path / "broken.mp4", 10.0)
    assert result["ok"] is False


def test_ingest_empty_artifacts_are_not_fresh(tmp_path):
    media = tmp_path / "media.mp4"
    audio = tmp_path / "audio16k.wav"
    media.touch()
    audio.touch()
    ctx = SimpleNamespace(job_dir=tmp_path)
    assert not __import__("publikclip_pipeline.ingest.stage", fromlist=["IngestStage"]).IngestStage().artifacts_ok(
        ctx, {"media_path": str(media)}
    )


def test_asr_empty_audio_is_user_facing_error(tmp_path):
    audio = tmp_path / "audio16k.wav"
    audio.touch()
    ctx = SimpleNamespace(
        prior={"ingest": {"audio_path": str(audio)}},
        emit=lambda *args, **kwargs: None,
    )
    with pytest.raises(queue.StageError, match="empty"):
        AsrStage().run(ctx)


def test_normalize_timeout_is_ffmpeg_error(monkeypatch):
    def timeout(*args, **kwargs):
        raise __import__("subprocess").TimeoutExpired("ffmpeg", kwargs.get("timeout", 0))

    monkeypatch.setattr(normalize.subprocess, "run", timeout)
    with pytest.raises(normalize.FfmpegError, match="timed out"):
        normalize._run(["ffmpeg", "-version"], timeout=1)


def test_model_registry_rejects_invalid_existing_checkpoint(tmp_path, monkeypatch):
    monkeypatch.setenv("PUBLIKCLIP_HOME", str(tmp_path / "home"))
    spec = ModelSpec("test-model", "weights.bin", "https://invalid", sha256="0" * 64)
    path = registry.model_path(spec)
    path.parent.mkdir(parents=True)
    path.write_bytes(b"corrupt")
    assert not registry.is_present(spec)


def test_events_audio_extraction_timeout_is_stage_error(monkeypatch, tmp_path):
    def timeout(*args, **kwargs):
        raise __import__("subprocess").TimeoutExpired("ffmpeg", 3600)

    monkeypatch.setattr(events_stage.subprocess, "run", timeout)
    with pytest.raises(queue.StageError, match="timed out"):
        events_stage._extract_wav(tmp_path / "media.mp4", tmp_path / "audio32k.wav", 32000)


def test_events_rejects_missing_audio_before_model_load(tmp_path):
    ctx = SimpleNamespace(
        prior={
            "ingest": {"media_path": str(tmp_path / "media.mp4"), "audio_path": str(tmp_path / "audio.wav")},
            "asr": {"segments": []},
        },
        job_dir=tmp_path,
        settings=config.Settings(),
        emit=lambda *args, **kwargs: None,
    )
    with pytest.raises(queue.StageError, match="inputs are invalid"):
        events_stage.EventsStage().run(ctx)


def test_render_stage_rejects_corrupt_trajectory(monkeypatch, tmp_path):
    media = tmp_path / "media.mp4"
    media.write_bytes(b"media")
    curves = tmp_path / "curves.json"
    curves.write_text(json.dumps({"rms": [], "grid_sec": 0.1}))
    trajectory = tmp_path / "trajectory_00.json"
    trajectory.write_text("{")
    monkeypatch.setattr("publikclip_pipeline.render.ffmpeg_bin.supports_captions", lambda: False)
    monkeypatch.setattr("publikclip_pipeline.render.ffmpeg_bin.ensure_capable", lambda progress: False)
    ctx = SimpleNamespace(
        prior={
            "ingest": {"media_path": str(media), "probe": {"width": 100, "height": 100}},
            "diarize": {"segments": []},
            "events": {"timeline": [], "curves_path": str(curves)},
            "score": {"clips": [{"start": 0, "end": 2, "score": 1, "best_platform": "reels"}]},
            "camera": {"trajectories": {"0": str(trajectory)}},
        },
        job_dir=tmp_path,
        settings=config.Settings(),
        emit=lambda *args, **kwargs: None,
    )
    with pytest.raises(queue.StageError, match="trajectory_00.json"):
        RenderStage().run(ctx)


def test_camera_corrupt_trajectory_invalidates_checkpoint(tmp_path):
    trajectory = tmp_path / "trajectory_00.json"
    trajectory.write_text("{")
    ctx = SimpleNamespace(settings=config.Settings())
    assert not CameraStage().artifacts_ok(
        SimpleNamespace(job_dir=tmp_path, settings=ctx.settings),
        {"camera_settings": {}, "trajectories": {"0": str(trajectory)}},
    )


def test_arousal_dsp_preserves_remainder_bin():
    from publikclip_pipeline.events.ser import arousal_curve_dsp

    output = arousal_curve_dsp([0.1] * 11, 0.1)
    assert len(output) == 3
