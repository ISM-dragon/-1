"""Filesystem storage boundary for private video inputs and rendered clips."""
from __future__ import annotations

import mimetypes
import secrets
from pathlib import Path
from urllib.parse import unquote


VIDEO_EXTENSIONS = {".mp4", ".mov", ".m4v", ".webm", ".mkv", ".avi"}


class StorageError(ValueError):
    pass


class Storage:
    def __init__(self, root: Path, max_upload_bytes: int):
        self.root = root.resolve()
        self.max_upload_bytes = max_upload_bytes
        self.uploads = self.root / "uploads"
        self.jobs = self.root / "jobs"
        self.uploads.mkdir(parents=True, exist_ok=True)
        self.jobs.mkdir(parents=True, exist_ok=True)

    def new_upload_id(self) -> str:
        return f"upl_{secrets.token_urlsafe(12)}"

    def new_job_id(self) -> str:
        return f"job_{secrets.token_urlsafe(12)}"

    def upload_path(self, upload_id: str, filename: str) -> Path:
        decoded_name = unquote(filename)
        if "\x00" in decoded_name:
            raise StorageError("Invalid upload filename")
        safe_name = Path(decoded_name).name
        if not safe_name or safe_name in {".", ".."}:
            safe_name = "video.mp4"
        suffix = Path(safe_name).suffix.lower()
        if suffix not in VIDEO_EXTENSIONS:
            suffix = ".mp4"
        directory = (self.uploads / upload_id).resolve()
        if self.root not in directory.parents:
            raise StorageError("Invalid upload identifier")
        directory.mkdir(parents=True, exist_ok=True)
        return directory / f"source{suffix}"

    def job_dir(self, job_id: str) -> Path:
        directory = (self.jobs / job_id).resolve()
        if self.root not in directory.parents:
            raise StorageError("Invalid job identifier")
        directory.mkdir(parents=True, exist_ok=True)
        return directory

    def resolve_upload(self, upload_id: str, path_text: str) -> Path:
        base = (self.uploads / upload_id).resolve()
        target = (base / unquote(path_text)).resolve()
        if base not in target.parents or not target.is_file():
            raise StorageError("Upload file not found")
        return target

    def resolve_clip(self, job_id: str, clip_name: str) -> Path:
        base = (self.job_dir(job_id) / "clips").resolve()
        target = (base / unquote(clip_name)).resolve()
        if base not in target.parents or not target.is_file() or target.suffix.lower() != ".mp4":
            raise StorageError("Clip not found")
        return target

    def resolve_engine_clip(self, engine_job_id: str, clip_name: str) -> Path:
        base = (self.jobs / engine_job_id / "clips").resolve()
        target = (base / unquote(clip_name)).resolve()
        if self.root not in base.parents or base not in target.parents or not target.is_file() or target.suffix.lower() != ".mp4":
            raise StorageError("Clip not found")
        return target

    @staticmethod
    def content_type(path: Path) -> str:
        return mimetypes.guess_type(path.name)[0] or "video/mp4"

    def cleanup_upload(self, upload_id: str) -> None:
        directory = (self.uploads / upload_id).resolve()
        if self.root in directory.parents and directory.is_dir():
            for child in directory.iterdir():
                if child.is_file() or child.is_symlink():
                    child.unlink(missing_ok=True)
            directory.rmdir()
