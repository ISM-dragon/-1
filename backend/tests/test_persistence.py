from __future__ import annotations

from pathlib import Path

import pytest

from backend.db import Store


def make_store(tmp_path: Path) -> Store:
    store = Store(tmp_path / "backend.sqlite3")
    store.init()
    return store


def add_job(store: Store, job_id: str, engine_job_id: str | None = None) -> None:
    now = store.now()
    store.create_job({
        "id": job_id,
        "source": "https://example.com/video.mp4",
        "options": {},
        "created_at": now,
    })
    if engine_job_id:
        store.transition(job_id, engine_job_id=engine_job_id)


def test_cancel_marks_resume_only_when_engine_checkpoint_exists(tmp_path: Path):
    store = make_store(tmp_path)
    add_job(store, "job-without-checkpoint")
    add_job(store, "job-with-checkpoint", "engine-1")

    cancelled_without = store.request_cancel("job-without-checkpoint")
    cancelled_with = store.request_cancel("job-with-checkpoint")

    assert cancelled_without["status"] == "cancelled"
    assert cancelled_without["resume_available"] == 0
    assert cancelled_with["resume_available"] == 1


def test_restart_only_exposes_resume_for_jobs_with_engine_identity(tmp_path: Path):
    store = make_store(tmp_path)
    add_job(store, "queued-without-checkpoint")
    add_job(store, "running-with-checkpoint", "engine-2")
    store.transition("running-with-checkpoint", status="running")

    store.mark_running_jobs_interrupted()

    assert store.get_job("queued-without-checkpoint")["resume_available"] == 0
    assert store.get_job("running-with-checkpoint")["status"] == "interrupted"
    assert store.get_job("running-with-checkpoint")["resume_available"] == 1


def test_transition_rejects_unknown_columns(tmp_path: Path):
    store = make_store(tmp_path)
    add_job(store, "job-1")

    with pytest.raises(ValueError, match="Unsupported job fields"):
        store.transition("job-1", secret_value="should-not-be-stored")
