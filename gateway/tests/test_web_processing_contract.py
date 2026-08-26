import asyncio
import json
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

    def test_processing_results_enriches_render_outputs_for_android_clients(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            job_dir = root / "jobs" / "pipeline_01"
            job_dir.mkdir(parents=True)
            output_path = job_dir / "clips" / "clip_00.mp4"
            output_path.parent.mkdir()
            output_path.write_bytes(b"valid-test-artifact")
            (job_dir / "render.json").write_text(json.dumps({
                "data": {"outputs": [{"clip": 0, "path": str(output_path), "duration": 31.0}]}
            }))
            (job_dir / "score.json").write_text(json.dumps({
                "data": {"clips": [{"start": 12.5, "end": 43.5, "title": "Hook", "transcript": "A real hook", "score": 88}]}
            }))

            with (
                patch.object(main, "PROCESSING_ROOT", root),
                patch.object(main, "PUBLIC_BASE_URL", "https://gateway.example.test"),
                patch.object(main, "validate_media_artifact", return_value=(True, "")),
            ):
                result = main.processing_results("proc_01", "pipeline_01")

            output = result["render"]["outputs"][0]
            self.assertEqual(output["path"], "https://gateway.example.test/v1/processing/jobs/proc_01/media/clip_00.mp4")
            self.assertEqual(output["start"], 12.5)
            self.assertEqual(output["end"], 43.5)
            self.assertEqual(output["title"], "Hook")
            self.assertEqual(output["transcript"], "A real hook")

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
