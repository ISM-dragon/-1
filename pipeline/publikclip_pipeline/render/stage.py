"""Render final vertical MP4s, each verified before being reported."""

from __future__ import annotations

import json
from pathlib import Path

from ..jobs.queue import Stage, StageContext, StageError


def _read_json_object(path: Path, label: str) -> dict:
    try:
        value = json.loads(path.read_text())
    except (OSError, json.JSONDecodeError) as err:
        raise StageError(f"{label} is missing or invalid.", "ARTIFACT_INVALID") from err
    if not isinstance(value, dict):
        raise StageError(f"{label} is invalid.", "ARTIFACT_INVALID")
    return value


class RenderStage(Stage):
    name = "render"
    schema_version = 1

    def artifacts_ok(self, ctx: StageContext, data: dict) -> bool:
        if data.get("caption_preset") != ctx.settings.caption_preset:
            return False  # restyle requested → re-render
        outputs = data.get("outputs", [])
        if not isinstance(outputs, list) or not outputs:
            return False
        for clip in outputs:
            if not isinstance(clip, dict) or not isinstance(clip.get("path"), str) or not clip["path"]:
                return False
            path = Path(clip["path"])
            try:
                if not path.is_file() or path.stat().st_size == 0:
                    return False
            except OSError:
                return False
        return True

    def run(self, ctx: StageContext) -> dict:
        import numpy as np

        from ..captions import ass as ass_mod
        from . import ffmpeg_bin, renderer

        if not ffmpeg_bin.supports_captions():
            ctx.emit(-1, "No caption-capable ffmpeg found — fetching one…")
            if not ffmpeg_bin.ensure_capable(progress=lambda f, m: ctx.emit(f, m)):
                ctx.emit(-1, "Caption burning unavailable — rendering without captions.")

        prior = ctx.prior or {}
        ingest = prior.get("ingest")
        diarize = prior.get("diarize")
        events = prior.get("events")
        score = prior.get("score")
        camera = prior.get("camera")
        if not (ingest and diarize and events and score and camera):
            raise StageError("Render needs every prior stage output.")

        try:
            media = Path(ingest["media_path"])
            probe = ingest["probe"]
            src_w, src_h = int(probe["width"]), int(probe["height"])
            if not media.is_file() or media.stat().st_size == 0 or src_w <= 0 or src_h <= 0:
                raise ValueError("media or probe is invalid")
            segments = diarize["segments"]
            timeline = events["timeline"]
            curves = _read_json_object(Path(events["curves_path"]), "curves.json")
            rms = curves["rms"]
            grid = float(curves["grid_sec"])
            clips = score["clips"]
            trajectory_paths = camera["trajectories"]
            if not isinstance(trajectory_paths, dict):
                raise ValueError("camera trajectories are invalid")
        except (KeyError, TypeError, ValueError) as err:
            raise StageError(f"Render inputs are invalid: {err}", "INPUT_INVALID") from err

        captions_ok = ffmpeg_bin.supports_captions()
        emoji_ok = ass_mod.emoji_probe() if captions_ok else False
        ctx.emit(-1, f"Emoji support: {'yes' if emoji_ok else 'no (dropping emoji)'}")

        out_dir = ctx.job_dir / "clips"
        out_dir.mkdir(exist_ok=True)
        preset = ctx.settings.caption_preset
        outputs = []
        for i, clip in enumerate(clips):
            traj_path = trajectory_paths.get(str(i))
            if not traj_path or not Path(traj_path).is_file():
                raise StageError(f"Trajectory for clip {i} is missing — re-run camera.", "ARTIFACT_MISSING")
            trajectory = _read_json_object(Path(traj_path), f"trajectory_{i:02d}.json")
            if not isinstance(trajectory.get("frames"), list) or not trajectory["frames"]:
                raise StageError(f"Trajectory for clip {i} is invalid.", "ARTIFACT_INVALID")
            try:
                start, end = float(clip["start"]), float(clip["end"])
                if end <= start:
                    raise ValueError("clip end must be greater than start")
            except (KeyError, TypeError, ValueError) as err:
                raise StageError(f"Clip {i} metadata is invalid: {err}", "INPUT_INVALID") from err
            ctx.emit(i / max(1, len(clips)), f"Rendering clip {i + 1}/{len(clips)}…")

            words = []
            for seg in segments:
                for w in seg.get("words", []):
                    if start <= w["start"] < end:
                        words.append(
                            ass_mod.Word(
                                text=w["word"],
                                start=round(w["start"] - start, 3),
                                end=round(min(w["end"], end) - start, 3),
                            )
                        )
            ass_mod.mark_emphasis(words, rms, grid, clip_start=start)
            clip_events = [
                {
                    "type": e["type"],
                    "start": round(max(0.0, e["start"] - start), 3),
                    "end": round(min(e["end"], end) - start, 3),
                }
                for e in timeline
                if e["end"] > start and e["start"] < end and e["type"] != "pause"
            ]
            ass_path = out_dir / f"clip_{i:02d}.ass"
            ass_path.write_text(
                ass_mod.build_ass(words, clip_events, preset_name=preset, emoji_ok=emoji_ok)
            )

            out_path = out_dir / f"clip_{i:02d}.mp4"
            try:
                renderer.render_clip(
                    str(media), out_path, start, end, trajectory,
                    ass_path if captions_ok else None, ass_mod.FONTS_DIR,
                    lufs=ctx.settings.lufs_target,
                    true_peak=ctx.settings.true_peak_db,
                    src_w=src_w, src_h=src_h,
                )
            except RuntimeError as err:
                raise StageError(str(err), "RENDER_FAILED") from err
            check = renderer.verify_output(out_path, end - start)
            if not check["ok"]:
                detail = check.get("error", "output failed validation")
                raise StageError(f"Clip {i} failed verification: {detail}", "RENDER_OUTPUT_INVALID")
            outputs.append(
                {
                    "clip": i,
                    "path": str(out_path),
                    "ass": str(ass_path),
                    "score": clip["score"],
                    "best_platform": clip["best_platform"],
                    "duration": round(check["duration"], 2),
                    "words": len(words),
                    "event_tags": len(clip_events),
                }
            )

        if not outputs:
            raise StageError("No clips were rendered.")
        return {
            "outputs": outputs,
            "emoji_ok": emoji_ok,
            "captions_burned": captions_ok,
            "caption_preset": preset,
        }
