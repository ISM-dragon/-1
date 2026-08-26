"""Unified lifecycle manager for every AI model artifact used by the pipeline."""

from __future__ import annotations

import hashlib
import shutil
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Callable, Iterable

import httpx

from .. import config
from ..models.registry import ModelRuntimeError, ModelSpec
from ..models import registry
from .hardware import HardwareInfo, inspect_resources


@dataclass(frozen=True)
class ModelStatus:
    key: str
    name: str
    version: str
    size_bytes: int | None
    approx_mb: int
    checksum: str | None
    actual_checksum: str | None
    checksum_pinned: bool
    source: str
    local_path: str
    hardware: dict
    state: str
    available: bool
    loaded: bool
    resumable: bool

    def to_dict(self) -> dict:
        return asdict(self)


Loader = Callable[[Path], object]
ProgressFn = Callable[[float, str], None]


class ModelManager:
    """Own model metadata, disk integrity, and in-process load state.

    ``managed=False`` entries (WhisperX, Silero VAD, alignment, and SER) are
    still visible and verifiable, but are populated by their upstream cache
    manager. A caller can supply a custom downloader/loader when integrating
    such a provider; the manager never pretends that an absent cache is ready.
    """

    def __init__(
        self,
        model_specs: Iterable[ModelSpec] | None = None,
        *,
        models_root: Path | None = None,
        hardware: HardwareInfo | None = None,
    ) -> None:
        specs = list(model_specs or registry.REGISTRY.values())
        self._specs = {self._key(spec): spec for spec in specs}
        self._root = Path(models_root or config.models_dir())
        self._hardware = hardware or inspect_resources(self._root)
        self._loaded: dict[str, object] = {}

    @staticmethod
    def _key(spec: ModelSpec) -> str:
        return f"{spec.name}/{spec.filename}"

    def _resolve(self, key: str | ModelSpec) -> tuple[str, ModelSpec]:
        if isinstance(key, ModelSpec):
            resolved = self._key(key)
            return resolved, key
        if key in self._specs:
            return key, self._specs[key]
        matches = [(k, s) for k, s in self._specs.items() if s.name == key]
        if len(matches) == 1:
            return matches[0]
        if not matches:
            raise ModelRuntimeError("MODEL_MISSING", f"Unknown model: {key}", model=key)
        raise ModelRuntimeError(
            "MODEL_MISSING",
            f"Model key is ambiguous; use name/filename: {key}",
            model=key,
        )

    def _path(self, spec: ModelSpec) -> Path:
        return self._root / (spec.relative_path or f"{spec.name}/{spec.filename}")

    @staticmethod
    def _hash(path: Path) -> str | None:
        if not path.is_file():
            return None
        digest = hashlib.sha256()
        try:
            with path.open("rb") as fh:
                for block in iter(lambda: fh.read(1 << 20), b""):
                    digest.update(block)
        except OSError:
            return None
        return digest.hexdigest()

    def _valid(self, spec: ModelSpec, path: Path) -> tuple[bool, str | None, str]:
        if not path.exists():
            return False, None, "missing"
        try:
            if path.is_file() and path.stat().st_size == 0:
                return False, None, "corrupted"
            if path.is_file() and spec.size_bytes is not None and path.stat().st_size != spec.size_bytes:
                return False, self._hash(path), "corrupted"
        except OSError:
            return False, None, "corrupted"
        actual = self._hash(path)
        if spec.sha256 and actual != spec.sha256:
            return False, actual, "corrupted"
        return True, actual, "ready"

    def _status(self, key: str, spec: ModelSpec) -> ModelStatus:
        path = self._path(spec)
        valid, actual, state = self._valid(spec, path)
        part = path.with_suffix(path.suffix + ".part")
        if state == "missing" and part.exists():
            state = "partial"
        loaded = key in self._loaded
        if loaded:
            state = "loaded"
        return ModelStatus(
            key=key,
            name=spec.name,
            version=spec.version,
            size_bytes=spec.size_bytes,
            approx_mb=spec.approx_mb,
            checksum=spec.sha256,
            actual_checksum=actual,
            checksum_pinned=bool(spec.sha256),
            source=spec.source or spec.url or "unspecified",
            local_path=str(path),
            hardware=dict(spec.hardware or {}),
            state=state,
            available=valid,
            loaded=loaded,
            resumable=part.exists(),
        )

    def check(self, key: str | ModelSpec) -> dict:
        """Inspect a model without raising for a missing or corrupt artifact."""
        resolved, spec = self._resolve(key)
        return self._status(resolved, spec).to_dict()

    def status(self, key: str | ModelSpec | None = None) -> dict | list[dict]:
        if key is not None:
            return self.check(key)
        return [self._status(k, s).to_dict() for k, s in self._specs.items()]

    def verify(self, key: str | ModelSpec) -> Path:
        resolved, spec = self._resolve(key)
        status = self._status(resolved, spec)
        if status.state == "missing" or status.state == "partial":
            raise ModelRuntimeError("MODEL_MISSING", f"Model is not downloaded: {resolved}", model=resolved)
        if not status.available:
            raise ModelRuntimeError(
                "MODEL_CORRUPTED",
                f"Model integrity verification failed: {resolved}",
                model=resolved,
            )
        return self._path(spec)

    def download(self, key: str | ModelSpec, progress: ProgressFn | None = None) -> Path:
        resolved, spec = self._resolve(key)
        path = self._path(spec)
        current = self._status(resolved, spec)
        if current.available:
            return path
        if not spec.managed or not spec.url:
            raise ModelRuntimeError(
                "MODEL_DOWNLOAD_FAILED",
                f"{resolved} is supplied by an external cache; populate {path} with its upstream loader.",
                model=resolved,
            )
        path.parent.mkdir(parents=True, exist_ok=True)
        part = path.with_suffix(path.suffix + ".part")
        offset = part.stat().st_size if part.exists() else 0
        headers = {"Range": f"bytes={offset}-"} if offset else {}
        try:
            with httpx.stream("GET", spec.url, headers=headers, follow_redirects=True, timeout=config.HTTP_TIMEOUT) as response:
                if response.status_code == 200 and offset:
                    offset = 0
                    part.unlink(missing_ok=True)
                elif response.status_code not in (200, 206):
                    raise RuntimeError(f"HTTP {response.status_code}")
                total = int(response.headers.get("content-length", 0)) + offset
                seen = offset
                with part.open("ab") as fh:
                    for chunk in response.iter_bytes():
                        fh.write(chunk)
                        seen += len(chunk)
                        if progress and total:
                            progress(seen / total, f"Downloading {spec.name}")
        except (httpx.HTTPError, OSError, RuntimeError) as err:
            raise ModelRuntimeError(
                "MODEL_DOWNLOAD_FAILED",
                f"Could not download {resolved}; partial data was retained for resume: {err}",
                model=resolved,
            ) from err
        valid, _, _ = self._valid(spec, part)
        if not valid:
            part.unlink(missing_ok=True)
            raise ModelRuntimeError(
                "MODEL_CORRUPTED",
                f"Downloaded model failed size/checksum verification: {resolved}",
                model=resolved,
            )
        part.replace(path)
        return path

    def resume(self, key: str | ModelSpec, progress: ProgressFn | None = None) -> Path:
        return self.download(key, progress=progress)

    def load(self, key: str | ModelSpec, loader: Loader | None = None) -> object:
        resolved, spec = self._resolve(key)
        if resolved in self._loaded:
            return self._loaded[resolved]
        path = self.verify(resolved)
        try:
            value = loader(path) if loader else path
        except ModelRuntimeError:
            raise
        except Exception as err:  # noqa: BLE001 - normalize model runtime failures
            raise ModelRuntimeError(
                "MODEL_CORRUPTED",
                f"Model could not be loaded: {resolved}: {err}",
                model=resolved,
            ) from err
        self._loaded[resolved] = value
        return value

    def unload(self, key: str | ModelSpec) -> bool:
        resolved, _ = self._resolve(key)
        value = self._loaded.pop(resolved, None)
        if value is not None:
            close = getattr(value, "close", None)
            if callable(close):
                close()
            return True
        return False

    def delete(self, key: str | ModelSpec) -> bool:
        resolved, spec = self._resolve(key)
        self.unload(resolved)
        path = self._path(spec)
        deleted = False
        try:
            if path.is_dir():
                shutil.rmtree(path)
                deleted = True
            elif path.exists():
                path.unlink()
                deleted = True
            part = path.with_suffix(path.suffix + ".part")
            if part.exists():
                part.unlink()
                deleted = True
        except OSError as err:
            raise ModelRuntimeError(
                "MODEL_CORRUPTED",
                f"Could not delete model artifact: {resolved}: {err}",
                model=resolved,
            ) from err
        return deleted

    @property
    def hardware(self) -> HardwareInfo:
        return self._hardware


__all__ = ["ModelManager", "ModelRuntimeError", "ModelStatus"]
