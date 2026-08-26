"""Durable storage for the personal backend.

The backend is intentionally single-tenant: there is one device binding and no
user/account tables. Each operation opens its own SQLite connection so worker
threads and HTTP requests do not share connection state.
"""
from __future__ import annotations

import json
import sqlite3
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


class Store:
    def __init__(self, path: Path):
        self.path = path
        self.path.parent.mkdir(parents=True, exist_ok=True)

    def connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.path, timeout=30, check_same_thread=False)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA foreign_keys = ON")
        conn.execute("PRAGMA busy_timeout = 30000")
        return conn

    @staticmethod
    def now() -> str:
        return datetime.now(timezone.utc).isoformat()

    def init(self) -> None:
        with closing(self.connect()) as conn:
            conn.executescript(
                """
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS device_binding (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    device_hash TEXT NOT NULL,
                    first_seen_at TEXT NOT NULL,
                    last_seen_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS uploads (
                    id TEXT PRIMARY KEY,
                    filename TEXT NOT NULL,
                    content_type TEXT NOT NULL,
                    bytes INTEGER NOT NULL,
                    path TEXT NOT NULL,
                    created_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS jobs (
                    id TEXT PRIMARY KEY,
                    idempotency_key TEXT UNIQUE,
                    source TEXT NOT NULL,
                    source_upload_id TEXT,
                    options_json TEXT NOT NULL,
                    status TEXT NOT NULL,
                    state TEXT NOT NULL,
                    stage TEXT,
                    progress REAL NOT NULL DEFAULT 0,
                    message TEXT,
                    error_code TEXT,
                    error_message TEXT,
                    engine_job_id TEXT,
                    results_json TEXT,
                    cancel_requested INTEGER NOT NULL DEFAULT 0,
                    resume_available INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    FOREIGN KEY(source_upload_id) REFERENCES uploads(id)
                );
                CREATE INDEX IF NOT EXISTS idx_jobs_created ON jobs(created_at DESC);
                CREATE INDEX IF NOT EXISTS idx_jobs_status ON jobs(status);
                """
            )
            conn.commit()

    def bind_device(self, device_hash: str) -> bool:
        """Bind the backend to the first device and reject all other devices."""
        now = self.now()
        with closing(self.connect()) as conn:
            row = conn.execute("SELECT device_hash FROM device_binding WHERE id=1").fetchone()
            if row is None:
                conn.execute(
                    "INSERT INTO device_binding(id, device_hash, first_seen_at, last_seen_at) VALUES (1, ?, ?, ?)",
                    (device_hash, now, now),
                )
                conn.commit()
                return True
            if row["device_hash"] != device_hash:
                return False
            conn.execute("UPDATE device_binding SET last_seen_at=? WHERE id=1", (now,))
            conn.commit()
            return True

    def create_upload(self, upload: dict[str, Any]) -> None:
        with closing(self.connect()) as conn:
            conn.execute(
                "INSERT INTO uploads(id, filename, content_type, bytes, path, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                (upload["id"], upload["filename"], upload["content_type"], upload["bytes"], upload["path"], upload["created_at"]),
            )
            conn.commit()

    def get_upload(self, upload_id: str) -> sqlite3.Row | None:
        with closing(self.connect()) as conn:
            return conn.execute("SELECT * FROM uploads WHERE id=?", (upload_id,)).fetchone()

    def create_job(self, job: dict[str, Any]) -> None:
        with closing(self.connect()) as conn:
            conn.execute(
                """INSERT INTO jobs(
                    id, idempotency_key, source, source_upload_id, options_json,
                    status, state, stage, progress, message, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 'queued', 'QUEUED', 'queued', 0, ?, ?, ?)""",
                (
                    job["id"], job.get("idempotency_key"), job["source"], job.get("source_upload_id"),
                    json.dumps(job["options"], ensure_ascii=False), job.get("message", "Job accepted"),
                    job["created_at"], job["created_at"],
                ),
            )
            conn.commit()

    def get_job(self, job_id: str) -> sqlite3.Row | None:
        with closing(self.connect()) as conn:
            return conn.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()

    def find_by_idempotency(self, key: str) -> sqlite3.Row | None:
        with closing(self.connect()) as conn:
            return conn.execute("SELECT * FROM jobs WHERE idempotency_key=?", (key,)).fetchone()

    def list_jobs(self, limit: int, status: str | None = None, before: str | None = None) -> list[sqlite3.Row]:
        clauses = []
        params: list[Any] = []
        if status:
            clauses.append("status=?")
            params.append(status)
        if before:
            clauses.append("created_at < ?")
            params.append(before)
        where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        params.append(limit)
        with closing(self.connect()) as conn:
            return conn.execute(f"SELECT * FROM jobs {where} ORDER BY created_at DESC LIMIT ?", params).fetchall()

    def mark_running_jobs_interrupted(self) -> None:
        now = self.now()
        with closing(self.connect()) as conn:
            conn.execute(
                """UPDATE jobs SET status='interrupted', state='INTERRUPTED', stage='interrupted',
                   message='Backend restarted; resume is available',
                   resume_available=CASE WHEN engine_job_id IS NOT NULL THEN 1 ELSE 0 END,
                   updated_at=?
                   WHERE status IN ('queued', 'running') AND cancel_requested=0""",
                (now,),
            )
            conn.commit()

    def transition(self, job_id: str, **values: Any) -> None:
        if not values:
            return
        allowed = {
            "status", "state", "stage", "progress", "message", "error_code",
            "error_message", "engine_job_id", "results_json", "cancel_requested",
            "resume_available",
        }
        unknown = set(values) - allowed
        if unknown:
            raise ValueError(f"Unsupported job fields: {', '.join(sorted(unknown))}")
        values["updated_at"] = self.now()
        assignments = ", ".join(f"{key}=?" for key in values)
        with closing(self.connect()) as conn:
            conn.execute(f"UPDATE jobs SET {assignments} WHERE id=?", (*values.values(), job_id))
            conn.commit()

    def request_cancel(self, job_id: str) -> sqlite3.Row | None:
        now = self.now()
        with closing(self.connect()) as conn:
            row = conn.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
            if row is None:
                return None
            if row["status"] in {"completed", "failed", "cancelled"}:
                return row
            conn.execute(
                """UPDATE jobs SET cancel_requested=1, status='cancelled', state='CANCELLED',
                   stage='cancelled', message=?, error_code='JOB_CANCELLED',
                   error_message=?, resume_available=CASE WHEN engine_job_id IS NOT NULL THEN 1 ELSE resume_available END,
                   updated_at=? WHERE id=?""",
                ("Cancellation requested", "Job cancellation was requested by the device", now, job_id),
            )
            conn.commit()
            return conn.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()

    def prepare_resume(self, job_id: str) -> sqlite3.Row | None:
        now = self.now()
        with closing(self.connect()) as conn:
            row = conn.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
            if row is None:
                return None
            conn.execute(
                """UPDATE jobs SET status='queued', state='QUEUED', stage='queued', progress=0,
                   message='Resume queued', error_code=NULL, error_message=NULL,
                   cancel_requested=0, resume_available=1, updated_at=? WHERE id=?""",
                (now, job_id),
            )
            conn.commit()
            return conn.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()

    @staticmethod
    def job_dict(row: sqlite3.Row) -> dict[str, Any]:
        result = dict(row)
        result["options"] = json.loads(result.pop("options_json"))
        raw_results = result.pop("results_json")
        result["results"] = json.loads(raw_results) if raw_results else None
        result["cancel_requested"] = bool(result["cancel_requested"])
        result["resume_available"] = bool(result["resume_available"])
        result["source_upload_id"] = result.get("source_upload_id")
        return result

    @staticmethod
    def upload_dict(row: sqlite3.Row) -> dict[str, Any]:
        return dict(row)

    def count(self) -> int:
        with closing(self.connect()) as conn:
            return int(conn.execute("SELECT COUNT(*) FROM jobs").fetchone()[0])
