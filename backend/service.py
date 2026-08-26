"""Job lifecycle orchestration for the private backend."""
from __future__ import annotations

import json
import threading
from concurrent.futures import Future, ThreadPoolExecutor
from pathlib import Path
from typing import Any

from .db import Store
from .engine import Engine, EngineError, EngineEvent
from .storage import Storage


STAGE_TO_STATE = {
    "prepare": "PREPARING",
    "download": "DOWNLOADING",
    "ingest": "INGESTING",
    "asr": "TRANSCRIBING",
    "transcrib": "TRANSCRIBING",
    "diariz": "DIARIZING",
    "analyz": "ANALYZING",
    "candidate": "CANDIDATES_READY",
    "scor": "SCORING",
    "edit": "EDITING",
    "render": "RENDERING",
    "final": "FINALIZING",
}


class JobManager:
    def __init__(self, store: Store, storage: Storage, engine: Engine, max_workers: int = 1):
        self.store = store
        self.storage = storage
        self.engine = engine
        self.executor = ThreadPoolExecutor(max_workers=max_workers, thread_name_prefix="publikclip-job")
        self.lock = threading.Lock()
        self.futures: dict[str, Future[Any]] = {}
        self.cancel_events: dict[str, threading.Event] = {}
        self.started = False

    def start(self) -> None:
        self.store.init()
        self.store.mark_running_jobs_interrupted()
        self.started = True
        for row in self.store.list_jobs(limit=100, status="queued"):
            self.submit(str(row["id"]))

    def stop(self) -> None:
        self.executor.shutdown(wait=False, cancel_futures=False)
        self.started = False

    def submit(self, job_id: str) -> bool:
        with self.lock:
            if job_id in self.futures and not self.futures[job_id].done():
                return True
            cancel_event = threading.Event()
            self.cancel_events[job_id] = cancel_event
            future = self.executor.submit(self._run, job_id, cancel_event)
            self.futures[job_id] = future
            return True

    def cancel(self, job_id: str) -> dict[str, Any] | None:
        row = self.store.request_cancel(job_id)
        if row is None:
            return None
        with self.lock:
            event = self.cancel_events.get(job_id)
            if event:
                event.set()
        return self.store.job_dict(self.store.get_job(job_id))

    def _event_handler(self, job_id: str, event: EngineEvent) -> None:
        if event.kind == "job" and event.engine_job_id:
            self.store.transition(job_id, engine_job_id=event.engine_job_id, message="Engine job created")
            return
        if event.kind == "progress":
            stage_text = (event.stage or "engine").lower()
            state = next((value for key, value in STAGE_TO_STATE.items() if key in stage_text), "ANALYZING")
            self.store.transition(
                job_id,
                status="running",
                state=state,
                stage=event.stage or "engine",
                progress=event.progress if event.progress is not None else 0,
                message=event.message or "Processing",
            )
        elif event.kind == "log" and event.message:
            self.store.transition(job_id, message=event.message[-500:])

    def _collect_result(self, job_id: str, engine_job_id: str | None, final: dict[str, Any]) -> dict[str, Any]:
        result = final.get("results") if isinstance(final.get("results"), dict) else dict(final)
        result["engine_job_id"] = engine_job_id or final.get("job_id")
        clips = result.get("clips")
        if not isinstance(clips, list):
            clips = []
        # The existing engine writes stage checkpoints. Read them as data only;
        # no scoring or editing logic is duplicated here.
        if engine_job_id:
            engine_dir = self.storage.jobs / engine_job_id
            render_file = engine_dir / "render.json"
            if render_file.is_file():
                try:
                    render = json.loads(render_file.read_text(encoding="utf-8")).get("data", {})
                    outputs = render.get("outputs", []) if isinstance(render, dict) else []
                    for index, output in enumerate(outputs):
                        if not isinstance(output, dict):
                            continue
                        raw = Path(str(output.get("path", "")))
                        if raw.is_file() and raw.suffix.lower() == ".mp4":
                            clips.append({"clip": output.get("clip", index), "filename": raw.name, "bytes": raw.stat().st_size, "download_ready": True, **{k: v for k, v in output.items() if k != "path"}})
                except (OSError, ValueError, TypeError, json.JSONDecodeError):
                    pass
        result["clips"] = clips
        result["job_id"] = job_id
        return result

    def _run(self, job_id: str, cancel_event: threading.Event) -> None:
        try:
            row = self.store.get_job(job_id)
            if row is None:
                return
            if row["cancel_requested"]:
                self.store.transition(job_id, status="cancelled", state="CANCELLED", stage="cancelled", error_code="JOB_CANCELLED", error_message="Job cancellation was requested", resume_available=bool(row["engine_job_id"]))
                return
            self.store.transition(job_id, status="running", state="PREPARING", stage="preparing", message="Starting publikclip", progress=0)
            options = json.loads(row["options_json"])
            source = str(row["source"])
            if row["source_upload_id"]:
                upload = self.store.get_upload(str(row["source_upload_id"]))
                if upload is None:
                    raise EngineError("Source upload was not found", "UPLOAD_NOT_FOUND", False)
                source = str(upload["path"])
            job_dir = self.storage.job_dir(job_id)
            final = self.engine.run(source, job_dir, options, row["engine_job_id"], lambda event: self._event_handler(job_id, event), cancel_event)
            engine_job_id = str(final.get("job_id") or self.store.get_job(job_id)["engine_job_id"] or "") or None
            result = self._collect_result(job_id, engine_job_id, final)
            self.store.transition(
                job_id,
                status="completed",
                state="COMPLETED",
                stage="completed",
                progress=1,
                message="Clips ready",
                engine_job_id=engine_job_id,
                results_json=json.dumps(result, ensure_ascii=False),
                error_code=None,
                error_message=None,
                resume_available=0,
            )
        except EngineError as error:
            if error.code == "JOB_CANCELLED" or cancel_event.is_set():
                self.store.transition(job_id, status="cancelled", state="CANCELLED", stage="cancelled", message="Job cancelled", error_code="JOB_CANCELLED", error_message=str(error), resume_available=bool(self.store.get_job(job_id)["engine_job_id"]))
            else:
                self.store.transition(job_id, status="failed", state="FAILED", stage="failed", message="Engine failed", error_code=error.code, error_message=str(error), resume_available=int(error.recoverable),)
        except Exception as error:  # noqa: BLE001 - persist safe worker failure
            current = self.store.get_job(job_id)
            if cancel_event.is_set() or (current is not None and current["cancel_requested"]):
                self.store.transition(job_id, status="cancelled", state="CANCELLED", stage="cancelled", message="Job cancelled", error_code="JOB_CANCELLED", error_message="Job cancellation was requested", resume_available=bool(current and current["engine_job_id"]))
            else:
                self.store.transition(job_id, status="failed", state="FAILED", stage="failed", message="Backend worker failed", error_code="BACKEND_WORKER_FAILED", error_message=str(error), resume_available=1)
        finally:
            with self.lock:
                self.cancel_events.pop(job_id, None)

    def render_clip(self, job_id: str, clip: int) -> dict[str, Any]:
        row = self.store.get_job(job_id)
        if row is None:
            raise KeyError(job_id)
        if row["status"] != "completed" or not row["engine_job_id"]:
            raise ValueError("Job is not completed or has no engine job id")
        cancel_event = threading.Event()
        engine_job_id = str(row["engine_job_id"])
        result = self.engine.render_clip(engine_job_id, clip, self.storage.jobs / engine_job_id, lambda event: self._event_handler(job_id, event), cancel_event)
        current = self.store.job_dict(self.store.get_job(job_id))
        outputs = current.get("results", {}).get("clips", []) if isinstance(current.get("results"), dict) else []
        rendered = result.get("output") or result.get("result") or result
        if isinstance(rendered, dict):
            rendered = {"clip": clip, **rendered}
        outputs = [item for item in outputs if item.get("clip") != clip] + [rendered]
        results = current.get("results") or {"job_id": job_id}
        results["clips"] = outputs
        self.store.transition(job_id, results_json=json.dumps(results, ensure_ascii=False))
        return rendered
