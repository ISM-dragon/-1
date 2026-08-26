"""Model metadata and the backwards-compatible download substrate.

The public lifecycle API is :class:`publikclip_pipeline.runtime.model_manager.ModelManager`.
This module remains intentionally small because existing stages import
``registry.ensure`` directly.
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

import httpx

from .. import config

ProgressFn = Callable[[float, str], None]


class ModelRuntimeError(RuntimeError):
    """Stable model lifecycle error shared by legacy registry callers."""

    def __init__(self, code: str, message: str, *, model: str | None = None):
        self.code = code
        self.model = model
        super().__init__(message)


@dataclass(frozen=True)
class ModelSpec:
    name: str
    filename: str
    url: str
    sha256: str | None = None
    approx_mb: int = 0
    version: str = "unversioned"
    size_bytes: int | None = None
    source: str | None = None
    hardware: dict[str, str | int | float | bool] | None = None
    relative_path: str | None = None
    managed: bool = True


REGISTRY: dict[str, ModelSpec] = {}


def register(spec: ModelSpec) -> ModelSpec:
    REGISTRY[f"{spec.name}/{spec.filename}"] = spec
    return spec


def model_path(spec: ModelSpec) -> Path:
    return config.models_dir() / (spec.relative_path or f"{spec.name}/{spec.filename}")


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for block in iter(lambda: fh.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def _existing_is_valid(spec: ModelSpec, path: Path) -> bool:
    try:
        if not path.exists():
            return False
        if path.is_file() and path.stat().st_size == 0:
            return False
        if path.is_file() and spec.size_bytes is not None and path.stat().st_size != spec.size_bytes:
            return False
        return not spec.sha256 or (path.is_file() and _sha256(path) == spec.sha256)
    except OSError:
        return False


def is_present(spec: ModelSpec) -> bool:
    return _existing_is_valid(spec, model_path(spec))


def ensure(spec: ModelSpec, progress: ProgressFn) -> Path:
    """Download a file with resume; verify size and SHA-256 when pinned."""
    dest = model_path(spec)
    if dest.exists():
        if _existing_is_valid(spec, dest):
            return dest
        if dest.is_file():
            dest.unlink(missing_ok=True)
    if not spec.managed or not spec.url:
        raise ModelRuntimeError(
            "MODEL_DOWNLOAD_FAILED",
            f"Model {spec.name} is managed by an external cache",
            model=spec.name,
        )
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    offset = tmp.stat().st_size if tmp.exists() else 0
    headers = {"Range": f"bytes={offset}-"} if offset else {}
    label = f"Downloading {spec.name}" + (f" (~{spec.approx_mb} MB)" if spec.approx_mb else "")
    try:
        with httpx.stream(
            "GET", spec.url, headers=headers, follow_redirects=True, timeout=config.HTTP_TIMEOUT
        ) as res:
            if res.status_code == 200 and offset:
                offset = 0
                tmp.unlink(missing_ok=True)
            elif res.status_code not in (200, 206):
                raise ModelRuntimeError(
                    "MODEL_DOWNLOAD_FAILED",
                    f"HTTP {res.status_code}",
                    model=spec.name,
                )
            total = int(res.headers.get("content-length", 0)) + offset
            seen = offset
            with open(tmp, "ab") as fh:
                for chunk in res.iter_bytes():
                    fh.write(chunk)
                    seen += len(chunk)
                    if total:
                        progress(seen / total, label)
    except (httpx.HTTPError, OSError) as err:
        raise ModelRuntimeError(
            "MODEL_DOWNLOAD_FAILED",
            f"download failed: {err}",
            model=spec.name,
        ) from err
    if not _existing_is_valid(spec, tmp):
        tmp.unlink(missing_ok=True)
        raise ModelRuntimeError(
            "MODEL_CORRUPTED",
            f"checksum/size verification failed for {spec.name}",
            model=spec.name,
        )
    tmp.replace(dest)
    return dest
