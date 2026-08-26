"""T2 frame sampler.

5–12 frames per clip: one at each scene change inside the window (visual
variety carries information), padded with uniform samples when the window
has fewer cuts than the floor. 512 px JPEG at quality 6 — low-detail on
purpose; T2 rates visual interest, it doesn't read license plates.

Frames are decoded in one ffmpeg process and streamed as JPEG bytes. The
previous implementation launched one process and one temporary JPEG per
sample, which repeated demux/decode setup and generated avoidable disk I/O.
"""

from __future__ import annotations

import subprocess
from pathlib import Path

from ..render import ffmpeg_bin

MIN_FRAMES = 5
MAX_FRAMES = 12
FRAME_WIDTH = 512
_FRAME_WINDOW_SEC = 0.025  # one frame for normalized 25/30 fps inputs


def sample_times(start: float, end: float, scene_times: list[float]) -> list[float]:
    inside = [t for t in scene_times if start + 0.5 <= t <= end - 0.5]
    times = [start + 0.3] + inside[: MAX_FRAMES - 2] + [max(start + 0.5, end - 0.5)]
    if len(times) < MIN_FRAMES:
        step = (end - start) / (MIN_FRAMES + 1)
        times = [start + step * (i + 1) for i in range(MIN_FRAMES)]
    times = sorted(set(round(t, 2) for t in times))
    return times[:MAX_FRAMES]


def _split_jpegs(payload: bytes) -> list[bytes]:
    """Split an image2pipe MJPEG stream without writing intermediate files."""
    frames: list[bytes] = []
    cursor = 0
    while True:
        start = payload.find(b"\xff\xd8", cursor)
        if start < 0:
            break
        end = payload.find(b"\xff\xd9", start + 2)
        if end < 0:
            break
        frames.append(payload[start : end + 2])
        cursor = end + 2
    return frames


def extract_frames(media_path: str, times: list[float], tmp_dir: Path) -> list[bytes]:
    """Extract all requested frames in one decode and return JPEG bytes.

    ``times`` are absolute media timestamps. The input seek starts at the
    first requested timestamp, so long inputs do not pay for decoding from
    t=0. The filter selects the first frame in a narrow window for each target,
    matching the old input-seek behaviour on the normalized CFR media used by
    ingest. ``tmp_dir`` remains in the signature for API compatibility; it is
    intentionally unused because no temporary frame files are needed.
    """
    if not times:
        return []
    ordered = sorted(float(t) for t in times)
    seek = max(0.0, ordered[0])
    expressions = []
    for target in ordered:
        relative = max(0.0, target - seek)
        expressions.append(
            f"between(t,{relative:.4f},{relative + _FRAME_WINDOW_SEC:.4f})"
        )
    select = "+".join(expressions)
    duration = max(0.1, ordered[-1] - seek + _FRAME_WINDOW_SEC + 0.1)
    try:
        proc = subprocess.run(
            [
                ffmpeg_bin.ffmpeg(), "-v", "error", "-ss", f"{seek:.3f}",
                "-t", f"{duration:.3f}", "-i", media_path,
                "-vf", f"select='{select}',scale={FRAME_WIDTH}:-2",
                "-fps_mode", "vfr", "-frames:v", str(len(ordered)),
                "-q:v", "6", "-f", "image2pipe", "-c:v", "mjpeg", "pipe:1",
            ],
            capture_output=True,
            timeout=120,
        )
    except (subprocess.TimeoutExpired, OSError):
        return []
    if proc.returncode != 0:
        return []
    return _split_jpegs(proc.stdout)
