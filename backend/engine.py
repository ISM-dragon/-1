"""Stable boundary between the private backend and publikclip.

The backend depends on the public ``ProcessingEngine`` contract only.  Pipeline
stage modules, checkpoint formats, and persistence implementation details stay
behind that facade.  The subprocess adapter remains available for deployments
that explicitly configure a CLI binary, but it is not the default integration.
"""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import threading
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Mapping, Protocol


@dataclass(frozen=True)
class EngineEvent:
    kind: str
    stage: str | None = None
    progress: float | None = None
    message: str | None = None
    engine_job_id: str | None = None
    payload: dict[str, Any] | None = None


def safe_engine_message(message: object, fallback: str = "Engine request failed") -> str:
    """Return a bounded message without filesystem paths or credentials."""
    text = str(message or fallback).strip()
    text = re.sub(r"(?i)(authorization\s*:\s*bearer\s+)[^\s]+", r"\1[redacted]", text)
    text = re.sub(r"(?i)(token|secret|password|api[_-]?key)\s*[=:]\s*[^\s,;]+", r"\1=[redacted]", text)
    text = re.sub(r"(?:/[^\s]+)+", "[redacted-path]", text)
    return text[:500] or fallback


class EngineError(RuntimeError):
    def __init__(self, message: str, code: str = "ENGINE_FAILED", recoverable: bool = True):
        safe = safe_engine_message(message)
        super().__init__(safe)
        self.code = code
        self.safe_message = safe
        self.recoverable = recoverable


EventCallback = Callable[[EngineEvent], None]


class Engine(Protocol):
    def available(self) -> tuple[bool, str]: ...

    def run(
        self,
        source: str,
        job_dir: Path,
        options: dict[str, Any],
        resume_engine_job_id: str | None,
        on_event: EventCallback,
        cancel_event: threading.Event,
    ) -> dict[str, Any]: ...

    def render_clip(
        self,
        engine_job_id: str,
        clip: int,
        job_dir: Path,
        on_event: EventCallback,
        cancel_event: threading.Event,
    ) -> dict[str, Any]: ...


class FacadePublikclipEngine:
    """Adapter for the public ``publikclip_pipeline.engine`` facade.

    ``PipelineEngine`` reads ``PUBLIKCLIP_HOME`` when it performs an operation.
    The backend is intentionally a single-device service with one worker, so a
    re-entrant lock makes that process-local environment selection explicit and
    prevents concurrent requests from crossing storage roots.
    """

    def __init__(self, pipeline_dir: Path, home: Path):
        self.pipeline_dir = pipeline_dir.resolve()
        self.home = home.resolve()
        self._lock = threading.RLock()
        self._engine: Any | None = None
        self._public_error: type[BaseException] | None = None

    @contextmanager
    def _runtime(self):
        old_home = os.environ.get("PUBLIKCLIP_HOME")
        old_pythonpath = os.environ.get("PYTHONPATH")
        old_disable_secrets = os.environ.get("PUBLIKCLIP_DISABLE_LOCAL_SECRETS")
        os.environ["PUBLIKCLIP_HOME"] = str(self.home)
        os.environ["PUBLIKCLIP_DISABLE_LOCAL_SECRETS"] = "1"
        path_entry = str(self.pipeline_dir)
        os.environ["PYTHONPATH"] = path_entry + (os.pathsep + old_pythonpath if old_pythonpath else "")
        try:
            yield
        finally:
            if old_home is None:
                os.environ.pop("PUBLIKCLIP_HOME", None)
            else:
                os.environ["PUBLIKCLIP_HOME"] = old_home
            if old_pythonpath is None:
                os.environ.pop("PYTHONPATH", None)
            else:
                os.environ["PYTHONPATH"] = old_pythonpath
            if old_disable_secrets is None:
                os.environ.pop("PUBLIKCLIP_DISABLE_LOCAL_SECRETS", None)
            else:
                os.environ["PUBLIKCLIP_DISABLE_LOCAL_SECRETS"] = old_disable_secrets

    def _load(self) -> Any:
        if self._engine is not None:
            return self._engine
        if str(self.pipeline_dir) not in sys.path:
            sys.path.insert(0, str(self.pipeline_dir))
        try:
            from publikclip_pipeline.engine import EngineError as PublicEngineError
            from publikclip_pipeline.engine import PipelineEngine
        except Exception as error:  # noqa: BLE001 - normalized at this boundary
            raise EngineError("publikclip engine could not be loaded", "ENGINE_UNAVAILABLE", False) from error
        self._public_error = PublicEngineError
        self._engine = PipelineEngine()
        return self._engine

    def available(self) -> tuple[bool, str]:
        public_api = self.pipeline_dir / "publikclip_pipeline" / "engine" / "__init__.py"
        if not public_api.is_file():
            return False, "publikclip public engine facade is not available."
        try:
            with self._lock, self._runtime():
                self._load()
            return True, "publikclip public engine facade is available."
        except EngineError as error:
            return False, error.safe_message

    @staticmethod
    def _settings(options: Mapping[str, Any]) -> dict[str, Any]:
        settings = dict(options)
        aliases = {
            "llm": "llm_mode",
            "captions": "caption_preset",
            "mode": "processing_mode",
        }
        for source, target in aliases.items():
            if source in settings and target not in settings:
                settings[target] = settings[source]
        return settings

    def _convert_error(self, error: BaseException) -> EngineError:
        if self._public_error is not None and isinstance(error, self._public_error):
            return EngineError(
                getattr(error, "safe_message", str(error)),
                str(getattr(error, "code", "ENGINE_FAILED")),
                bool(getattr(error, "recoverable", False)),
            )
        if isinstance(error, EngineError):
            return error
        return EngineError("Pipeline execution failed", "ENGINE_FAILED", True)

    def _progress_callback(self, job_id: str, on_event: EventCallback, cancel_event: threading.Event):
        def emit(event: Any) -> None:
            on_event(
                EngineEvent(
                    "progress",
                    stage=str(getattr(event, "stage", "engine")),
                    progress=float(getattr(event, "fraction", 0.0)),
                    message=str(getattr(event, "message", "")),
                    engine_job_id=job_id,
                    payload=event.to_dict() if hasattr(event, "to_dict") else None,
                )
            )

        return emit

    def _execute_with_cancel(self, engine: Any, job_id: str, action: Callable[[], Any], cancel_event: threading.Event) -> Any:
        stop_watcher = threading.Event()

        def request_engine_cancel() -> None:
            while not stop_watcher.wait(0.1):
                if cancel_event.is_set():
                    try:
                        engine.cancel_job(job_id)
                    except Exception:
                        pass
                    return

        watcher = threading.Thread(target=request_engine_cancel, name="publikclip-facade-cancel", daemon=True)
        watcher.start()
        try:
            return action()
        finally:
            stop_watcher.set()
            watcher.join(timeout=1)

    def run(
        self,
        source: str,
        job_dir: Path,
        options: dict[str, Any],
        resume_engine_job_id: str | None,
        on_event: EventCallback,
        cancel_event: threading.Event,
    ) -> dict[str, Any]:
        del job_dir  # The public facade owns its checkpoint root under PUBLIKCLIP_HOME.
        with self._lock, self._runtime():
            try:
                engine = self._load()
                settings = self._settings(options)
                if resume_engine_job_id:
                    engine_job_id = resume_engine_job_id
                    on_event(EngineEvent("job", engine_job_id=engine_job_id))
                    results = self._execute_with_cancel(
                        engine,
                        engine_job_id,
                        lambda: engine.resume_job(
                            engine_job_id,
                            settings=settings,
                            on_progress=self._progress_callback(engine_job_id, on_event, cancel_event),
                        ),
                        cancel_event,
                    )
                else:
                    source_type = "url" if source.startswith(("http://", "https://")) else "file"
                    reference = engine.create_job(source, settings=settings, source_type=source_type)
                    engine_job_id = str(reference.id)
                    on_event(EngineEvent("job", engine_job_id=engine_job_id, payload=reference.to_dict()))
                    results = self._execute_with_cancel(
                        engine,
                        engine_job_id,
                        lambda: engine.start_job(
                            engine_job_id,
                            on_progress=self._progress_callback(engine_job_id, on_event, cancel_event),
                        ),
                        cancel_event,
                    )
                payload = results.to_dict() if hasattr(results, "to_dict") else dict(results)
                payload["ok"] = True
                payload["job_id"] = engine_job_id
                return payload
            except Exception as error:  # noqa: BLE001 - only stable errors leave the adapter
                raise self._convert_error(error) from error

    def render_clip(
        self,
        engine_job_id: str,
        clip: int,
        job_dir: Path,
        on_event: EventCallback,
        cancel_event: threading.Event,
    ) -> dict[str, Any]:
        del job_dir
        with self._lock, self._runtime():
            try:
                engine = self._load()
                result = self._execute_with_cancel(
                    engine,
                    engine_job_id,
                    lambda: engine.render_clip(
                        engine_job_id,
                        clip,
                        on_progress=self._progress_callback(engine_job_id, on_event, cancel_event),
                    ),
                    cancel_event,
                )
                return result.to_dict() if hasattr(result, "to_dict") else dict(result)
            except Exception as error:  # noqa: BLE001 - stable adapter boundary
                raise self._convert_error(error) from error


class SubprocessPublikclipEngine:
    """Explicit legacy adapter for a configured ``publikclip --jsonl`` command."""

    def __init__(self, pipeline_dir: Path, binary: str = "", env: dict[str, str] | None = None):
        self.pipeline_dir = pipeline_dir.resolve()
        self.binary = binary.strip()
        self.extra_env = env or {}

    def _base_command(self) -> list[str]:
        if self.binary:
            return [self.binary, "--jsonl"]
        uv = shutil.which("uv")
        if uv and (self.pipeline_dir / "pyproject.toml").is_file():
            return [uv, "run", "--project", str(self.pipeline_dir), "publikclip", "--jsonl"]
        return [sys.executable, "-m", "publikclip_pipeline.cli", "--jsonl"]

    def available(self) -> tuple[bool, str]:
        if self.binary:
            path = Path(self.binary)
            if path.is_file() and os.access(path, os.X_OK):
                return True, "Configured publikclip executable is available."
            return False, "Configured publikclip executable is missing or not executable."
        cli = self.pipeline_dir / "publikclip_pipeline" / "cli.py"
        if cli.is_file():
            return True, "publikclip pipeline CLI is available."
        return False, "publikclip pipeline CLI is not available."

    def _environment(self, job_dir: Path) -> dict[str, str]:
        environment = os.environ.copy()
        environment.update(self.extra_env)
        environment["PUBLIKCLIP_HOME"] = str(job_dir.parent.parent)
        environment["PUBLIKCLIP_DISABLE_LOCAL_SECRETS"] = "1"
        environment["PYTHONPATH"] = f"{self.pipeline_dir}{os.pathsep}{environment.get('PYTHONPATH', '')}".rstrip(os.pathsep)
        return environment

    @staticmethod
    def _emit_line(line: str, on_event: EventCallback) -> dict[str, Any] | None:
        try:
            payload = json.loads(line)
        except json.JSONDecodeError:
            return None
        kind = str(payload.get("event") or "log")
        if kind == "progress":
            fraction = payload.get("fraction")
            try:
                fraction = max(0.0, min(1.0, float(fraction))) if fraction is not None else None
            except (TypeError, ValueError):
                fraction = None
            on_event(EngineEvent("progress", str(payload.get("stage") or "engine"), fraction, str(payload.get("message") or ""), payload=payload))
        elif kind == "job":
            on_event(EngineEvent("job", engine_job_id=str(payload.get("job_id") or ""), payload=payload))
        elif kind == "result":
            on_event(EngineEvent("result", engine_job_id=str(payload.get("job_id") or "") or None, payload=payload))
        else:
            on_event(EngineEvent("log", message=safe_engine_message(line[-500:]), payload=payload))
        return payload if kind == "result" else None

    def _execute(self, command: list[str], cwd: Path, job_dir: Path, on_event: EventCallback, cancel_event: threading.Event) -> dict[str, Any]:
        try:
            process = subprocess.Popen(command, cwd=str(cwd), env=self._environment(job_dir), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
        except FileNotFoundError as error:
            raise EngineError("publikclip executable could not be started", "ENGINE_START_FAILED", True) from error
        final: dict[str, Any] | None = None
        stop_watcher = threading.Event()

        def terminate_when_cancelled() -> None:
            while not stop_watcher.wait(0.1):
                if cancel_event.is_set() and process.poll() is None:
                    process.terminate()
                    return

        watcher = threading.Thread(target=terminate_when_cancelled, name="publikclip-cancel", daemon=True)
        watcher.start()
        assert process.stdout is not None
        try:
            for line in process.stdout:
                result = self._emit_line(line, on_event)
                if result is not None:
                    final = result
        finally:
            stop_watcher.set()
            watcher.join(timeout=1)
            process.stdout.close()
        return_code = process.wait()
        if cancel_event.is_set():
            raise EngineError("Engine execution was cancelled", "JOB_CANCELLED", False)
        if return_code != 0 or not final or not final.get("ok"):
            message = str((final or {}).get("error") or f"publikclip exited with code {return_code}")
            code = str((final or {}).get("error_code") or "ENGINE_FAILED")
            raise EngineError(message, code, True)
        return final

    def run(self, source: str, job_dir: Path, options: dict[str, Any], resume_engine_job_id: str | None, on_event: EventCallback, cancel_event: threading.Event) -> dict[str, Any]:
        command = self._base_command()
        if resume_engine_job_id:
            command += ["resume", resume_engine_job_id]
        else:
            command += ["run", source]
        for name in ("llm", "captions", "mode", "camera"):
            value = options.get(name)
            if value:
                command += [f"--{name}", str(value)]
        cwd = self.pipeline_dir.parent if self.pipeline_dir.parent.is_dir() else job_dir
        return self._execute(command, cwd, job_dir, on_event, cancel_event)

    def render_clip(self, engine_job_id: str, clip: int, job_dir: Path, on_event: EventCallback, cancel_event: threading.Event) -> dict[str, Any]:
        command = self._base_command() + ["edit", "render-clip", engine_job_id, str(clip)]
        return self._execute(command, self.pipeline_dir.parent, job_dir, on_event, cancel_event)


class UnavailableEngine:
    """Explicit adapter used when the public engine checkout is absent."""

    def available(self) -> tuple[bool, str]:
        return False, "publikclip engine is not available; install or configure the engine command."

    def run(self, source: str, job_dir: Path, options: dict[str, Any], resume_engine_job_id: str | None, on_event: EventCallback, cancel_event: threading.Event) -> dict[str, Any]:
        raise EngineError("publikclip engine is unavailable", "ENGINE_UNAVAILABLE", False)

    def render_clip(self, engine_job_id: str, clip: int, job_dir: Path, on_event: EventCallback, cancel_event: threading.Event) -> dict[str, Any]:
        raise EngineError("publikclip engine is unavailable", "ENGINE_UNAVAILABLE", False)
