"""Diarization stage: CAM++ embeddings over ASR speech windows → speaker
turns → word-level speaker labels merged back into the transcript."""

from __future__ import annotations

from pathlib import Path

import numpy as np

from ..jobs.queue import Stage, StageContext, StageError
from ..models import registry, specs


def _load_cached_embeddings(path: Path, expected_windows: int) -> np.ndarray | None:
    """Read the mid-stage cache, deleting malformed data so the stage can rebuild it."""
    try:
        cached = np.load(path, allow_pickle=False)
        if cached.ndim != 2 or len(cached) != expected_windows:
            raise ValueError("unexpected embedding cache shape")
        return cached
    except (OSError, ValueError, EOFError, TypeError):
        path.unlink(missing_ok=True)
        return None


class DiarizeStage(Stage):
    name = "diarize"
    schema_version = 2  # v2: refined affinity (v1 collapsed real two-host audio to one speaker)

    def run(self, ctx: StageContext) -> dict:
        prior = ctx.prior or {}
        ingest, asr = prior.get("ingest"), prior.get("asr")
        if not ingest or not asr:
            raise StageError("Diarization needs ingest + asr outputs.")
        audio_path = Path(ingest["audio_path"])
        if not audio_path.exists():
            raise StageError("Analysis audio missing — re-run ingest.")

        import torch

        from . import campplus, cluster

        ctx.emit(-1, "Loading speaker model…")
        ckpt = registry.ensure(specs.CAMPPLUS, lambda f, m: ctx.emit(f * 0.2, m))
        device = torch.device("cpu")
        model = campplus.load_model(str(ckpt), device)

        import librosa

        y16k, _ = librosa.load(str(audio_path), sr=16000, mono=True)
        duration = len(y16k) / 16000.0

        segments = asr["segments"]
        windows = campplus.speech_windows(segments, duration)
        if not windows:
            return {"speakers": 0, "turns": [], "segments": segments}

        # Mid-stage cache: embedding an hour of speech costs real minutes and
        # the stage checkpoint only lands at the end — a crash after embedding
        # shouldn't re-pay it.
        cache_path = ctx.job_dir / "diar_embeddings.npy"
        embeddings = None
        if cache_path.exists():
            embeddings = _load_cached_embeddings(cache_path, expected_windows=len(windows))
            if embeddings is not None:
                ctx.emit(0.8, "Embeddings cached")
        if embeddings is None:
            ctx.emit(0.25, f"Embedding {len(windows)} speech windows…")
            embeddings = campplus.embed_windows(
                model, y16k, windows, device,
                progress=lambda f: ctx.emit(0.25 + f * 0.55, "Embedding speech…"),
            )
            np.save(cache_path, embeddings)

        ctx.emit(0.85, "Clustering speakers…")
        labels = cluster.cluster_windows(embeddings)
        turns = cluster.build_turns(windows, labels)
        cluster.assign_words(segments, turns)

        speakers = int(len(np.unique(labels))) if len(labels) else 0
        return {
            "speakers": speakers,
            "turns": turns,
            "segments": segments,  # transcript enriched with word/segment speakers
        }
