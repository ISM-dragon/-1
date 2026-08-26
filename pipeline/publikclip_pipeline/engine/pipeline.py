"""Reference local implementation of the public processing-engine contract.

This module is an orchestration adapter. The existing stage algorithms remain
in their original packages; this class owns only composition, lifecycle,
checkpoint access, and conversion to stable public records.
"""

from __future__ import annotations

import json
from typing import Any, Callable, Iterable, Mapping

from .. import config
from ..edits import render_clip as render_clip_module
from ..jobs import queue
from .contracts import (
    ClipResult,
    EngineError,
    JobRef,
    JobResults,
    JobStatus,
    ProcessingEngine,
    ProgressCallback,
    ProgressEvent,
)

_INTERNAL_TO_PUBLIC_STAGE = {"diarize": "diarization", "score": "scoring"}


def _public_stage(name: str) -> str:
    return _INTERNAL_TO_PUBLIC_STAGE.get(name, name)


def _settings_json(settings: Mapping[str, Any] | Any | None) -> str:
    if settings is None:
        payload = config.Settings().to_json()
    elif hasattr(settings, "to_json"):
        payload = settings.to_json()
    elif isinstance(settings, Mapping):
        payload = dict(settings)
    else:
        raise EngineError("Settings must be a mapping or Settings object.", "INVALID_JOB_SETTINGS")
    try:
        return json.dumps(payload, ensure_ascii=False)
    except (TypeError, ValueError) as error:
        raise EngineError("Settings are not JSON serializable.", "INVALID_JOB_SETTINGS") from error


def _read_envelope(job: queue.Job, stage: str) -> dict[str, Any] | None:
    path = queue.checkpoint_path(job, stage)
    try:
        payload = json.loads(path.read_text())
    except (OSError, json.JSONDecodeError):
        return None
    data = payload.get("data")
    return data if isinstance(data, dict) else None


class PipelineEngine(ProcessingEngine):
    """Checkpoint-backed local engine for the existing Python pipeline."""

    def __init__(self, stages_factory: Callable[[], Iterable[queue.Stage]] | None = None) -> None:
        self._stages_factory = stages_factory or self._default_stages

    @staticmethod
    def _default_stages() -> list[queue.Stage]:
        # Imports stay lazy so callers can inspect/create jobs without paying
        # the ASR/vision model import cost.
        from ..asr.stage import AsrStage
        from ..camera.stage import CameraStage
        from ..candidates.stage import CandidatesStage
        from ..diarize.stage import DiarizeStage
        from ..events.stage import EventsStage
        from ..ingest.stage import IngestStage
        from ..render.stage import RenderStage
        from ..scoring.stage import ScoreStage

        return [
            IngestStage(),
            AsrStage(),
            DiarizeStage(),
            EventsStage(),
            CandidatesStage(),
            ScoreStage(),
            CameraStage(),
            RenderStage(),
        ]

    def _job(self, job_id: str) -> queue.Job:
        job = queue.get_job(job_id)
        if job is None:
            raise EngineError(f"No processing job {job_id}.", "JOB_NOT_FOUND")
        return job

    def get_job(self, job_id: str) -> JobRef:
        job = self._job(job_id)
        return JobRef(job.id, job.created_at, job.source, job.source_type)

    def get_job_settings(self, job_id: str) -> Mapping[str, Any]:
        job = self._job(job_id)
        try:
            payload = json.loads(job.settings_json)
        except (TypeError, json.JSONDecodeError) as error:
            raise EngineError("Job settings are corrupted.", "INVALID_JOB_SETTINGS") from error
        return payload if isinstance(payload, dict) else {}

    def create_job(
        self,
        source: str,
        settings: Mapping[str, Any] | Any | None = None,
        *,
        source_type: str | None = None,
    ) -> JobRef:
        if not isinstance(source, str) or not source.strip():
            raise EngineError("A non-empty source is required.", "INVALID_SOURCE")
        resolved_type = source_type or ("url" if source.startswith(("http://", "https://")) else "file")
        try:
            job = queue.create_job(resolved_type, source, _settings_json(settings))
        except ValueError as error:
            raise EngineError(str(error), "INVALID_SOURCE") from error
        return JobRef(job.id, job.created_at, job.source, job.source_type)

    def start_job(self, job_id: str, on_progress: ProgressCallback | None = None) -> JobResults:
        job = self._job(job_id)
        if job.status == "done":
            return self.get_job_results(job_id)
        if job.status == "cancelled" or queue.is_cancel_requested(job_id):
            raise EngineError("Job cancellation was requested.", "JOB_CANCELLED", recoverable=False)
        if job.status == "running":
            raise EngineError("Job is already running.", "JOB_BUSY", recoverable=True)

        def emit(stage: str, fraction: float, message: str) -> None:
            if on_progress is not None:
                normalized = -1.0 if fraction < 0 else max(0.0, min(1.0, float(fraction)))
                on_progress(ProgressEvent(job_id, _public_stage(stage), normalized, message))

        try:
            queue.run_stages(job, self._stages_factory(), emit)
        except queue.StageError as error:
            if error.code == "JOB_CANCELLED":
                raise EngineError(error.safe_message, error.code, recoverable=False) from error
            raise EngineError(error.safe_message, error.code, recoverable=True) from error
        except Exception as error:  # noqa: BLE001 - convert implementation errors at boundary
            raise EngineError("Pipeline execution failed.", "ENGINE_FAILED", recoverable=True) from error
        return self.get_job_results(job_id)

    def get_job_status(self, job_id: str) -> JobStatus:
        job = self._job(job_id)
        current = queue.get_progress(job_id)
        progress = self.progress(job_id)
        return JobStatus(
            id=job.id,
            status=job.status,
            stage=_public_stage(current["stage"]) if current and current["stage"] else None,
            progress=float(progress["fraction"]),
            message=current["message"] if current else job.message,
            error_code=job.error_code,
            error=job.error,
            recoverable=job.status == "failed",
            cancel_requested=job.cancel_requested,
            stages={_public_stage(name): status for name, status in queue.stage_statuses(job_id).items()},
        )

    def status(self, job_id: str) -> JobStatus:
        """Direct-name alias for callers that use the lifecycle contract."""
        return self.get_job_status(job_id)

    def progress(self, job_id: str) -> Mapping[str, Any]:
        """Return the latest durable event and normalized overall progress."""
        self._job(job_id)
        current = queue.get_progress(job_id)
        stages = queue.stage_statuses(job_id)
        public_stages = {_public_stage(name): status for name, status in stages.items()}
        expected = {"ingest", "asr", "diarization", "events", "candidates", "scoring", "camera", "render"}
        total = len(expected) if not (set(public_stages) - expected) else max(1, len(public_stages))
        done = sum(status == "done" for status in public_stages.values())
        fraction = 1.0 if self._job(job_id).status == "done" else (done / total if total else 0.0)
        if current and current["stage"]:
            current_stage = _public_stage(current["stage"])
            stage_fraction = float(current["fraction"])
            if stage_fraction >= 0:
                completed_before = done - (1 if public_stages.get(current_stage) == "done" else 0)
                fraction = (completed_before + min(1.0, stage_fraction)) / total
        return {
            "job_id": job_id,
            "stage": _public_stage(current["stage"]) if current and current["stage"] else None,
            "fraction": max(0.0, min(1.0, round(fraction, 6))),
            "progress": max(0.0, min(1.0, round(fraction, 6))),
            "message": current["message"] if current else None,
            "updated_at": current["updated_at"] if current else None,
            "stages": public_stages,
        }

    def cancel_job(self, job_id: str) -> JobStatus:
        job = self._job(job_id)
        if job.status == "done":
            raise EngineError("Completed jobs cannot be cancelled.", "JOB_NOT_CANCELLABLE")
        queue.request_cancel(job_id)
        return self.get_job_status(job_id)

    def resume_job(
        self,
        job_id: str,
        settings: Mapping[str, Any] | Any | None = None,
        on_progress: ProgressCallback | None = None,
    ) -> JobResults:
        job = self._job(job_id)
        if job.status == "running":
            raise EngineError("Job is already running.", "JOB_BUSY", recoverable=True)
        if settings is not None:
            queue.update_job_settings(job_id, _settings_json(settings))
        queue.clear_cancel_request(job_id)
        return self.start_job(job_id, on_progress)

    def get_job_results(self, job_id: str) -> JobResults:
        job = self._job(job_id)
        render = _read_envelope(job, "render") or {}
        outputs = render.get("outputs") or []
        artifacts = [item for item in outputs if isinstance(item, dict)]
        return JobResults(
            job_id=job.id,
            ingest=_read_envelope(job, "ingest"),
            events=_read_envelope(job, "events"),
            candidates=_read_envelope(job, "candidates"),
            score=_read_envelope(job, "score"),
            render={**render, "outputs": artifacts} if render else None,
            artifacts=artifacts,
        )

    def results(self, job_id: str) -> JobResults:
        """Direct-name alias for checkpoint-backed result reads."""
        return self.get_job_results(job_id)

    def get_clip(self, job_id: str, clip_index: int) -> ClipResult:
        if clip_index < 0:
            raise EngineError("Clip index must be non-negative.", "INVALID_CLIP_INDEX")
        results = self.get_job_results(job_id)
        clips = list((results.score or {}).get("clips") or [])
        if clip_index >= len(clips):
            raise EngineError("Clip index is out of range.", "CLIP_NOT_FOUND")
        artifact = next(
            (item for item in results.artifacts if int(item.get("clip", -1)) == clip_index),
            None,
        )
        return ClipResult(job_id, clip_index, clips[clip_index], artifact)

    def render_clip(
        self,
        job_id: str,
        clip_index: int,
        on_progress: ProgressCallback | None = None,
    ) -> Mapping[str, Any]:
        job = self._job(job_id)
        self.get_clip(job_id, clip_index)

        def emit(fraction: float, message: str) -> None:
            normalized = -1.0 if fraction < 0 else max(0.0, min(1.0, float(fraction)))
            queue.record_progress(job_id, "render", normalized, message)
            if on_progress is not None:
                on_progress(ProgressEvent(job_id, "render", normalized, message))

        try:
            return render_clip_module.render_clip_edit(job.dir, clip_index, emit)
        except IndexError as error:
            raise EngineError("Clip index is out of range.", "CLIP_NOT_FOUND") from error
        except Exception as error:  # noqa: BLE001 - stable public boundary
            raise EngineError("Clip render failed.", "CLIP_RENDER_FAILED", recoverable=True) from error

    def render(
        self,
        job_id: str,
        clip_index: int | None = None,
        on_progress: ProgressCallback | None = None,
    ) -> JobResults | Mapping[str, Any]:
        """Render the full pipeline or one edited clip through the existing paths."""
        if clip_index is None:
            return self.start_job(job_id, on_progress=on_progress)
        return self.render_clip(job_id, clip_index, on_progress=on_progress)


__all__ = ["PipelineEngine"]
