import asyncio
import hashlib
import os
import tempfile
import unittest
from datetime import timedelta
from pathlib import Path
from unittest.mock import patch

from fastapi import HTTPException

from gateway import main


class StreamRequest:
    def __init__(self, body: bytes | list[bytes], headers: dict[str, str]) -> None:
        self.headers = {key.lower(): value for key, value in headers.items()}
        self._chunks = [body] if isinstance(body, bytes) else body

    async def stream(self):
        for chunk in self._chunks:
            yield chunk


def bytes_for_size(size: int, *, seed: int = 0) -> bytes:
    block = bytes(((index + seed) % 251 for index in range(1024 * 1024)))
    return (block * (size // len(block) + 1))[:size]


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def generated_chunks(size: int, *, seed: int = 0, start_offset: int = 0, chunk_size: int = 8 * 1024 * 1024):
    for start in range(0, size, chunk_size):
        length = min(chunk_size, size - start)
        absolute_start = start_offset + start
        yield bytes(((absolute_start + index + seed) % 251 for index in range(length)))


def generated_sha256(size: int, *, seed: int = 0) -> str:
    digest = hashlib.sha256()
    for chunk in generated_chunks(size, seed=seed):
        digest.update(chunk)
    return digest.hexdigest()


class MediaLifecycleTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        root = Path(self.directory.name)
        self.patches = patch.multiple(
            main,
            DB_PATH=root / "gateway.db",
            SOURCE_ROOT=root / "sources",
            PUBLIC_BASE_URL="https://gateway.example.test",
            MEDIA_UPLOAD_CHUNK_BYTES=1024 * 1024,
            MEDIA_UPLOAD_TTL_SECONDS=300,
        )
        self.patches.start()
        main.init_db()

    async def asyncTearDown(self) -> None:
        self.patches.stop()
        self.directory.cleanup()

    async def create_upload(self, data: bytes, *, expected_sha256: str | None = None) -> dict:
        payload = main.MediaUploadInitPayload(
            filename="camera recording.mp4",
            bytes=len(data),
            sha256=expected_sha256 or sha256_bytes(data),
        )
        return await main.init_media_upload(payload)

    async def append(self, upload_id: str, data: bytes | list[bytes], offset: int, length: int | None = None) -> dict:
        length = length if length is not None else len(data)
        end = offset + length - 1
        request = StreamRequest(data, {"Content-Range": f"bytes {offset}-{end}/{self.expected_size}"})
        return await main.write_media_upload(upload_id, request)

    async def test_interrupted_resume_duplicate_corruption_and_cleanup(self):
        data = bytes_for_size(3 * 1024 * 1024 + 17)
        self.expected_size = len(data)
        upload = await self.create_upload(data)
        first = await self.append(upload["id"], data[:1024 * 1024], 0)
        self.assertEqual(first["offset"], 1024 * 1024)
        resumed = await self.append(upload["id"], data[1024 * 1024 :], first["offset"])
        self.assertEqual(resumed["progress"], 1.0)
        with patch.object(main, "validate_uploaded_media"):
            completed = await main.complete_media_upload(upload["id"])
        self.assertEqual(completed["status"], "done")
        self.assertEqual(completed["integrity"]["sha256"], sha256_bytes(data))
        source_path = Path(main.SOURCE_ROOT) / upload["id"] / "source.mp4"
        self.assertEqual(source_path.read_bytes(), data)
        self.assertEqual(main.uploaded_source_path(completed["source"]), str(source_path))
        source_path.write_bytes(b"tampered")
        self.assertIsNone(main.uploaded_source_path(completed["source"]))
        source_path.write_bytes(data)

        duplicate = await self.create_upload(data)
        self.assertTrue(duplicate["reused"])
        self.assertEqual(duplicate["id"], upload["id"])

        corrupt = await self.create_upload(data, expected_sha256="0" * 64)
        self.expected_size = len(data)
        await self.append(corrupt["id"], data, 0)
        with self.assertRaises(HTTPException) as error:
            await main.complete_media_upload(corrupt["id"])
        self.assertEqual(error.exception.status_code, 422)
        self.assertIn("MEDIA_CHECKSUM_MISMATCH", str(error.exception.detail))
        self.assertFalse((Path(main.SOURCE_ROOT) / corrupt["id"] / "source.mp4").exists())

        with main.closing(main.db()) as connection:
            old = (main.datetime.now(main.timezone.utc) - timedelta(hours=2)).isoformat()
            connection.execute("UPDATE media_uploads SET updated_at=? WHERE id=?", (old, corrupt["id"]))
            connection.commit()
        self.assertEqual(main.cleanup_media_uploads(), 1)
        with self.assertRaises(HTTPException):
            main._find_media_upload(corrupt["id"])

    async def test_processing_output_includes_integrity_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "clip.mp4"
            output.write_bytes(b"0" * 2048)
            job_dir = root / "jobs" / "pipe_01"
            job_dir.mkdir(parents=True)
            (job_dir / "render.json").write_text('{"data": {"outputs": [{"path": "' + str(output) + '"}]}}')
            with patch.object(main, "PROCESSING_ROOT", root):
                result = main.processing_results("proc_01", "pipe_01")
        artifact = result["render"]["outputs"][0]
        self.assertEqual(artifact["bytes"], 2048)
        self.assertEqual(artifact["sha256"], sha256_bytes(b"0" * 2048))
        self.assertEqual(artifact["integrity"]["algorithm"], "sha256")

    async def test_large_size_matrix_is_available_for_real_storage_runs(self):
        if os.getenv("RUN_LARGE_MEDIA_TESTS") != "1":
            self.skipTest("Set RUN_LARGE_MEDIA_TESTS=1 to exercise 100MB, 500MB, and 1GB+ disk-backed uploads.")
        configured = os.getenv("MEDIA_TEST_SIZES_MB", "100,500,1025")
        for size_mb in (int(value) for value in configured.split(",")):
            size = size_mb * 1024 * 1024
            seed = size_mb
            expected_sha256 = generated_sha256(size, seed=seed)
            self.expected_size = size
            upload = await main.init_media_upload(main.MediaUploadInitPayload(filename="large-camera-recording.mp4", bytes=size, sha256=expected_sha256))
            # The body is sent in two disk-backed requests to exercise an interruption/resume boundary.
            pivot = size // 2
            first = list(generated_chunks(pivot, seed=seed))
            await self.append(upload["id"], first, 0, pivot)
            status = await main.media_upload_status(upload["id"])
            self.assertEqual(status["offset"], pivot)
            second = list(generated_chunks(size - pivot, seed=seed, start_offset=pivot))
            await self.append(upload["id"], second, pivot, size - pivot)
            # This matrix measures disk-backed resumable I/O, not codec validity;
            # media probe behavior is covered by the dedicated safety tests.
            with patch.object(main, "validate_uploaded_media"):
                result = await main.complete_media_upload(upload["id"])
            self.assertEqual(result["integrity"]["bytes"], size)
            self.assertEqual(result["integrity"]["sha256"], expected_sha256)


if __name__ == "__main__":
    unittest.main()


__all__ = ["MediaLifecycleTests"]


# The intended command is:
# RUN_LARGE_MEDIA_TESTS=1 pytest -q gateway/tests/test_media_lifecycle.py
