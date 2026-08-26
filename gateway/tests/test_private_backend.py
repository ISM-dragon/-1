import asyncio
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from fastapi import HTTPException
from starlette.datastructures import Headers, UploadFile
from starlette.testclient import TestClient


from gateway import main


class PrivateBackendTests(unittest.TestCase):
    def test_android_routes_are_registered(self):
        routes = {(route.path, next(iter(route.methods or ()))) for route in main.app.routes}
        expected = {
            ("/jobs", "POST"),
            ("/jobs/{job_id}", "GET"),
            ("/jobs/{job_id}/cancel", "POST"),
            ("/jobs/{job_id}/resume", "POST"),
            ("/jobs/{job_id}/results", "GET"),
            ("/jobs/{job_id}/clips/{clip_id}", "GET"),
            ("/jobs/{job_id}/clips/{clip_id}/render", "POST"),
        }
        self.assertTrue(expected.issubset(routes))

    def test_upload_is_streamed_and_persisted(self):
        async def exercise(root: Path):
            upload_path = root / "input.mp4"
            upload_path.write_bytes(b"video-bytes")
            upload = UploadFile(upload_path.open("rb"), filename="input.mp4", headers=Headers({"content-type": "video/mp4"}))
            with (
                patch.object(main, "DB_PATH", root / "gateway.db"),
                patch.object(main, "SOURCE_ROOT", root / "sources"),
                patch.object(main, "MAX_UPLOAD_BYTES", 1024),
                patch.object(main, "PUBLIC_BASE_URL", "http://private.test"),
            ):
                main.init_db()
                source_url, size = await main._private_upload_to_source(upload)
                saved = list((root / "sources").glob("*/source.mp4"))
                self.assertEqual(size, len(b"video-bytes"))
                self.assertEqual(len(saved), 1)
                self.assertIn("/v1/sources/jobs/", source_url)
                with main.closing(main.db()) as connection:
                    row = connection.execute("SELECT status, total, completed FROM source_jobs").fetchone()
                self.assertEqual(tuple(row), ("done", 1, 1))

        with tempfile.TemporaryDirectory() as directory:
            asyncio.run(exercise(Path(directory)))

    def test_upload_rejects_size_limit_and_removes_partial_file(self):
        async def exercise(root: Path):
            upload_path = root / "too-large.mp4"
            upload_path.write_bytes(b"0123456789")
            upload = UploadFile(upload_path.open("rb"), filename="too-large.mp4", headers=Headers({"content-type": "video/mp4"}))
            with (
                patch.object(main, "DB_PATH", root / "gateway.db"),
                patch.object(main, "SOURCE_ROOT", root / "sources"),
                patch.object(main, "MAX_UPLOAD_BYTES", 4),
            ):
                main.init_db()
                with self.assertRaises(HTTPException) as context:
                    await main._private_upload_to_source(upload)
                self.assertEqual(context.exception.status_code, 413)
                self.assertEqual(list((root / "sources").glob("**/*")), [])

        with tempfile.TemporaryDirectory() as directory:
            asyncio.run(exercise(Path(directory)))

    def test_http_upload_creates_durable_android_job(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            video = root / "real-video.mp4"
            video.write_bytes(b"minimal-test-video-payload")
            with (
                patch.object(main, "DB_PATH", root / "gateway.db"),
                patch.object(main, "PROCESSING_ROOT", root / "processing"),
                patch.object(main, "SOURCE_ROOT", root / "sources"),
                patch.object(main, "PUBLIC_BASE_URL", "http://private.test"),
                patch.object(main, "GATEWAY_TOKEN", "test-token"),
                patch.object(main, "REQUIRE_GATEWAY_TOKEN", True),
                patch.object(main, "read_server_gemini_key", return_value="server-key"),
                patch.object(main, "pipeline_checks", return_value={"pipeline": True, "storage": True}),
                patch.object(main, "_ffmpeg_capability", return_value={"ready": True}),
                patch.object(main._processing_workers, "submit", return_value=True),
            ):
                with TestClient(main.app) as client:
                    unauthorized = client.get("/jobs/does-not-exist", headers={"Authorization": "Bearer wrong-token"})
                    self.assertEqual(unauthorized.status_code, 401)
                    response = client.post(
                        "/jobs",
                        headers={"Authorization": "Bearer test-token"},
                        files={"file": ("real-video.mp4", video.read_bytes(), "video/mp4")},
                        data={"llm": "gemini", "mode": "balanced"},
                    )
                    self.assertEqual(response.status_code, 200, response.text)
                    body = response.json()
                    self.assertEqual(body["status"], "queued")
                    self.assertIn("current_stage", body)
                    status = client.get(f"/jobs/{body['job_id']}", headers={"Authorization": "Bearer test-token"})
                    self.assertEqual(status.status_code, 200, status.text)
                    self.assertEqual(status.json()["job_id"], body["job_id"])
                    self.assertEqual(status.json()["progress"], None)

    def test_private_job_response_exposes_progress_and_safe_errors(self):
        row = SimpleNamespace(
            id="job_1", status="failed", state="FAILED", stage="score", progress=0.75,
            message="Pipeline failed", error="Safe failure", error_code="PIPELINE_FAILED",
            recoverable=1, cancel_requested=0, retry_count=1, created_at="now", updated_at="now",
            correlation_id="cor_1", results=None,
        )
        result = main._private_job_response(vars(row))
        self.assertEqual(result["current_stage"], "score")
        self.assertEqual(result["progress"], 0.75)
        self.assertEqual(result["errors"], [{"code": "PIPELINE_FAILED", "message": "Safe failure"}])

    def test_results_reject_non_terminal_job(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.object(main, "DB_PATH", root / "gateway.db"):
                main.init_db()
                now = main.now_iso()
                with main.closing(main.db()) as connection:
                    connection.execute(
                        "INSERT INTO processing_jobs (id, source, status, state, created_at, updated_at) VALUES (?, ?, 'queued', 'QUEUED', ?, ?)",
                        ("job_1", "upload:one", now, now),
                    )
                    connection.commit()
                with self.assertRaises(HTTPException) as context:
                    main._private_result_for_job("job_1", main._private_processing_row("job_1"))
                self.assertEqual(context.exception.status_code, 409)

    def test_clip_artifact_containment_rejects_path_escape(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.object(main, "PROCESSING_ROOT", root / "processing"):
                outside = root / "outside.mp4"
                outside.write_bytes(b"not exposed")
                self.assertIsNone(main._private_safe_clip_entry("job_1", "pipeline_1", {"path": str(outside), "clip": 0}))


if __name__ == "__main__":
    unittest.main()
