"""Unified lifecycle manager for AI model artifacts used by the pipeline."""

from __future__ import annotations

import hashlib
import shutil
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Callable, Iterable

import httpx

from .. import config
from ..models import registry
from ..models.registry import ModelSpec
from .hardware import HardwareInfo, inspect_resources


class ModelRuntimeError(RuntimeError):
    """Stable model error with a safe code and model key."""

    def __init__(self, code: str, message: str, *, model: str):
        self.code = code
        self.model = model
        super().__init__(message)


@dataclass(frozen=True)
class ModelStatus:
    key: str
    name: str
    version: str
    size_bytes: int | None
    approx_mb: int
    checksum: str | None
    actual_checksum: str | None
    source: str
    local_path: str
    state: str
    available: bool
    loaded: bool
    resumable: bool

    def to_dict(self) -> dict[str, object]:
        return asdict(self)


Loader = Callable[[Path], object]
ProgressFn = Callable[[float, str], None]


class ModelManager:
    """Own model metadata, disk integrity, and in-process load state."""

    def __init__(self, model_specs: Iterable[ModelSpec] | None = None, *, models_root: Path | None = None, hardware: HardwareInfo | None = None):
        if model_specs is None:
            # Import for registration side effects only; model specs contain no heavy runtime imports.
            from ..models import specs as _declared_specs  # noqa: F401
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
            return self._key(key), key
        if key in self._specs:
            return key, self._specs[key]
        matches = [(candidate, spec) for candidate, spec in self._specs.items() if spec.name == key]
        if len(matches) == 1:
            return matches[0]
        if not matches:
            raise ModelRuntimeError("MODEL_MISSING", f"Unknown model: {key}", model=key)
        raise ModelRuntimeError("MODEL_MISSING", f"Model key is ambiguous: {key}", model=key)

    def _path(self, spec: ModelSpec) -> Path:
        return self._root / spec.name / spec.filename

    @staticmethod
    def _hash(path: Path) -> str | None:
        if not path.is_file():
            return None
        digest = hashlib.sha256()
        try:
            with path.open("rb") as handle:
                for block in iter(lambda: handle.read(1 << 20), b""):
                    digest.update(block)
        except OSError:
            return None
        return digest.hexdigest()

    def _valid(self, spec: ModelSpec, path: Path) -> tuple[bool, str | None, str]:
        if not path.exists():
            return False, None, "missing"
        try:
            if not path.is_file() or path.stat().st_size == 0:
                return False, self._hash(path), "corrupted"
            if spec.size_bytes is not None and path.stat().st_size != spec.size_bytes:
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
        return ModelStatus(key, spec.name, spec.version, spec.size_bytes, spec.approx_mb, spec.sha256, actual, spec.source or spec.url or "unspecified", str(path), state, valid, loaded, part.exists())

    def check(self, key: str | ModelSpec) -> dict[str, object]:
        resolved, spec = self._resolve(key)
        return self._status(resolved, spec).to_dict()

    def status(self, key: str | ModelSpec | None = None) -> dict[str, object] | list[dict[str, object]]:
        if key is not None:
            return self.check(key)
        return [self._status(candidate, spec).to_dict() for candidate, spec in self._specs.items()]

    def verify(self, key: str | ModelSpec) -> Path:
        resolved, spec = self._resolve(key)
        state = self._status(resolved, spec)
        if state.state in {"missing", "partial"}:
            raise ModelRuntimeError("MODEL_MISSING", f"Model is not downloaded: {resolved}", model=resolved)
        if not state.available:
            raise ModelRuntimeError("MODEL_INVALID", f"Model integrity verification failed: {resolved}", model=resolved)
        return self._path(spec)

    def download(self, key: str | ModelSpec, progress: ProgressFn | None = None) -> Path:
        resolved, spec = self._resolve(key)
        path = self._path(spec)
        if self._status(resolved, spec).available:
            return path
        if not spec.managed or not spec.url:
            raise ModelRuntimeError("MODEL_MISSING", f"Populate the external model cache at {path}.", model=resolved)
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
                total = int(response.headers.get("content-length", "0")) + offset
                seen = offset
                with part.open("ab") as handle:
                    for chunk in response.iter_bytes():
                        handle.write(chunk)
                        seen += len(chunk)
                        if progress and total:
                            progress(seen / total, f"Downloading {spec.name}")
        except (httpx.HTTPError, OSError, RuntimeError) as error:
            raise ModelRuntimeError("MODEL_DOWNLOAD_FAILED", f"Model download failed; partial data was retained for resume: {error}", model=resolved) from error
        valid, _, _ = self._valid(spec, part)
        if not valid:
            part.unlink(missing_ok=True)
            raise ModelRuntimeError("MODEL_INVALID", f"Downloaded model failed integrity verification: {resolved}", model=resolved)
        part.replace(path)
        return path

    def resume(self, key: str | ModelSpec, progress: ProgressFn | None = None) -> Path:
        return self.download(key, progress=progress)

    def load(self, key: str | ModelSpec, loader: Loader | None = None) -> object:
        resolved, _ = self._resolve(key)
        if resolved in self._loaded:
            return self._loaded[resolved]
        path = self.verify(resolved)
        try:
            value = loader(path) if loader else path
        except Exception as error:  # noqa: BLE001 - normalize loader failures at the boundary
            raise ModelRuntimeError("MODEL_INVALID", f"Model could not be loaded: {resolved}", model=resolved) from error
        self._loaded[resolved] = value
        return value

    def unload(self, key: str | ModelSpec) -> bool:
        resolved, _ = self._resolve(key)
        value = self._loaded.pop(resolved, None)
        if value is None:
            return False
        close = getattr(value, "close", None)
        if callable(close):
            close()
        return True

    def delete(self, key: str | ModelSpec) -> bool:
        resolved, spec = self._resolve(key)
        self.unload(resolved)
        path = self._path(spec)
        deleted = False
        try:
            for candidate in (path, path.with_suffix(path.suffix + ".part")):
                if candidate.is_dir():
                    shutil.rmtree(candidate)
                    deleted = True
                elif candidate.exists():
                    candidate.unlink()
                    deleted = True
        except OSError as error:
            raise ModelRuntimeError("MODEL_INVALID", f"Could not delete model artifact: {resolved}", model=resolved) from error
        return deleted

    @property
    def hardware(self) -> HardwareInfo:
        return self._hardware


__all__ = ["ModelManager", "ModelRuntimeError", "ModelStatus"]
