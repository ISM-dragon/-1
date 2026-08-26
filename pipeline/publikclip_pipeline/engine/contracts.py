"""Stable public contracts for the processing engine.

Consumers should depend on this module rather than stage, queue, or renderer
implementation modules. Values returned by the engine are deliberately plain,
JSON-shaped records so they can be adapted to a CLI, Gateway, or application
shell without importing pipeline internals.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Callable, Mapping, Protocol, Sequence

ENGINE_CONTRACT_VERSION = 1
STAGE_NAMES: tuple[str, ...] = (
    "ingest",
    "asr",
    "diarization",
    "events",
    "candidates",
    "scoring",
    "camera",
    "render",
)

ProgressCallback = Callable[["ProgressEvent"], None]


@dataclass(frozen=True)
class JobSpec:
    """Immutable input accepted when a job is created."""

    source: str
    source_type: str = "file"
    settings: Mapping[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {
            "source": self.source,
            "source_type": self.source_type,
            "settings": dict(self.settings),
        }


@dataclass(frozen=True)
class JobRef:
    """Stable identity returned from ``create_job``."""

    id: str
    created_at: float
    source: str
    source_type: str
    contract_version: int = ENGINE_CONTRACT_VERSION

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "job_id": self.id,
            "created_at": self.created_at,
            "source": self.source,
            "source_type": self.source_type,
            "contract_version": self.contract_version,
        }


@dataclass(frozen=True)
class ProgressEvent:
    """A normalized progress event emitted by a running job."""

    job_id: str
    stage: str
    fraction: float
    message: str
    event: str = "progress"

    def to_dict(self) -> dict[str, Any]:
        return {
            "event": self.event,
            "job_id": self.job_id,
            "stage": self.stage,
            "fraction": self.fraction,
            "message": self.message,
        }


@dataclass(frozen=True)
class JobStatus:
    """Durable job projection safe for application shells and API adapters."""

    id: str
    status: str
    stage: str | None
    progress: float | None
    message: str | None
    error_code: str | None
    error: str | None
    recoverable: bool
    cancel_requested: bool
    stages: Mapping[str, str]
    contract_version: int = ENGINE_CONTRACT_VERSION

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "job_id": self.id,
            "status": self.status,
            "stage": self.stage,
            "progress": self.progress,
            "message": self.message,
            "error_code": self.error_code,
            "error": self.error,
            "recoverable": self.recoverable,
            "cancel_requested": self.cancel_requested,
            "stages": dict(self.stages),
            "contract_version": self.contract_version,
        }


@dataclass(frozen=True)
class JobResults:
    """Checkpoint-backed result projection for a completed or partial job."""

    job_id: str
    ingest: Mapping[str, Any] | None
    events: Mapping[str, Any] | None
    candidates: Mapping[str, Any] | None
    score: Mapping[str, Any] | None
    render: Mapping[str, Any] | None
    artifacts: Sequence[Mapping[str, Any]]
    contract_version: int = ENGINE_CONTRACT_VERSION

    def to_dict(self) -> dict[str, Any]:
        return {
            "job_id": self.job_id,
            "ingest": dict(self.ingest) if self.ingest else None,
            "events": dict(self.events) if self.events else None,
            "candidates": dict(self.candidates) if self.candidates else None,
            "score": dict(self.score) if self.score else None,
            "render": dict(self.render) if self.render else None,
            "artifacts": [dict(item) for item in self.artifacts],
            "contract_version": self.contract_version,
        }


@dataclass(frozen=True)
class ClipResult:
    """A single scored/rendered clip, addressable independently of the UI."""

    job_id: str
    index: int
    score: Mapping[str, Any] | None
    artifact: Mapping[str, Any] | None
    contract_version: int = ENGINE_CONTRACT_VERSION

    def to_dict(self) -> dict[str, Any]:
        return {
            "job_id": self.job_id,
            "index": self.index,
            "score": dict(self.score) if self.score else None,
            "artifact": dict(self.artifact) if self.artifact else None,
            "contract_version": self.contract_version,
        }


class EngineError(RuntimeError):
    """Stable, user-safe engine failure.

    ``code`` is for programmatic handling; ``message`` is safe to display after
    localization. ``recoverable`` tells a caller whether resume/retry can be
    offered. Raw exception objects and stack traces stay inside the engine.
    """

    def __init__(self, message: str, code: str = "ENGINE_FAILED", *, recoverable: bool = False) -> None:
        super().__init__(message)
        self.code = code
        self.safe_message = message
        self.recoverable = recoverable


class ProcessingEngine(Protocol):
    """Public engine interface implemented by the local Python engine."""

    def create_job(self, source: str, settings: Mapping[str, Any] | Any | None = None, *, source_type: str | None = None) -> JobRef: ...

    def get_job(self, job_id: str) -> JobRef: ...

    def get_job_settings(self, job_id: str) -> Mapping[str, Any]: ...

    def start_job(self, job_id: str, on_progress: ProgressCallback | None = None) -> JobResults: ...

    def get_job_status(self, job_id: str) -> JobStatus: ...

    def get_status(self, job_id: str) -> JobStatus: ...

    def status(self, job_id: str) -> JobStatus: ...

    def get_progress(self, job_id: str) -> Mapping[str, Any]: ...

    def progress(self, job_id: str) -> Mapping[str, Any]: ...

    def cancel_job(self, job_id: str) -> JobStatus: ...

    def resume_job(self, job_id: str, settings: Mapping[str, Any] | Any | None = None, on_progress: ProgressCallback | None = None) -> JobResults: ...

    def get_job_results(self, job_id: str) -> JobResults: ...

    def get_results(self, job_id: str) -> JobResults: ...

    def results(self, job_id: str) -> JobResults: ...

    def get_clip(self, job_id: str, clip_index: int) -> ClipResult: ...

    def render_clip(self, job_id: str, clip_index: int, on_progress: ProgressCallback | None = None) -> Mapping[str, Any]: ...

    def render(self, job_id: str, clip_index: int | None = None, on_progress: ProgressCallback | None = None) -> JobResults | Mapping[str, Any]: ...


__all__ = [
    "ENGINE_CONTRACT_VERSION",
    "STAGE_NAMES",
    "ClipResult",
    "EngineError",
    "JobRef",
    "JobResults",
    "JobSpec",
    "JobStatus",
    "ProcessingEngine",
    "ProgressCallback",
    "ProgressEvent",
]
