"""Safe, classified FFmpeg operations for the media runtime."""

from __future__ import annotations

import json
import os
import shutil
import subprocess
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable

from .. import config
from ..render import ffmpeg_bin
from .hardware import HardwareInfo, inspect_resources


class MediaRuntimeError(RuntimeError):
    """A stable error that callers can expose without a raw subprocess crash."""

    def __init__(self, code: str, message: str, *, path: Path | None = None):
        self.code = code
        self.path = str(path) if path else None
        super().__init__(message)


@dataclass(frozen=True)
class MediaProbe:
    path: str
    duration_sec: float
    width: int
    height: int
    fps: float
    video_codec: str
    audio_codec: str | None
    has_audio: bool
    format_name: str

    def to_dict(self) -> dict:
        return asdict(self)


class MediaManager:
    """Own FFmpeg discovery and bounded media subprocess operations."""

    def __init__(
        self,
        *,
        ffmpeg_path: str | None = None,
        ffprobe_path: str | None = None,
        hardware: HardwareInfo | None = None,
    ) -> None:
        self._ffmpeg_override = ffmpeg_path
        self._ffprobe_override = ffprobe_path
        self._hardware = hardware or inspect_resources(config.home_dir())

    @property
    def ffmpeg_path(self) -> str:
        return self._ffmpeg_override or ffmpeg_bin.ffmpeg()

    @property
    def ffprobe_path(self) -> str:
        if self._ffprobe_override:
            return self._ffprobe_override
        if self._ffmpeg_override:
            sibling = Path(self._ffmpeg_override).with_name("ffprobe" + (".exe" if os.name == "nt" else ""))
            return str(sibling) if sibling.exists() else "ffprobe"
        return ffmpeg_bin.ffprobe()

    def _run(self, args: list[str], *, media_path: Path | None = None, timeout: float) -> subprocess.CompletedProcess:
        try:
            proc = subprocess.run(args, capture_output=True, text=True, timeout=timeout, check=False)
        except FileNotFoundError as err:
            raise MediaRuntimeError("FFMPEG_MISSING", f"Required executable is not installed: {args[0]}", path=media_path) from err
        except subprocess.TimeoutExpired as err:
            raise MediaRuntimeError("FFMPEG_INVALID", f"FFmpeg operation timed out after {timeout:.0f}s.", path=media_path) from err
        except OSError as err:
            raise MediaRuntimeError("FFMPEG_INVALID", f"FFmpeg could not start: {err}", path=media_path) from err
        if proc.returncode != 0:
            tail = (proc.stderr or proc.stdout or "")[-1200:]
            code = "MEDIA_INVALID" if media_path is not None else "FFMPEG_INVALID"
            raise MediaRuntimeError(code, f"FFmpeg operation failed: {tail.strip() or proc.returncode}", path=media_path)
        return proc

    def check(self) -> dict:
        """Return readiness details without raising for missing FFmpeg."""
        result = {
            "ffmpeg_path": self.ffmpeg_path,
            "ffprobe_path": self.ffprobe_path,
            "valid": False,
            "has_subtitles": False,
            "version": None,
            "hardware_profile": self._hardware.profile,
        }
        try:
            version = self._run([self.ffmpeg_path, "-hide_banner", "-version"], timeout=10)
            filters = self._run([self.ffmpeg_path, "-hide_banner", "-filters"], timeout=10)
            self._run([self.ffprobe_path, "-hide_banner", "-version"], timeout=10)
            result["valid"] = True
            result["version"] = (version.stdout.splitlines() or [""])[0]
            result["has_subtitles"] = " subtitles " in filters.stdout
        except MediaRuntimeError as err:
            result["error_code"] = err.code
            result["error"] = str(err)
        return result

    def status(self) -> dict:
        return self.check()

    def _require_valid_tools(self) -> None:
        state = self.check()
        if state.get("valid"):
            return
        code = state.get("error_code", "FFMPEG_INVALID")
        raise MediaRuntimeError(code, state.get("error", "FFmpeg runtime is not ready."))

    def _probe_json(self, path: Path) -> dict:
        if not path.is_file() or path.stat().st_size == 0:
            raise MediaRuntimeError("MEDIA_INVALID", "Media file is missing or empty.", path=path)
        self._require_valid_tools()
        proc = self._run(
            [self.ffprobe_path, "-v", "error", "-print_format", "json", "-show_format", "-show_streams", str(path)],
            media_path=path,
            timeout=config.PROBE_TIMEOUT,
        )
        try:
            info = json.loads(proc.stdout)
        except json.JSONDecodeError as err:
            raise MediaRuntimeError("MEDIA_INVALID", "ffprobe returned invalid media metadata.", path=path) from err
        if not any(s.get("codec_type") == "video" for s in info.get("streams", [])):
            raise MediaRuntimeError("MEDIA_INVALID", "Media has no valid video stream.", path=path)
        return info

    @staticmethod
    def _rate(value: str | None) -> float:
        if not value or value in {"0/0", "N/A"}:
            return 0.0
        try:
            left, right = value.split("/", 1)
            return float(left) / float(right)
        except (ValueError, ZeroDivisionError):
            try:
                return float(value)
            except ValueError:
                return 0.0

    def probe(self, path: str | Path) -> MediaProbe:
        source = Path(path)
        info = self._probe_json(source)
        streams = info.get("streams", [])
        video = next(s for s in streams if s.get("codec_type") == "video")
        audio = next((s for s in streams if s.get("codec_type") == "audio"), None)
        fmt = info.get("format", {})
        duration = float(fmt.get("duration") or video.get("duration") or 0.0)
        if duration < 0 or video.get("width", 0) <= 0 or video.get("height", 0) <= 0:
            raise MediaRuntimeError("MEDIA_INVALID", "Media metadata is incomplete or invalid.", path=source)
        return MediaProbe(
            path=str(source),
            duration_sec=duration,
            width=int(video.get("width", 0)),
            height=int(video.get("height", 0)),
            fps=self._rate(video.get("avg_frame_rate")) or self._rate(video.get("r_frame_rate")),
            video_codec=str(video.get("codec_name", "")),
            audio_codec=str(audio.get("codec_name")) if audio else None,
            has_audio=audio is not None,
            format_name=str(fmt.get("format_name", "")),
        )

    def validate(self, path: str | Path, *, require_audio: bool = False) -> dict:
        probe = self.probe(path)
        if require_audio and not probe.has_audio:
            raise MediaRuntimeError("MEDIA_INVALID", "Media has no audio stream.", path=Path(path))
        return {"valid": True, "has_audio": probe.has_audio, "probe": probe.to_dict()}

    def extract_audio(self, source: str | Path, destination: str | Path, *, sample_rate: int = 16_000) -> Path:
        src, dst = Path(source), Path(destination)
        probe = self.probe(src)
        if not probe.has_audio:
            raise MediaRuntimeError("MEDIA_INVALID", "Cannot extract audio: media has no audio stream.", path=src)
        dst.parent.mkdir(parents=True, exist_ok=True)
        self._run(
            [self.ffmpeg_path, "-y", "-i", str(src), "-vn", "-ac", "1", "-ar", str(sample_rate), "-c:a", "pcm_s16le", str(dst)],
            media_path=src,
            timeout=3600,
        )
        if not dst.is_file() or dst.stat().st_size <= 44:
            raise MediaRuntimeError("MEDIA_INVALID", "Audio extraction produced an empty artifact.", path=src)
        return dst

    def extract_frames(self, source: str | Path, destination_dir: str | Path, *, fps: float = 1.0, image_format: str = "jpg") -> list[Path]:
        src, out_dir = Path(source), Path(destination_dir)
        self.probe(src)
        if fps <= 0 or image_format not in {"jpg", "jpeg", "png"}:
            raise MediaRuntimeError("MEDIA_INVALID", "Invalid frame extraction parameters.", path=src)
        out_dir.mkdir(parents=True, exist_ok=True)
        pattern = out_dir / f"frame_%06d.{image_format}"
        self._run([self.ffmpeg_path, "-y", "-i", str(src), "-vf", f"fps={fps}", str(pattern)], media_path=src, timeout=3600)
        frames = sorted(out_dir.glob(f"frame_*.{image_format}"))
        if not frames:
            raise MediaRuntimeError("MEDIA_INVALID", "Frame extraction produced no frames.", path=src)
        return frames

    def transcode(self, source: str | Path, destination: str | Path, *, video_codec: str = "libx264", audio_codec: str = "aac", extra_args: Iterable[str] = ()) -> Path:
        return self._encode(source, destination, video_codec=video_codec, audio_codec=audio_codec, extra_args=extra_args)

    def render(self, source: str | Path, destination: str | Path, *, extra_args: Iterable[str] = ()) -> Path:
        return self._encode(source, destination, video_codec="libx264", audio_codec="aac", extra_args=extra_args)

    def _encode(self, source: str | Path, destination: str | Path, *, video_codec: str, audio_codec: str, extra_args: Iterable[str]) -> Path:
        src, dst = Path(source), Path(destination)
        self.probe(src)
        dst.parent.mkdir(parents=True, exist_ok=True)
        self._run([self.ffmpeg_path, "-y", "-i", str(src), "-c:v", video_codec, "-c:a", audio_codec, *list(extra_args), str(dst)], media_path=src, timeout=6 * 3600)
        if not dst.is_file() or dst.stat().st_size == 0:
            raise MediaRuntimeError("MEDIA_INVALID", "FFmpeg produced an empty output artifact.", path=dst)
        self.probe(dst)
        return dst

    def cleanup(self, paths: Iterable[str | Path]) -> int:
        removed = 0
        for raw in paths:
            path = Path(raw)
            try:
                if path.is_dir():
                    shutil.rmtree(path)
                    removed += 1
                elif path.exists():
                    path.unlink()
                    removed += 1
            except OSError as err:
                raise MediaRuntimeError("MEDIA_INVALID", f"Could not clean media artifact: {err}", path=path) from err
        return removed

    @property
    def hardware(self) -> HardwareInfo:
        return self._hardware


__all__ = ["MediaManager", "MediaProbe", "MediaRuntimeError"]
