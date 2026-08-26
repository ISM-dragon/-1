"""publikclip integration boundary.

Only this module knows how to launch the existing engine. The algorithms remain
owned by ``pipeline/`` and can be replaced later without changing the HTTP API.
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Protocol


@dataclass(frozen=True)
class EngineEvent:
    kind: str
    stage: str | None = None
    progress: float | None = None
    message: str | None = None
    engine_job_id: str | None = None
    payload: dict[str, Any] | None = None


class EngineError(RuntimeError):
    def __init__(self, message: str, code: str = "ENGINE_FAILED", recoverable: bool = True):
        super().__init__(message)
        self.code = code
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


class SubprocessPublikclipEngine:
    """Adapter for the existing ``publikclip --jsonl`` command."""

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
            on_event(EngineEvent("log", message=line[-500:], payload=payload))
        return payload if kind == "result" else None

    def _execute(self, command: list[str], cwd: Path, job_dir: Path, on_event: EventCallback, cancel_event: threading.Event) -> dict[str, Any]:
        try:
            process = subprocess.Popen(
                command,
                cwd=str(cwd),
                env=self._environment(job_dir),
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                bufsize=1,
            )
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
    """Explicit temporary adapter used when the engine checkout is absent."""

    def available(self) -> tuple[bool, str]:
        return False, "publikclip engine is not available; install or configure the engine command."

    def run(self, source: str, job_dir: Path, options: dict[str, Any], resume_engine_job_id: str | None, on_event: EventCallback, cancel_event: threading.Event) -> dict[str, Any]:
        raise EngineError("publikclip engine is unavailable", "ENGINE_UNAVAILABLE", False)

    def render_clip(self, engine_job_id: str, clip: int, job_dir: Path, on_event: EventCallback, cancel_event: threading.Event) -> dict[str, Any]:
        raise EngineError("publikclip engine is unavailable", "ENGINE_UNAVAILABLE", False)
