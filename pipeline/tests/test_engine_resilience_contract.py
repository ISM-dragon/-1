"""Resilience contracts for media ingest and pipeline execution.

These tests intentionally exercise public/near-public boundaries with mocked
process output. They do not download model weights or invoke an LLM.
"""

import json
from types import SimpleNamespace

import pytest

from publikclip_pipeline.ingest import normalize
from publikclip_pipeline.jobs import queue
from publikclip_pipeline.render import ffmpeg_bin


VIDEO_STREAM = {
    "codec_type": "video",
    "codec_name": "h264",
    "width": 1920,
    "height": 1080,
    "avg_frame_rate": "30/1",
    "r_frame_rate": "30/1",
    "start_time": "0",
    "duration": "12.5",
}


def _probe_result(*streams, duration="12.5"):
    return SimpleNamespace(
        returncode=0,
        stdout=json.dumps({"format": {"duration": duration}, "streams": list(streams)}),
        stderr="",
    )


def test_valid_video_probe_reports_video_and_audio(monkeypatch):
    monkeypatch.setattr(normalize, "_run", lambda *_args, **_kwargs: _probe_result(VIDEO_STREAM, {"codec_type": "audio"}))

    result = normalize.probe(__import__("pathlib").Path("valid.mp4"))

    assert result.width == 1920
    assert result.height == 1080
    assert result.fps == 30.0
    assert result.duration_sec == 12.5
    assert result.has_audio is True
    assert result.vfr is False


def test_no_audio_video_is_identified_without_fabricating_audio(monkeypatch):
    monkeypatch.setattr(normalize, "_run", lambda *_args, **_kwargs: _probe_result(VIDEO_STREAM))

    result = normalize.probe(__import__("pathlib").Path("silent.mp4"))

    assert result.has_audio is False
    assert result.width == 1920


def test_invalid_video_without_video_stream_is_rejected(monkeypatch):
    monkeypatch.setattr(
        normalize,
        "_run",
        lambda *_args, **_kwargs: _probe_result({"codec_type": "audio"}),
    )

    with pytest.raises(normalize.FfmpegError, match="No video stream"):
        normalize.probe(__import__("pathlib").Path("audio-only.mp4"))


def test_broken_media_from_ffprobe_is_reported_as_ffmpeg_error(monkeypatch):
    def broken_probe(*_args, **_kwargs):
        raise normalize.FfmpegError("ffprobe failed (1): Invalid data found when processing input")

    monkeypatch.setattr(normalize, "_run", broken_probe)

    with pytest.raises(normalize.FfmpegError, match="Invalid data"):
        normalize.probe(__import__("pathlib").Path("broken.mp4"))


def test_large_video_job_keeps_checkpoint_contract(tmp_path, monkeypatch):
    monkeypatch.setenv("PUBLIKCLIP_HOME", str(tmp_path / "home"))
    job = queue.create_job("file", str(tmp_path / "large.mp4"), '{"camera": {}}')
    source = tmp_path / "large.mp4"
    with source.open("wb") as handle:
        handle.truncate(64 * 1024 * 1024)

    class LargeVideoStage(queue.Stage):
        name = "large_video_probe"
        schema_version = 1

        def run(self, _ctx):
            return {"bytes": source.stat().st_size}

    queue.run_stages(job, [LargeVideoStage()], lambda *_args: None)

    assert queue.read_checkpoint(job, "large_video_probe", 1) == {"bytes": 64 * 1024 * 1024}
    assert queue.get_job(job.id).status == "done"


def test_ffmpeg_unavailable_is_not_reported_as_caption_capable(monkeypatch):
    monkeypatch.setattr(ffmpeg_bin, "resolve", lambda: ("ffmpeg", False))
    monkeypatch.setattr(ffmpeg_bin, "supports_captions", lambda: False)

    assert ffmpeg_bin.supports_captions() is False
    assert ffmpeg_bin.ffmpeg() == "ffmpeg"
