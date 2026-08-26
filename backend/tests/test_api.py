from __future__ import annotations

import time
from pathlib import Path

from fastapi.testclient import TestClient

from backend.app import Settings, create_app
from backend.engine import EngineError, EngineEvent


class FakeEngine:
    def __init__(self, slow: bool = False):
        self.slow = slow
        self.calls: list[tuple[str, str | None]] = []

    def available(self):
        return True, "fake engine"

    def run(self, source, job_dir, options, resume_engine_job_id, on_event, cancel_event):
        self.calls.append((source, resume_engine_job_id))
        engine_job_id = resume_engine_job_id or "engine-test-job"
        on_event(EngineEvent("job", engine_job_id=engine_job_id))
        on_event(EngineEvent("progress", stage="ingest", progress=0.4, message="ingesting"))
        if self.slow:
            for _ in range(100):
                if cancel_event.is_set():
                    raise RuntimeError("cancelled")
                time.sleep(0.01)
        engine_dir = job_dir.parent / engine_job_id / "clips"
        engine_dir.mkdir(parents=True, exist_ok=True)
        (engine_dir / "clip0.mp4").write_bytes(b"valid fake mp4 artifact")
        return {"ok": True, "job_id": engine_job_id, "clips": [{"clip": 0, "filename": "clip0.mp4", "title": "Test clip"}]}

    def render_clip(self, engine_job_id, clip, job_dir, on_event, cancel_event):
        clips = job_dir / "clips"
        clips.mkdir(parents=True, exist_ok=True)
        filename = f"clip{clip}-rendered.mp4"
        (clips / filename).write_bytes(b"valid rendered fake mp4 artifact")
        return {"ok": True, "output": {"clip": clip, "filename": filename}}


class NoCheckpointEngine(FakeEngine):
    def run(self, source, job_dir, options, resume_engine_job_id, on_event, cancel_event):
        self.calls.append((source, resume_engine_job_id))
        while not cancel_event.is_set():
            time.sleep(0.01)
        raise EngineError("cancelled before checkpoint", "JOB_CANCELLED", False)


def make_client(tmp_path: Path, engine=None, token: str = ""):
    settings = Settings()
    settings.db_path = tmp_path / "backend.sqlite3"
    settings.storage_root = tmp_path / "files"
    settings.max_upload_bytes = 1024 * 1024
    settings.token = token
    settings.allow_insecure_local = not bool(token)
    return TestClient(create_app(settings, engine or FakeEngine()))


def wait_for(client: TestClient, job_id: str, expected: str = "completed"):
    for _ in range(100):
        response = client.get(f"/jobs/{job_id}")
        assert response.status_code == 200
        body = response.json()
        if body["status"] == expected:
            return body
        time.sleep(0.01)
    raise AssertionError(f"job did not reach {expected}: {body}")


def test_private_job_lifecycle_upload_results_and_download(tmp_path):
    with make_client(tmp_path) as client:
        upload = client.post("/uploads", content=b"video bytes", headers={"X-Filename": "source.mp4", "Content-Type": "video/mp4"})
        assert upload.status_code == 201
        upload_body = upload.json()
        assert upload_body["path"] is None
        upload_id = upload_body["id"]

        created = client.post("/jobs", json={"upload_id": upload_id, "options": {"mode": "fast"}, "idempotency_key": "device-key-001"})
        assert created.status_code == 202
        job_id = created.json()["id"]
        assert created.json()["state"] in {"QUEUED", "PREPARING", "RUNNING", "COMPLETED"}

        duplicate = client.post("/jobs", json={"upload_id": upload_id, "idempotency_key": "device-key-001"})
        assert duplicate.status_code == 200
        assert duplicate.json()["reused"] is True
        assert duplicate.json()["id"] == job_id

        completed = wait_for(client, job_id)
        assert completed["progress"] == 1
        assert completed["results"]["job_id"] == job_id

        results = client.get(f"/jobs/{job_id}/results")
        assert results.status_code == 200
        assert results.json()["results"]["clips"]

        clips = client.get(f"/jobs/{job_id}/clips")
        assert clips.status_code == 200
        assert clips.json()["items"][0]["download_url"] == f"/jobs/{job_id}/clips/0/download"

        download = client.get(f"/jobs/{job_id}/clips/0/download")
        assert download.status_code == 200
        assert download.content.startswith(b"valid fake")

        rendered = client.post(f"/jobs/{job_id}/clips/0/render", json={"options": {"preset": "vertical"}})
        assert rendered.status_code == 200
        assert rendered.json()["render"]["filename"] == "clip0-rendered.mp4"
        rendered_download = client.get(f"/jobs/{job_id}/clips/0/download")
        assert rendered_download.status_code == 200
        assert rendered_download.content.startswith(b"valid rendered")


def test_upload_validation_and_filename_containment(tmp_path):
    with make_client(tmp_path) as client:
        empty = client.post("/uploads", content=b"", headers={"X-Filename": "../../outside.mp4"})
        assert empty.status_code == 400
        assert empty.json()["error"]["code"] == "EMPTY_UPLOAD"

        upload = client.post("/uploads", content=b"safe", headers={"X-Filename": "../../outside.mp4"})
        assert upload.status_code == 201
        assert upload.json()["filename"] == "outside.mp4"
        assert list((tmp_path / "files" / "uploads").rglob("source.mp4"))
        assert not list((tmp_path / "files").parent.glob("outside.mp4"))


def test_source_validation_and_stable_error_envelope(tmp_path):
    with make_client(tmp_path) as client:
        for source in ("http://127.0.0.1/private.mp4", "https://user:password@example.com/video.mp4", "https://example.com:bad/video.mp4"):
            response = client.post("/jobs", json={"source": source})
            assert response.status_code == 422
            body = response.json()
            assert body["error"]["code"] == "INVALID_SOURCE"
            assert body["error"]["request_id"].startswith("req_")

        missing = client.get("/jobs/not-found")
        assert missing.status_code == 404
        assert missing.json()["error"]["code"] == "JOB_NOT_FOUND"


def test_bearer_and_first_device_binding(tmp_path):
    with make_client(tmp_path, token="secret-token") as client:
        assert client.get("/jobs").status_code == 401
        first = {"Authorization": "Bearer secret-token", "X-Device-ID": "android-one"}
        assert client.get("/jobs", headers=first).status_code == 200
        second = {"Authorization": "Bearer secret-token", "X-Device-ID": "android-two"}
        response = client.get("/jobs", headers=second)
        assert response.status_code == 403
        assert response.json()["error"]["code"] == "DEVICE_MISMATCH"


def test_cancel_and_resume_require_checkpoint(tmp_path):
    engine = NoCheckpointEngine()
    with make_client(tmp_path, engine) as client:
        created = client.post("/jobs", json={"source": "https://example.com/video.mp4"})
        assert created.status_code == 202
        job_id = created.json()["id"]
        for _ in range(50):
            if engine.calls:
                break
            time.sleep(0.01)
        cancelled = client.post(f"/jobs/{job_id}/cancel")
        assert cancelled.status_code == 200
        assert cancelled.json()["status"] == "cancelled"
        assert client.post(f"/jobs/{job_id}/resume").status_code == 409
