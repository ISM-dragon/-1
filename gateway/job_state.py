"""Canonical durable processing-job state helpers for the ISM Gateway."""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

PROCESSING_STATES = (
    "QUEUED",
    "PREPARING",
    "DOWNLOADING",
    "INGESTING",
    "TRANSCRIBING",
    "DIARIZING",
    "ANALYZING",
    "CANDIDATES_READY",
    "SCORING",
    "EDITING",
    "RENDERING",
    "FINALIZING",
    "COMPLETED",
    "FAILED",
    "CANCELLED",
    "RETRY_WAIT",
    "INTERRUPTED",
)

TERMINAL_STATES = {"COMPLETED", "FAILED", "CANCELLED"}
RECOVERABLE_DEFAULT = {"FAILED", "RETRY_WAIT", "INTERRUPTED"}
LEGACY_TO_STATE = {
    "queued": "QUEUED",
    "running": "PREPARING",
    "done": "COMPLETED",
    "completed": "COMPLETED",
    "failed": "FAILED",
    "cancelled": "CANCELLED",
}


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def canonical_state(row: Any) -> str:
    value = row["state"] if "state" in row.keys() and row["state"] else None
    if value in PROCESSING_STATES:
        return value
    return LEGACY_TO_STATE.get(str(row["status"]).lower(), "QUEUED")


def legacy_status(state: str) -> str:
    if state == "COMPLETED":
        return "done"
    if state == "CANCELLED":
        return "cancelled"
    if state == "FAILED":
        return "failed"
    if state in TERMINAL_STATES:
        return "done"
    return "running" if state not in {"QUEUED", "RETRY_WAIT", "INTERRUPTED"} else "queued"


def transition_payload(row: Any) -> dict[str, Any]:
    state = canonical_state(row)
    return {
        "state": state,
        "status": str(row["status"]),
        "stage": row["stage"],
        "progress": row["fraction"],
        "retry_count": int(row["retry_count"] or 0) if "retry_count" in row.keys() else 0,
        "error_code": row["error_code"] if "error_code" in row.keys() else None,
        "recoverable": bool(row["recoverable"]) if "recoverable" in row.keys() else state in RECOVERABLE_DEFAULT,
        "cancel_requested": bool(row["cancel_requested"]) if "cancel_requested" in row.keys() else False,
    }


def validate_transition(current: str, target: str) -> None:
    if target not in PROCESSING_STATES:
        raise ValueError(f"Unknown processing state: {target}")
    if current in TERMINAL_STATES and target not in {"RETRY_WAIT", "QUEUED"}:
        raise ValueError(f"Terminal job cannot transition from {current} to {target}")
