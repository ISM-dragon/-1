from __future__ import annotations

import argparse
import json
import os
import re
import resource
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PYTHONPATH = str(ROOT / "pipeline")


def run_checked(args: list[str]) -> None:
    subprocess.run(args, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)


def make_fixture(path: Path, width: int, height: int, duration: int, source: str) -> None:
    if path.exists():
        return
    video = f"{source}=s={width}x{height}:r=25:d={duration}"
    run_checked([
        "ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", video,
        "-f", "lavfi", "-i", f"sine=frequency=440:sample_rate=48000:duration={duration}",
        "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-shortest", str(path),
    ])


def worker(case: str, media: Path, work: Path) -> dict:
    sys.path.insert(0, PYTHONPATH)
    from publikclip_pipeline.render import renderer
    from publikclip_pipeline.scoring import frames
    from publikclip_pipeline.camera import asd

    work.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    result: dict = {"case": case, "media": str(media), "media_bytes": media.stat().st_size}
    if case == "render":
        probe = subprocess.run(
            ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height",
             "-of", "json", str(media)], capture_output=True, text=True, check=True,
        )
        stream = json.loads(probe.stdout)["streams"][0]
        duration = 6.0
        fps = 25
        trajectory = {"fps": fps, "frames": [[0.0, 0.0, stream["width"], stream["height"]]] * int(duration * fps)}
        out = work / "render.mp4"
        renderer.render_clip(str(media), out, 0.0, duration, trajectory, None, None, timeout=600)
        result["output"] = renderer.verify_output(out, duration)
        result["output_bytes"] = out.stat().st_size
    elif case == "extract_frames":
        times = [0.3, 1.5, 3.0, 4.5, 5.5]
        images = frames.extract_frames(str(media), times, work / "frames")
        result["frame_count"] = len(images)
        result["frame_bytes"] = sum(len(item) for item in images)
    elif case == "checkpoint_io":
        from publikclip_pipeline.jobs import queue

        payload = {
            "stage": "score",
            "schema_version": 1,
            "data": {"clips": [{"start": i * 7.0, "end": i * 7.0 + 18.0, "summary": "representative clip"} for i in range(250)]},
        }
        out = work / "checkpoint.json"
        queue._atomic_write_json(out, payload)
        result["checkpoint_bytes"] = out.stat().st_size
    elif case == "camera_decode":
        duration = 6.0
        rgb_count = sum(1 for _ in asd._stream_frames(str(media), 0.0, duration, "fps=25,scale=320:240", "rgb24", 320 * 240 * 3))
        gray_count = sum(1 for _ in asd._stream_frames(str(media), 0.0, duration, "fps=25,scale=640:-2", "gray", 640 * 360))
        pcm = subprocess.run(
            ["ffmpeg", "-v", "error", "-ss", "0", "-t", str(duration), "-i", str(media), "-vn", "-ac", "1", "-ar", "16000", "-f", "s16le", "-"],
            capture_output=True, check=True,
        )
        result.update({"rgb_frames": rgb_count, "gray_frames": gray_count, "pcm_bytes": len(pcm.stdout)})
    else:
        raise ValueError(case)
    self_usage = resource.getrusage(resource.RUSAGE_SELF)
    child_usage = resource.getrusage(resource.RUSAGE_CHILDREN)
    result.update({
        "wall_sec": round(time.monotonic() - started, 4),
        "user_sec": round(self_usage.ru_utime + child_usage.ru_utime, 4),
        "sys_sec": round(self_usage.ru_stime + child_usage.ru_stime, 4),
        "max_rss_kb": max(int(self_usage.ru_maxrss), int(child_usage.ru_maxrss)),
        "fs_inputs": int(self_usage.ru_inblock + child_usage.ru_inblock),
        "fs_outputs": int(self_usage.ru_oublock + child_usage.ru_oublock),
        "voluntary_ctx": int(self_usage.ru_nvcsw + child_usage.ru_nvcsw),
        "involuntary_ctx": int(self_usage.ru_nivcsw + child_usage.ru_nivcsw),
    })
    print(json.dumps(result, sort_keys=True))
    return result


def parse_time_output(stderr: str) -> dict:
    fields: dict[str, object] = {}
    patterns = {
        "user_sec": r"User time \(seconds\): ([0-9.]+)",
        "sys_sec": r"System time \(seconds\): ([0-9.]+)",
        "max_rss_kb": r"Maximum resident set size \(kbytes\): (\d+)",
        "fs_inputs": r"File system inputs: (\d+)",
        "fs_outputs": r"File system outputs: (\d+)",
        "voluntary_ctx": r"Voluntary context switches: (\d+)",
        "involuntary_ctx": r"Involuntary context switches: (\d+)",
    }
    for key, pattern in patterns.items():
        match = re.search(pattern, stderr)
        if match:
            fields[key] = float(match.group(1)) if "sec" in key else int(match.group(1))
    elapsed = re.search(r"Elapsed \(wall clock\) time \(h:mm:ss or m:ss\): ([0-9:.]+)", stderr)
    if elapsed:
        parts = elapsed.group(1).split(":")
        if len(parts) == 3:
            fields["elapsed_sec"] = int(parts[0]) * 3600 + int(parts[1]) * 60 + float(parts[2])
        else:
            fields["elapsed_sec"] = int(parts[0]) * 60 + float(parts[1])
    return fields


def run_case(case: str, media: Path, root: Path) -> dict:
    work = root / f"{media.stem}_{case}"
    shutil.rmtree(work, ignore_errors=True)
    work.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    env["PYTHONPATH"] = PYTHONPATH
    proc = subprocess.run(
        [sys.executable, str(Path(__file__).resolve()), "--worker", case, str(media), str(work)],
        capture_output=True, text=True, env=env, check=True,
    )
    lines = [line for line in proc.stdout.splitlines() if line.strip()]
    result = json.loads(lines[-1])
    result["elapsed_sec"] = result["wall_sec"]
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--worker", choices=["render", "extract_frames", "camera_decode", "checkpoint_io"])
    parser.add_argument("media", nargs="?")
    parser.add_argument("work", nargs="?")
    parser.add_argument("--output", default="benchmarks/performance_baseline.json")
    parser.add_argument("--label", default="baseline")
    args = parser.parse_args()
    if args.worker:
        worker(args.worker, Path(args.media), Path(args.work))
        return

    root = ROOT / "benchmarks" / "media"
    root.mkdir(parents=True, exist_ok=True)
    fixtures = [
        ("low_motion_720p_15s.mp4", 1280, 720, 15, "testsrc"),
        ("high_motion_1080p_15s.mp4", 1920, 1080, 15, "testsrc2"),
        ("long_1080p_30s.mp4", 1920, 1080, 30, "smptebars"),
    ]
    cases = ["camera_decode", "extract_frames", "render", "checkpoint_io"]
    results = []
    for filename, width, height, duration, source in fixtures:
        path = root / filename
        make_fixture(path, width, height, duration, source)
        for case in cases:
            print(f"benchmark {case} {filename}", file=sys.stderr)
            results.append(run_case(case, path, ROOT / "benchmarks" / "work"))
    payload = {
        "label": args.label,
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "host": {"cpu_count": os.cpu_count(), "python": sys.version.split()[0]},
        "results": results,
    }
    output = ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, indent=2) + "\n")
    print(output)


if __name__ == "__main__":
    main()
