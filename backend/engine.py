"""Backend boundary for the public PUBLIKCLIP Engine facade.

The backend owns HTTP-facing job state and worker scheduling.  This module is
its only integration boundary to the processing runtime: production uses
``PipelineFacadeEngine`` and imports only the public
``publikclip_pipeline.engine`` package.  The subprocess adapter remains for
legacy launchers and isolated deployments, but is no longer the default path.
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
from typing import Any, Callable, Mapping, Protocol


@dataclass(frozen=True)
class EngineEvent:
    kind: str
    stage: str | None = None
    progress: float | None = None
    message: str | None = None
    engine_job_id: str | None = None
    payload: dict[str, Any] | None = None


class EngineError(RuntimeError):
    """Backend-safe projection of an engine failure."""

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


class PipelineFacadeEngine:
    """Adapter from Backend workers to the public processing-engine facade.

    No stage, queue, checkpoint, or renderer module is imported here.  The
    processing package remains responsible for orchestration, SQLite state,
    checkpoint validity, resume semantics, progress persistence, and artifact
    rendering.  This adapter translates public records/events to the legacy
    Backend worker protocol while exposing the same lifecycle operations for
    newer callers.
    """

    def __init__(
        self,
        pipeline_dir: Path,
        engine_home: Path,
        facade: Any | None = None,
    ) -> None:
        self.pipeline_dir = pipeline_dir.resolve()
        self.engine_home = engine_home.resolve()
        self.engine_home.mkdir(parents=True, exist_ok=True)
        self._facade = facade
        self._facade_lock = threading.RLock()
        self._configure_runtime()

    def _configure_runtime(self) -> None:
        """Point the public pipeline at the Backend's isolated data root."""
        os.environ["PUBLIKCLIP_HOME"] = str(self.engine_home)
        os.environ["PUBLIKCLIP_DISABLE_LOCAL_SECRETS"] = "1"
        if str(self.pipeline_dir) not in sys.path:
            sys.path.insert(0, str(self.pipeline_dir))

    def _public_facade(self) -> Any:
        if self._facade is None:
            self._configure_runtime()
            from publikclip_pipeline.engine import PipelineEngine

            self._facade = PipelineEngine()
        return self._facade

    @staticmethod
    def _convert_error(error: Exception) -> EngineError:
        code = str(getattr(error, "code", "ENGINE_FAILED"))
        message = str(getattr(error, "safe_message", str(error)))
        recoverable = bool(getattr(error, "recoverable", True))
        return EngineError(message, code, recoverable)

    def available(self) -> tuple[bool, str]:
        if not (self.pipeline_dir / "publikclip_pipeline" / "engine" / "__init__.py").is_file():
            return False, "publikclip engine facade is not available."
        return True, "publikclip engine facade is available."

    def create_job(self, source: str, settings: Mapping[str, Any] | Any | None = None, *, source_type: str | None = None) -> Any:
        try:
            with self._facade_lock:
                self._configure_runtime()
                return self._public_facade().create_job(source, settings, source_type=source_type)
        except Exception as error:  # noqa: BLE001 - normalize public boundary
            if isinstance(error, EngineError):
                raise
            raise self._convert_error(error) from error

    def start_job(self, job_id: str, on_progress: Callable[[Any], None] | None = None) -> Any:
        try:
            with self._facade_lock:
                self._configure_runtime()
                facade = self._public_facade()
            # Do not hold the initialization lock while a stage is running;
            # the cancellation watcher must be able to call cancel_job().
            return facade.start_job(job_id, on_progress=on_progress)
        except Exception as error:  # noqa: BLE001 - normalize public boundary
            if isinstance(error, EngineError):
                raise
            raise self._convert_error(error) from error

    def get_status(self, job_id: str) -> Any:
        try:
            with self._facade_lock:
                self._configure_runtime()
                return self._public_facade().get_status(job_id)
        except Exception as error:  # noqa: BLE001 - normalize public boundary
            if isinstance(error, EngineError):
                raise
            raise self._convert_error(error) from error

    def get_progress(self, job_id: str) -> Mapping[str, Any]:
        try:
            with self._facade_lock:
                self._configure_runtime()
                return self._public_facade().get_progress(job_id)
        except Exception as error:  # noqa: BLE001 - normalize public boundary
            if isinstance(error, EngineError):
                raise
            raise self._convert_error(error) from error

    def cancel_job(self, job_id: str) -> Any:
        try:
            with self._facade_lock:
                self._configure_runtime()
                return self._public_facade().cancel_job(job_id)
        except Exception as error:  # noqa: BLE001 - normalize public boundary
            if isinstance(error, EngineError):
                raise
            raise self._convert_error(error) from error

    def resume_job(self, job_id: str, settings: Mapping[str, Any] | Any | None = None, on_progress: Callable[[Any], None] | None = None) -> Any:
        try:
            with self._facade_lock:
                self._configure_runtime()
                facade = self._public_facade()
            return facade.resume_job(job_id, settings=settings, on_progress=on_progress)
        except Exception as error:  # noqa: BLE001 - normalize public boundary
            if isinstance(error, EngineError):
                raise
            raise self._convert_error(error) from error

    def get_results(self, job_id: str) -> Any:
        try:
            with self._facade_lock:
                self._configure_runtime()
                return self._public_facade().get_results(job_id)
        except Exception as error:  # noqa: BLE001 - normalize public boundary
            if isinstance(error, EngineError):
                raise
            raise self._convert_error(error) from error

    def render_clip(self, job_id: str, clip: int, on_progress: Callable[[Any], None] | None = None) -> Mapping[str, Any]:
        try:
            with self._facade_lock:
                self._configure_runtime()
                artifact = self._public_facade().render_clip(job_id, clip, on_progress=on_progress)
                return self._artifact_entry(artifact, clip)
        except Exception as error:  # noqa: BLE001 - normalize public boundary
            if isinstance(error, EngineError):
                raise
            raise self._convert_error(error) from error

    @staticmethod
    def _artifact_entry(artifact: Mapping[str, Any], clip_index: int) -> dict[str, Any]:
        entry = {key: value for key, value in artifact.items() if key not in {"path", "ass"}}
        entry["clip"] = int(entry.get("clip", clip_index))
        raw_path = artifact.get("path")
        if isinstance(raw_path, str) and raw_path:
            path = Path(raw_path)
            entry["filename"] = path.name
            try:
                entry["bytes"] = path.stat().st_size
                entry["download_ready"] = path.is_file() and path.suffix.lower() == ".mp4"
            except OSError:
                entry["download_ready"] = False
        return entry

    @staticmethod
    def _public_settings(options: Mapping[str, Any]) -> dict[str, Any]:
        """Translate Backend option aliases to the public Settings mapping."""
        settings = dict(options)
        aliases = {"llm": "llm_mode", "captions": "caption_preset", "mode": "processing_mode"}
        for old_name, public_name in aliases.items():
            if old_name in settings and public_name not in settings:
                settings[public_name] = settings[old_name]
        camera = settings.get("camera")
        if isinstance(camera, str):
            settings["camera"] = {"speaker_change": camera}
        return settings

    @classmethod
    def _result_payload(cls, results: Any) -> dict[str, Any]:
        payload = results.to_dict() if hasattr(results, "to_dict") else dict(results)
        raw_artifacts = payload.get("artifacts") if isinstance(payload.get("artifacts"), list) else []
        safe_artifacts = [
            cls._artifact_entry(item, index)
            for index, item in enumerate(raw_artifacts)
            if isinstance(item, dict)
        ]
        payload["artifacts"] = safe_artifacts
        render = payload.get("render")
        if isinstance(render, dict):
            render = dict(render)
            raw_outputs = render.get("outputs") if isinstance(render.get("outputs"), list) else []
            render["outputs"] = [
                cls._artifact_entry(item, index)
                for index, item in enumerate(raw_outputs)
                if isinstance(item, dict)
            ]
            payload["render"] = render
        score = payload.get("score") if isinstance(payload.get("score"), dict) else {}
        scored_clips = score.get("clips") if isinstance(score.get("clips"), list) else []
        artifacts = {
            int(item.get("clip", index)): item
            for index, item in enumerate(safe_artifacts)
            if str(item.get("clip", index)).lstrip("-").isdigit()
        }
        clips: list[dict[str, Any]] = []
        for index, score_clip in enumerate(scored_clips):
            entry = dict(score_clip) if isinstance(score_clip, dict) else {"score": score_clip}
            entry["clip"] = index
            artifact = artifacts.get(index)
            if artifact is not None:
                entry.update(cls._artifact_entry(artifact, index))
            clips.append(entry)
        payload["clips"] = clips
        return {"ok": True, "job_id": payload.get("job_id"), "results": payload, "clips": clips}

    @staticmethod
    def _progress_event(job_id: str, on_event: EventCallback) -> Callable[[Any], None]:
        def emit(event: Any) -> None:
            on_event(
                EngineEvent(
                    "progress",
                    stage=getattr(event, "stage", None),
                    progress=getattr(event, "fraction", None),
                    message=getattr(event, "message", None),
                    engine_job_id=job_id,
                    payload=event.to_dict() if hasattr(event, "to_dict") else None,
                )
            )

        return emit

    def run(
        self,
        source: str,
        job_dir: Path,
        options: dict[str, Any],
        resume_engine_job_id: str | None,
        on_event: EventCallback,
        cancel_event: threading.Event,
    ) -> dict[str, Any]:
        """Legacy worker entry point implemented through the public facade."""
        del job_dir  # The public facade owns its configured engine home.
        facade_job_id: str | None = resume_engine_job_id
        if facade_job_id is None:
            job = self.create_job(
                source,
                self._public_settings(options),
                source_type="url" if source.startswith(("http://", "https://")) else "file",
            )
            facade_job_id = str(job.id)
        on_event(EngineEvent("job", engine_job_id=facade_job_id))

        stop_watcher = threading.Event()

        def request_facade_cancel() -> None:
            while not stop_watcher.wait(0.05):
                if cancel_event.is_set():
                    try:
                        self.cancel_job(facade_job_id)
                    except Exception:
                        pass
                    return

        watcher = threading.Thread(target=request_facade_cancel, name="publikclip-facade-cancel", daemon=True)
        watcher.start()
        progress = self._progress_event(facade_job_id, on_event)
        try:
            results = self.resume_job(facade_job_id, on_progress=progress) if resume_engine_job_id else self.start_job(facade_job_id, on_progress=progress)
            return self._result_payload(results)
        except EngineError:
            raise
        except Exception as error:  # noqa: BLE001 - defensive boundary
            raise self._convert_error(error) from error
        finally:
            stop_watcher.set()
            watcher.join(timeout=1)


class SubprocessPublikclipEngine:
    """Compatibility adapter for the existing ``publikclip --jsonl`` command."""

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
        final = self._execute(command, cwd, job_dir, on_event, cancel_event)
        engine_job_id = str(final.get("job_id") or "")
        if final.get("ok") and engine_job_id:
            try:
                facade = PipelineFacadeEngine(self.pipeline_dir, job_dir.parent.parent)
                final.update(self._public_result_from_facade(facade.get_results(engine_job_id)))
            except Exception:
                # The CLI result remains valid for legacy callers even when a
                # post-run result projection is unavailable.
                pass
        return final

    @staticmethod
    def _public_result_from_facade(results: Any) -> dict[str, Any]:
        return PipelineFacadeEngine._result_payload(results)

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


__all__ = ["Engine", "EngineError", "EngineEvent", "PipelineFacadeEngine", "SubprocessPublikclipEngine", "UnavailableEngine"]
