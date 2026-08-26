from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def old_extract(media: Path, times: list[float]) -> list[bytes]:
    frames = []
    for i, t in enumerate(times):
        out = Path("/tmp") / f"old_frame_{i:02d}.jpg"
        proc = subprocess.run(
            ["ffmpeg", "-y", "-ss", f"{t:.2f}", "-i", str(media), "-frames:v", "1",
             "-vf", "scale=512:-2", "-q:v", "6", str(out)],
            capture_output=True, check=False,
        )
        if proc.returncode == 0 and out.exists():
            frames.append(out.read_bytes())
            out.unlink(missing_ok=True)
    return frames


def main() -> None:
    sys.path.insert(0, str(ROOT / "pipeline"))
    from publikclip_pipeline.scoring import frames as new_frames

    media_dir = ROOT / "benchmarks" / "media"
    times = [0.3, 1.5, 3.0, 4.5, 5.5]
    for media in sorted(media_dir.glob("*.mp4")):
        old = old_extract(media, times)
        new = new_frames.extract_frames(str(media), times, Path("/tmp/unused-frame-dir"))
        equal = old == new
        print(f"{media.name}: old={len(old)} new={len(new)} byte_equal={equal}")
        if not equal:
            raise SystemExit(1)


if __name__ == "__main__":
    main()
