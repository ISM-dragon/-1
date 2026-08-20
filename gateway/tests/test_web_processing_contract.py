import asyncio
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from fastapi import HTTPException

from gateway import main


class WebProcessingContractTests(unittest.TestCase):
    def test_rejects_private_source_before_job_creation(self):
        with self.assertRaises(HTTPException) as context:
            main.validate_processing_source("http://127.0.0.1/private-video.mp4")
        self.assertEqual(context.exception.status_code, 422)

    def test_creates_job_and_exposes_polling_shape_for_signed_gateway_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_url = "https://gateway.example.test/v1/sources/jobs/upload_01/media/source.mp4"
            request = SimpleNamespace(state=SimpleNamespace(request_id="web-contract-test"))
            payload = main.ProcessingPayload(source=source_url, llm="gemini", captions="classic", mode="balanced")
            checks = {"pipeline": True, "storage": True}

            with (
                patch.object(main, "DB_PATH", root / "gateway.db"),
                patch.object(main, "PROCESSING_ROOT", root / "processing"),
                patch.object(main, "SOURCE_ROOT", root / "sources"),
                patch.object(main, "PUBLIC_BASE_URL", "https://gateway.example.test"),
                patch.object(main, "read_server_gemini_key", return_value="server-side-key"),
                patch.object(main, "pipeline_checks", return_value=checks),
                patch.object(main, "_ffmpeg_capability", return_value={"ready": True}),
                patch.object(main._processing_workers, "submit", return_value=True),
            ):
                main.init_db()
                created = asyncio.run(main.start_processing(payload, request))
                current = asyncio.run(main.processing_status(created["id"]))

            self.assertEqual(created["status"], "queued")
            self.assertEqual(created["state"], "QUEUED")
            self.assertEqual(current["id"], created["id"])
            self.assertEqual(current["status"], "queued")
            self.assertEqual(current["state"], "QUEUED")
            self.assertIn("results", current)
            self.assertIn("transitions", current)


if __name__ == "__main__":
    unittest.main()
