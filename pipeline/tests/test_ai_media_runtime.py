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
    command = [FFMPEG, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", f"testsrc=size={size}:rate=24"]
    if audio:
        command += ["-f", "lavfi", "-i", "sine=frequency=880:sample_rate=16000"]
    command += ["-t", str(duration), "-c:v", "libx264", "-pix_fmt", "yuv420p"]
    command += ["-c:a", "aac", "-shortest"] if audio else ["-an"]
    command += [str(path)]
    subprocess.run(command, check=True, capture_output=True)


@pytest.fixture
def media_files(tmp_path: Path):
    valid = tmp_path / "valid.mp4"
    no_audio = tmp_path / "no-audio.mp4"
    _make_video(valid)
    _make_video(no_audio, audio=False)
    return valid, no_audio


def test_valid_video_probe_extract_transcode_and_cleanup(media_files, tmp_path: Path):
    valid, _ = media_files
    manager = MediaManager(ffmpeg_path=FFMPEG, ffprobe_path=FFPROBE)
    probe = manager.probe(valid)
    assert probe.duration_sec > 0 and probe.has_audio
    assert manager.validate(valid, require_audio=True)["valid"] is True
    audio = manager.extract_audio(valid, tmp_path / "audio.wav")
    frames = manager.extract_frames(valid, tmp_path / "frames", fps=2)
    output = manager.transcode(valid, tmp_path / "transcoded.mp4")
    assert audio.exists() and audio.stat().st_size > 44
    assert frames and all(frame.exists() for frame in frames)
    assert output.exists() and manager.probe(output).has_audio
    assert manager.cleanup([audio, tmp_path / "frames"]) == 2


def test_broken_and_no_audio_media_are_classified(media_files, tmp_path: Path):
    _, no_audio = media_files
    broken = tmp_path / "broken.mp4"
    broken.write_bytes(b"not an mp4")
    manager = MediaManager(ffmpeg_path=FFMPEG, ffprobe_path=FFPROBE)
    with pytest.raises(MediaRuntimeError) as error:
        manager.probe(broken)
    assert error.value.code == "MEDIA_INVALID"
    assert manager.validate(no_audio)["has_audio"] is False
    with pytest.raises(MediaRuntimeError) as error:
        manager.validate(no_audio, require_audio=True)
    assert error.value.code == "MEDIA_INVALID"


def test_missing_ffmpeg_is_classified(tmp_path: Path):
    manager = MediaManager(ffmpeg_path=str(tmp_path / "missing-ffmpeg"), ffprobe_path=FFPROBE)
    assert manager.check()["error_code"] == "FFMPEG_MISSING"


def test_model_status_verify_load_unload_delete(tmp_path: Path):
    content = b"valid model weights"
    spec = ModelSpec("test-model", "weights.bin", "https://example.invalid/weights.bin", hashlib.sha256(content).hexdigest(), 1, "1.0.0", len(content), "test fixture")
    manager = ModelManager([spec], models_root=tmp_path)
    assert manager.check(spec)["state"] == "missing"
    path = tmp_path / "test-model" / "weights.bin"
    path.parent.mkdir()
    path.write_bytes(content)
    assert manager.verify(spec) == path
    assert manager.load(spec, loader=lambda loaded: {"path": str(loaded)})["path"] == str(path)
    assert manager.status(spec)["state"] == "loaded"
    assert manager.unload(spec) is True
    assert manager.delete(spec) is True
    assert manager.check(spec)["available"] is False


def test_model_missing_and_corrupt_are_classified(tmp_path: Path):
    missing = ModelSpec("missing-model", "weights.bin", "", None, 0, "2.0.0", None, "external cache", managed=False)
    manager = ModelManager([missing], models_root=tmp_path)
    with pytest.raises(ModelRuntimeError) as error:
        manager.verify(missing)
    assert error.value.code == "MODEL_MISSING"
    corrupt = ModelSpec("corrupt-model", "weights.bin", "", "0" * 64)
    path = tmp_path / "corrupt-model" / "weights.bin"
    path.parent.mkdir()
    path.write_bytes(b"bad")
    with pytest.raises(ModelRuntimeError) as error:
        ModelManager([corrupt], models_root=tmp_path).verify(corrupt)
    assert error.value.code == "MODEL_INVALID"


def test_model_resume_uses_partial_file_and_range(monkeypatch, tmp_path: Path):
    payload = b"model"
    spec = ModelSpec("resume-model", "weights.bin", "https://example.invalid/weights.bin", hashlib.sha256(payload).hexdigest(), 0, "unversioned", len(payload))
    manager = ModelManager([spec], models_root=tmp_path)
    path = tmp_path / "resume-model" / "weights.bin"
    path.parent.mkdir()
    path.with_suffix(".bin.part").write_bytes(payload[:2])
    seen_headers: dict[str, str] = {}

    class Response:
        status_code = 206
        headers = {"content-length": "3"}

        def __enter__(self):
            return self

        def __exit__(self, *args):
            return False

        def iter_bytes(self):
            yield payload[2:]

    def fake_stream(*args, **kwargs):
        seen_headers.update(kwargs.get("headers", {}))
        return Response()

    monkeypatch.setattr("publikclip_pipeline.runtime.model_manager.httpx.stream", fake_stream)
    assert manager.resume(spec).read_bytes() == payload
    assert seen_headers["Range"] == "bytes=2-"
