from __future__ import annotations

import sqlite3

import pytest
from fastapi.testclient import TestClient

import gateway.main as gateway


@pytest.fixture
def client(tmp_path, monkeypatch):
    monkeypatch.setattr(gateway, "DB_PATH", tmp_path / "gateway.db")
    monkeypatch.setattr(gateway, "SOURCE_ROOT", tmp_path / "sources")
    monkeypatch.setattr(gateway, "PROCESSING_ROOT", tmp_path / "processing")
    gateway.init_db()
    with TestClient(gateway.app) as test_client:
        yield test_client


def test_versioned_health_and_project_lifecycle(client):
    health = client.get("/api/v1/health")
    assert health.status_code == 200
    assert health.json()["ok"] is True

    created = client.post(
        "/api/v1/projects",
        json={"name": "Demo episode", "source": "https://www.youtube.com/watch?v=demo"},
    )
    assert created.status_code == 200
    project = created.json()
    assert project["status"] == "created"
    assert project["source"].startswith("https://")

    updated = client.patch(
        f"/api/v1/projects/{project['id']}",
        json={"name": "Renamed episode"},
    )
    assert updated.status_code == 200
    assert updated.json()["name"] == "Renamed episode"

    fetched = client.get(f"/api/v1/projects/{project['id']}")
    assert fetched.status_code == 200
    assert fetched.json()["job"] is None


def test_queued_job_can_be_cancelled_without_claiming_success(client):
    with sqlite3.connect(gateway.DB_PATH) as connection:
        connection.execute(
            "INSERT INTO processing_jobs (id, source, status, created_at, updated_at) VALUES (?, ?, 'queued', ?, ?)",
            ("proc_test_cancel", "https://www.youtube.com/watch?v=demo", gateway.now_iso(), gateway.now_iso()),
        )

    cancelled = client.post("/api/v1/jobs/proc_test_cancel/cancel")
    assert cancelled.status_code == 200
    assert cancelled.json() == {"id": "proc_test_cancel", "status": "cancelled"}

    status = client.get("/api/v1/jobs/proc_test_cancel")
    assert status.status_code == 200
    assert status.json()["status"] == "cancelled"
