import asyncio
import hashlib
import json
import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from fastapi import HTTPException
from starlette.requests import Request

from gateway import main
from gateway.processing_service import pipeline_command


class _StreamRequest:
    def __init__(self, body: bytes, headers: dict[str, str]) -> None:
        self._body = body
        self.headers = {key.lower(): value for key, value in headers.items()}

    async def stream(self):
        yield self._body


def _request(headers: dict[str, str] | None = None) -> Request:
    raw_headers = [(key.lower().encode(), value.encode()) for key, value in (headers or {}).items()]
    return Request({"type": "http", "method": "GET", "path": "/", "headers": raw_headers, "query_string": b"", "scheme": "https", "server": ("test", 443), "client": ("test", 1), "root_path": ""})


class GatewayQaSecurityTests(unittest.IsolatedAsyncioTestCase):
    async def test_private_auth_fails_closed_when_required(self):
        with patch.multiple(main, GATEWAY_TOKEN="qa-secret", REQUIRE_GATEWAY_TOKEN=True):
            with self.assertRaises(HTTPException) as missing:
                await main.auth(_request())
            self.assertEqual(missing.exception.status_code, 401)
            with self.assertRaises(HTTPException) as wrong:
                await main.auth(_request({"authorization": "Bearer wrong"}))
            self.assertEqual(wrong.exception.status_code, 401)
            await main.auth(_request({"authorization": "Bearer qa-secret"}))

        with patch.multiple(main, GATEWAY_TOKEN="", REQUIRE_GATEWAY_TOKEN=True):
            with self.assertRaises(HTTPException) as unconfigured:
                await main.auth(_request())
            self.assertEqual(unconfigured.exception.status_code, 503)

    def test_dns_rebinding_to_private_address_is_rejected(self):
        with patch.object(main.socket, "getaddrinfo", return_value=[(2, 1, 6, "", ("192.168.1.20", 443))]):
            with self.assertRaises(HTTPException) as error:
                main.validate_public_source("https://attacker.example/video.mp4")
        self.assertEqual(error.exception.status_code, 422)

    async def test_source_and_processing_media_paths_cannot_escape_authorized_root(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_root = root / "sources"
            processing_root = root / "processing"
            source_root.mkdir()
            processing_root.mkdir()
            outside = root / "outside.mp4"
            outside.write_bytes(b"x" * 2048)
            with patch.multiple(main, DB_PATH=root / "gateway.db", SOURCE_ROOT=source_root, PROCESSING_ROOT=processing_root, PUBLIC_BASE_URL="https://gateway.example.test"):
                main.init_db()
                now = main.now_iso()
                digest = hashlib.sha256(outside.read_bytes()).hexdigest()
                with sqlite3.connect(main.DB_PATH) as connection:
                    connection.execute("INSERT INTO source_jobs (id, source, status, created_at, updated_at) VALUES (?, ?, 'done', ?, ?)", ("src_qa", "upload:src_qa", now, now))
                    connection.execute("INSERT INTO media_uploads (id, filename, expected_bytes, expected_sha256, received_bytes, status, temp_path, media_path, source_job_id, created_at, updated_at, completed_at) VALUES (?, ?, ?, ?, ?, 'completed', ?, ?, ?, ?, ?, ?)", ("src_qa", "source.mp4", 2048, digest, 2048, str(outside), str(outside), "src_qa", now, now, now))
                    connection.execute("INSERT INTO processing_jobs (id, source, status, state, pipeline_job_id, created_at, updated_at) VALUES (?, ?, 'done', 'COMPLETED', ?, ?, ?)", ("proc_qa", "https://gateway.example.test/v1/sources/jobs/src_qa/media/source.mp4", "pipe_qa", now, now))
                    connection.commit()
                with self.assertRaises(HTTPException):
                    await main.source_media("src_qa", "../outside.mp4")
                with self.assertRaises(HTTPException):
                    await main.processing_media("proc_qa", "../../outside.mp4")

    def test_pipeline_arguments_are_structured_and_do_not_use_shell(self):
        dangerous_source = "https://example.test/video; touch /tmp/qa-marker.mp4"
        command = pipeline_command(Path("/tmp/pipeline"), "", {"PATH": ""})
        self.assertIsInstance(command, list)
        self.assertNotIn(dangerous_source, command)
        self.assertNotIn(";", " ".join(command))
        with patch("gateway.processing_service.shutil.which", return_value=None):
            command = pipeline_command(Path("/tmp/pipeline"), "", {"PATH": ""})
        self.assertEqual(command[:2], [__import__("sys").executable, "-m"])

    async def test_malformed_content_range_offset_is_http_error_not_500(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.multiple(main, DB_PATH=root / "gateway.db", SOURCE_ROOT=root / "sources", MEDIA_UPLOAD_CHUNK_BYTES=8):
                main.init_db()
                data = b"valid-bytes-for-upload"
                upload = await main.init_media_upload(main.MediaUploadInitPayload(filename="clip.mp4", bytes=len(data), sha256=hashlib.sha256(data).hexdigest()))
                request = _StreamRequest(data, {"Content-Range": f"bytes 0-{len(data)-1}/{len(data)}", "X-Upload-Offset": "not-an-int"})
                with self.assertRaises(HTTPException) as error:
                    await main.write_media_upload(upload["id"], request)
                self.assertEqual(error.exception.status_code, 400)

    async def test_malformed_content_length_is_http_error_not_500(self):
        with patch.object(main, "MAX_UPLOAD_BYTES", 1024):
            request = _StreamRequest(b"data", {"Content-Length": "not-an-int"})
            with self.assertRaises(HTTPException) as error:
                await main.upload_source(request)
            self.assertEqual(error.exception.status_code, 400)

    def test_ffmpeg_probe_arguments_are_bounded_and_shell_free(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "video.mp4"
            path.write_bytes(b"payload")
            captured = {}

            class Probe:
                returncode = 1
                stdout = "{}"

            def fake_run(args, **kwargs):
                captured["args"] = args
                captured["kwargs"] = kwargs
                return Probe()

            with patch.object(main.subprocess, "run", side_effect=fake_run):
                with self.assertRaises(HTTPException):
                    main.validate_uploaded_media(path)
            self.assertIsInstance(captured["args"], list)
            self.assertNotIn("shell", captured["kwargs"])
            self.assertEqual(captured["kwargs"]["timeout"], 15)


class GatewayQaLifecycleTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        root = Path(self.directory.name)
        self.patches = patch.multiple(main, DB_PATH=root / "gateway.db", PROCESSING_ROOT=root / "processing", SOURCE_ROOT=root / "sources", PUBLIC_BASE_URL="https://gateway.example.test")
        self.patches.start()
        main.init_db()

    def tearDown(self):
        self.patches.stop()
        self.directory.cleanup()

    def _insert_job(self, job_id="proc_worker", state="QUEUED"):
        now = main.now_iso()
        with sqlite3.connect(main.DB_PATH) as connection:
            connection.execute("INSERT INTO processing_jobs (id, source, status, state, created_at, updated_at) VALUES (?, ?, 'queued', ?, ?, ?)", (job_id, "https://example.test/video.mp4", state, now, now))
            connection.commit()

    def test_worker_failure_is_persisted_as_failed_and_not_requeued(self):
        self._insert_job()
        main.mark_processing_worker_error("proc_worker", "RESOURCE_UNAVAILABLE: disk full")
        with sqlite3.connect(main.DB_PATH) as connection:
            row = connection.execute("SELECT status, state, error, error_code, recoverable FROM processing_jobs WHERE id=?", ("proc_worker",)).fetchone()
        self.assertEqual(row[0], "failed")
        self.assertEqual(row[1], "FAILED")
        self.assertEqual(row[2], "RESOURCE_UNAVAILABLE: disk full")
        self.assertEqual(row[3], "WORKER_FAILED")
        self.assertEqual(row[4], 1)

    def test_restart_history_records_actual_previous_state(self):
        self._insert_job("proc_restart", "RENDERING")
        with patch.object(main._processing_workers, "start", new=unittest.mock.AsyncMock()), patch.object(main._source_workers, "start", new=unittest.mock.AsyncMock()), patch.object(main._processing_workers, "submit", return_value=True), patch.object(main._source_workers, "submit", return_value=True):
            asyncio.run(main.startup())
            asyncio.run(main.shutdown())
        with sqlite3.connect(main.DB_PATH) as connection:
            row = connection.execute("SELECT from_state, to_state FROM processing_job_transitions WHERE job_id=? ORDER BY id DESC LIMIT 1", ("proc_restart",)).fetchone()
        self.assertEqual(row, ("RENDERING", "INTERRUPTED"))


if __name__ == "__main__":
    unittest.main()
