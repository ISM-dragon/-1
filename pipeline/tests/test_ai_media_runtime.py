from __future__ import annotations

import hashlib
import os
import stat
import subprocess
from pathlib import Path

import pytest

from publikclip_pipeline.models.registry import ModelSpec
from publikclip_pipeline.runtime.media_manager import MediaManager, MediaRuntimeError
from publikclip_pipeline.runtime.model_manager import ModelManager, ModelRuntimeError


FFMPEG = os.environ.get("TEST_FFMPEG", "ffmpeg")
FFPROBE = os.environ.get("TEST_FFPROBE", "ffprobe")


def _make_video(path: Path, *, audio: bool = True, duration: int = 2, size: str = "320x180") -> None:
    cmd = [FFMPEG, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", f"testsrc=size={size}:rate=24"]
    if audio:
        cmd += ["-f", "lavfi", "-i", "sine=frequency=880:sample_rate=16000"]
    cmd += ["-t", str(duration), "-c:v", "libx264", "-pix_fmt", "yuv420p"]
    if audio:
        cmd += ["-c:a", "aac", "-shortest"]
    else:
        cmd += ["-an"]
    cmd += [str(path)]
    subprocess.run(cmd, check=True, capture_output=True)


@pytest.fixture
def media_files(tmp_path: Path):
    valid = tmp_path / "valid.mp4"
    no_audio = tmp_path / "no-audio.mp4"
    _make_video(valid, duration=2)
    _make_video(no_audio, audio=False, duration=2)
    return valid, no_audio


def test_valid_video_probe_extract_transcode_and_cleanup(media_files, tmp_path: Path):
    valid, _ = media_files
    manager = MediaManager(ffmpeg_path=FFMPEG, ffprobe_path=FFPROBE)
    probe = manager.probe(valid)
    assert probe.duration_sec > 0
    assert probe.has_audio is True
    assert manager.validate(valid, require_audio=True)["valid"] is True

    audio = manager.extract_audio(valid, tmp_path / "audio.wav")
    frames = manager.extract_frames(valid, tmp_path / "frames", fps=2)
    output = manager.transcode(valid, tmp_path / "transcoded.mp4")
    assert audio.exists() and audio.stat().st_size > 44
    assert frames and all(frame.exists() for frame in frames)
    assert output.exists() and manager.probe(output).has_audio
    assert manager.cleanup([audio, tmp_path / "frames"]) == 2


def test_broken_video_is_media_invalid(tmp_path: Path):
    broken = tmp_path / "broken.mp4"
    broken.write_bytes(b"not an mp4")
    manager = MediaManager(ffmpeg_path=FFMPEG, ffprobe_path=FFPROBE)
    with pytest.raises(MediaRuntimeError) as exc:
        manager.probe(broken)
    assert exc.value.code == "MEDIA_INVALID"


def test_no_audio_is_rejected_only_when_audio_is_required(media_files):
    _, no_audio = media_files
    manager = MediaManager(ffmpeg_path=FFMPEG, ffprobe_path=FFPROBE)
    assert manager.validate(no_audio)["has_audio"] is False
    with pytest.raises(MediaRuntimeError) as exc:
        manager.validate(no_audio, require_audio=True)
    assert exc.value.code == "MEDIA_INVALID"
    with pytest.raises(MediaRuntimeError) as exc:
        manager.extract_audio(no_audio, no_audio.with_suffix(".wav"))
    assert exc.value.code == "MEDIA_INVALID"


def test_large_video_is_processed_without_loading_whole_file(tmp_path: Path):
    large = tmp_path / "large.mp4"
    _make_video(large, duration=8, size="1280x720")
    manager = MediaManager(ffmpeg_path=FFMPEG, ffprobe_path=FFPROBE)
    assert manager.probe(large).width == 1280
    frames = manager.extract_frames(large, tmp_path / "large-frames", fps=1)
    assert len(frames) >= 8


def test_ffmpeg_failure_is_classified(tmp_path: Path):
    failing = tmp_path / "ffmpeg-fails"
    failing.write_text("#!/bin/sh\necho broken >&2\nexit 1\n")
    failing.chmod(failing.stat().st_mode | stat.S_IXUSR)
    manager = MediaManager(ffmpeg_path=str(failing), ffprobe_path=FFPROBE)
    state = manager.status()
    assert state["valid"] is False
    assert state["error_code"] == "FFMPEG_INVALID"


def test_missing_ffmpeg_is_classified(tmp_path: Path):
    manager = MediaManager(ffmpeg_path=str(tmp_path / "missing-ffmpeg"), ffprobe_path=FFPROBE)
    state = manager.check()
    assert state["error_code"] == "FFMPEG_MISSING"


def test_model_status_verify_load_unload_delete(tmp_path: Path):
    content = b"valid model weights"
    spec = ModelSpec(
        "test-model",
        "weights.bin",
        "https://example.invalid/weights.bin",
        sha256=hashlib.sha256(content).hexdigest(),
        version="1.0.0",
        size_bytes=len(content),
        source="test fixture",
        hardware={"device": "cpu"},
    )
    manager = ModelManager([spec], models_root=tmp_path)
    assert manager.check(spec)["state"] == "missing"
    path = tmp_path / "test-model" / "weights.bin"
    path.parent.mkdir()
    path.write_bytes(content)
    assert manager.verify(spec) == path
    loaded = manager.load(spec, loader=lambda p: {"path": str(p)})
    assert loaded["path"] == str(path)
    assert manager.status(spec)["state"] == "loaded"
    assert manager.unload(spec) is True
    assert manager.delete(spec) is True
    assert manager.check(spec)["available"] is False


def test_model_missing_corrupt_and_external_download_errors(tmp_path: Path):
    spec = ModelSpec(
        "missing-model",
        "weights.bin",
        "",
        version="2.0.0",
        source="external cache",
        managed=False,
    )
    manager = ModelManager([spec], models_root=tmp_path)
    with pytest.raises(ModelRuntimeError) as exc:
        manager.verify(spec)
    assert exc.value.code == "MODEL_MISSING"
    with pytest.raises(ModelRuntimeError) as exc:
        manager.download(spec)
    assert exc.value.code == "MODEL_DOWNLOAD_FAILED"

    corrupt = ModelSpec("corrupt-model", "weights.bin", "", sha256="0" * 64)
    corrupt_path = tmp_path / "corrupt-model" / "weights.bin"
    corrupt_path.parent.mkdir()
    corrupt_path.write_bytes(b"bad")
    corrupt_manager = ModelManager([corrupt], models_root=tmp_path)
    with pytest.raises(ModelRuntimeError) as exc:
        corrupt_manager.verify(corrupt)
    assert exc.value.code == "MODEL_CORRUPTED"


class _RangeResponse:
    status_code = 206
    headers = {"content-length": "3"}

    def __init__(self, payload: bytes):
        self.payload = payload

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def iter_bytes(self):
        yield self.payload


def test_model_resume_uses_partial_file_and_range(monkeypatch, tmp_path: Path):
    payload = b"model"
    spec = ModelSpec(
        "resume-model",
        "weights.bin",
        "https://example.invalid/weights.bin",
        sha256=hashlib.sha256(payload).hexdigest(),
        size_bytes=len(payload),
    )
    manager = ModelManager([spec], models_root=tmp_path)
    path = tmp_path / "resume-model" / "weights.bin"
    path.parent.mkdir()
    path.with_suffix(".bin.part").write_bytes(payload[:2])
    seen_headers = {}

    def fake_stream(*args, **kwargs):
        seen_headers.update(kwargs.get("headers", {}))
        return _RangeResponse(payload[2:])

    monkeypatch.setattr("publikclip_pipeline.runtime.model_manager.httpx.stream", fake_stream)
    assert manager.resume(spec).read_bytes() == payload
    assert seen_headers["Range"] == "bytes=2-"
