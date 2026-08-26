import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from fastapi import HTTPException
from gateway.main import validate_public_source, validate_uploaded_media


class GatewaySafetyTests(unittest.TestCase):
    def test_public_https_source_is_allowed(self):
        self.assertEqual(validate_public_source("https://www.youtube.com/watch?v=demo"), "https://www.youtube.com/watch?v=demo")

    def test_localhost_is_rejected(self):
        with self.assertRaises(HTTPException):
            validate_public_source("http://127.0.0.1:8787/internal")

    def test_private_ip_is_rejected(self):
        with self.assertRaises(HTTPException):
            validate_public_source("https://10.0.0.4/video")

    def test_corrupted_uploaded_media_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "corrupted.mp4"
            path.write_bytes(b"not a video")
            probe_failure = SimpleNamespace(returncode=1, stdout="", stderr="invalid media")
            with patch("gateway.main.subprocess.run", return_value=probe_failure):
                with self.assertRaises(HTTPException) as context:
                    validate_uploaded_media(path)
            self.assertEqual(context.exception.status_code, 422)
            self.assertIn("MEDIA_INVALID", str(context.exception.detail))

    def test_non_http_source_is_rejected(self):
        with self.assertRaises(HTTPException):
            validate_public_source("file:///tmp/video.mp4")


if __name__ == "__main__":
    unittest.main()
