"""Public processing-engine API.

Import consumers from here so the application shell depends on a stable
contract, not on stage or persistence implementation modules.
"""

from .contracts import (
    ENGINE_CONTRACT_VERSION,
    STAGE_NAMES,
    ClipResult,
    EngineError,
    JobRef,
    JobResults,
    JobSpec,
    JobStatus,
    ProcessingEngine,
    ProgressCallback,
    ProgressEvent,
)
from .pipeline import PipelineEngine

__all__ = [
    "ENGINE_CONTRACT_VERSION",
    "STAGE_NAMES",
    "ClipResult",
    "EngineError",
    "JobRef",
    "JobResults",
    "JobSpec",
    "JobStatus",
    "PipelineEngine",
    "ProcessingEngine",
    "ProgressCallback",
    "ProgressEvent",
]
