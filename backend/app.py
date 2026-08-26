"""Private, single-device Android API for publikclip."""
from __future__ import annotations

import asyncio
import hashlib
import hmac
import ipaddress
import os
import secrets
import shutil
import socket
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel, Field

from .db import Store
from .engine import Engine, PipelineFacadeEngine, SubprocessPublikclipEngine, UnavailableEngine
from .service import JobManager
from .storage import Storage, StorageError


class Settings:
    def __init__(self) -> None:
        root = Path(os.getenv("PRIVATE_BACKEND_ROOT", str(Path(__file__).resolve().parent / "data")))
        self.root = root
        self.db_path = Path(os.getenv("PRIVATE_BACKEND_DB", str(root / "backend.sqlite3")))
        self.storage_root = Path(os.getenv("PRIVATE_BACKEND_STORAGE", str(root / "files")))
        self.max_upload_bytes = max(1, int(os.getenv("PRIVATE_BACKEND_MAX_UPLOAD_BYTES", str(2 * 1024 * 1024 * 1024))))
        self.token = os.getenv("PRIVATE_BACKEND_TOKEN", "").strip()
        self.device_id = os.getenv("PRIVATE_BACKEND_DEVICE_ID", "").strip()
        self.pipeline_dir = Path(os.getenv("PRIVATE_BACKEND_PIPELINE_DIR", str(Path(__file__).resolve().parent.parent / "pipeline")))
        self.engine_bin = os.getenv("PRIVATE_BACKEND_ENGINE_BIN", "").strip()
        self.allow_insecure_local = os.getenv("PRIVATE_BACKEND_ALLOW_INSECURE_LOCAL", "false").lower() in {"1", "true", "yes"}


class JobCreate(BaseModel):
    source: str | None = Field(default=None, max_length=2000)
    upload_id: str | None = Field(default=None, max_length=200)
    options: dict[str, Any] = Field(default_factory=dict)
    idempotency_key: str | None = Field(default=None, min_length=8, max_length=180)


class RenderRequest(BaseModel):
    options: dict[str, Any] = Field(default_factory=dict)


def public_source(value: str) -> str:
    parsed = urlparse(value)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise HTTPException(status_code=422, detail="source must be an HTTP or HTTPS URL", headers={"X-Error-Code": "INVALID_SOURCE"})
    host = parsed.hostname.lower().rstrip(".")
    if host in {"localhost", "localhost.localdomain", "0.0.0.0", "::1"}:
        raise HTTPException(status_code=422, detail="private network sources are not allowed", headers={"X-Error-Code": "INVALID_SOURCE"})
    try:
        addresses = {host}
        ipaddress.ip_address(host)
    except ValueError:
        try:
            addresses = {entry[4][0] for entry in socket.getaddrinfo(host, parsed.port or 443, type=socket.SOCK_STREAM)}
        except socket.gaierror as error:
            raise HTTPException(status_code=422, detail="source hostname could not be resolved", headers={"X-Error-Code": "INVALID_SOURCE"}) from error
    for address in addresses:
        try:
            parsed_address = ipaddress.ip_address(address)
        except ValueError:
            continue
        if parsed_address.is_private or parsed_address.is_loopback or parsed_address.is_link_local or parsed_address.is_reserved or parsed_address.is_multicast:
            raise HTTPException(status_code=422, detail="private network sources are not allowed", headers={"X-Error-Code": "INVALID_SOURCE"})
    return value


def error_payload(request: Request, code: str, message: str, retryable: bool = False) -> dict[str, Any]:
    return {"error": {"code": code, "message": message, "request_id": getattr(request.state, "request_id", None), "retryable": retryable}}


def create_app(settings: Settings | None = None, engine: Engine | None = None) -> FastAPI:
    config = settings or Settings()
    store = Store(config.db_path)
    storage = Storage(config.storage_root, config.max_upload_bytes)
    if engine is not None:
        selected_engine = engine
    elif config.pipeline_dir.exists() and not config.engine_bin:
        selected_engine = PipelineFacadeEngine(config.pipeline_dir, config.storage_root)
    elif config.pipeline_dir.exists():
        selected_engine = SubprocessPublikclipEngine(config.pipeline_dir, config.engine_bin)
    else:
        selected_engine = UnavailableEngine()
    manager = JobManager(store, storage, selected_engine)

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        manager.start()
        yield
        manager.stop()

    app = FastAPI(title="Private publikclip Backend", version="1.0.0", lifespan=lifespan)
    app.state.store = store
    app.state.storage = storage
    app.state.manager = manager
    app.state.settings = config
    app.state.engine = selected_engine

    @app.middleware("http")
    async def request_context(request: Request, call_next):
        request_id = request.headers.get("X-Request-ID", "").strip()[:120] or f"req_{secrets.token_urlsafe(10)}"
        request.state.request_id = request_id
        response = await call_next(request)
        response.headers["X-Request-ID"] = request_id
        return response

    @app.exception_handler(HTTPException)
    async def http_error(request: Request, exc: HTTPException):
        code = (exc.headers or {}).get("X-Error-Code", "HTTP_ERROR")
        retryable = exc.status_code in {429, 502, 503, 504}
        return JSONResponse(status_code=exc.status_code, content=error_payload(request, code, str(exc.detail), retryable))

    @app.exception_handler(RequestValidationError)
    async def validation_error(request: Request, exc: RequestValidationError):
        return JSONResponse(status_code=422, content=error_payload(request, "VALIDATION_ERROR", "Request validation failed", False))

    async def authenticate(request: Request) -> None:
        if config.allow_insecure_local and request.client and request.client.host in {"127.0.0.1", "::1", "testclient"}:
            return
        if not config.token:
            raise HTTPException(status_code=503, detail="Private backend token is not configured", headers={"X-Error-Code": "AUTH_NOT_CONFIGURED"})
        supplied = request.headers.get("Authorization", "")
        if not hmac.compare_digest(supplied, f"Bearer {config.token}"):
            raise HTTPException(status_code=401, detail="Invalid bearer token", headers={"X-Error-Code": "UNAUTHORIZED"})
        supplied_device = request.headers.get("X-Device-ID", "").strip()
        if not supplied_device or len(supplied_device) > 200:
            raise HTTPException(status_code=401, detail="X-Device-ID is required", headers={"X-Error-Code": "DEVICE_REQUIRED"})
        device_hash = hashlib.sha256(supplied_device.encode("utf-8")).hexdigest()
        if config.device_id and not hmac.compare_digest(supplied_device, config.device_id):
            raise HTTPException(status_code=403, detail="Device is not allowed", headers={"X-Error-Code": "DEVICE_NOT_ALLOWED"})
        if not store.bind_device(device_hash):
            raise HTTPException(status_code=403, detail="A different device is already bound to this private backend", headers={"X-Error-Code": "DEVICE_MISMATCH"})

    def job_response(row: Any) -> dict[str, Any]:
        return store.job_dict(row)

    def clip_response(job_id: str, row: Any) -> list[dict[str, Any]]:
        payload = store.job_dict(row)
        results = payload.get("results") or {}
        clips = results.get("clips", []) if isinstance(results, dict) else []
        output: list[dict[str, Any]] = []
        for index, item in enumerate(clips):
            if not isinstance(item, dict):
                continue
            clip = dict(item)
            clip_id = clip.get("clip", index)
            clip["clip"] = clip_id
            clip["download_url"] = f"/jobs/{job_id}/clips/{clip_id}/download"
            output.append(clip)
        return output

    @app.get("/health")
    async def health() -> dict[str, Any]:
        available, message = selected_engine.available()
        return {"ok": True, "service": "private-backend", "api_version": "1", "engine": {"available": available, "message": message}, "auth_required": not config.allow_insecure_local}

    @app.post("/uploads", dependencies=[Depends(authenticate)])
    async def upload(request: Request, x_filename: str | None = Header(default=None, alias="X-Filename"), content_type: str | None = Header(default=None, alias="Content-Type")) -> JSONResponse:
        filename = x_filename or "video.mp4"
        upload_id = storage.new_upload_id()
        target = storage.upload_path(upload_id, filename)
        size = 0
        try:
            with target.open("wb") as output:
                async for chunk in request.stream():
                    if not chunk:
                        continue
                    size += len(chunk)
                    if size > config.max_upload_bytes:
                        raise HTTPException(status_code=413, detail="Video exceeds configured upload limit", headers={"X-Error-Code": "UPLOAD_TOO_LARGE"})
                    output.write(chunk)
            if size == 0:
                raise HTTPException(status_code=400, detail="Video upload is empty", headers={"X-Error-Code": "EMPTY_UPLOAD"})
            upload_row = {"id": upload_id, "filename": Path(filename).name, "content_type": content_type or storage.content_type(target), "bytes": size, "path": str(target), "created_at": store.now()}
            store.create_upload(upload_row)
            return JSONResponse(status_code=201, content={**upload_row, "path": None, "source": f"upload:{upload_id}"})
        except Exception:
            target.unlink(missing_ok=True)
            shutil.rmtree(target.parent, ignore_errors=True)
            raise

    @app.post("/jobs", dependencies=[Depends(authenticate)])
    async def create_job(payload: JobCreate, request: Request, idempotency_header: str | None = Header(default=None, alias="Idempotency-Key")) -> JSONResponse:
        if bool(payload.source) == bool(payload.upload_id):
            raise HTTPException(status_code=422, detail="Provide exactly one of source or upload_id", headers={"X-Error-Code": "INVALID_JOB_SOURCE"})
        source_upload_id = payload.upload_id
        source = payload.source or ""
        if source_upload_id:
            upload_row = store.get_upload(source_upload_id)
            if upload_row is None:
                raise HTTPException(status_code=404, detail="Upload not found", headers={"X-Error-Code": "UPLOAD_NOT_FOUND"})
            source = f"upload:{source_upload_id}"
        else:
            source = public_source(source)
        key = (idempotency_header or payload.idempotency_key or "").strip() or None
        if key:
            existing = store.find_by_idempotency(key)
            if existing:
                return JSONResponse(status_code=200, content=job_response(existing) | {"reused": True})
        job_id = storage.new_job_id()
        now = store.now()
        store.create_job({"id": job_id, "idempotency_key": key, "source": source, "source_upload_id": source_upload_id, "options": payload.options, "created_at": now})
        if not manager.submit(job_id):
            # It remains durable and queued; the running worker will pick it up
            # only on restart, so explicitly report a retryable overload.
            raise HTTPException(status_code=429, detail="Backend worker is busy; retry shortly", headers={"X-Error-Code": "WORKER_BUSY"})
        row = store.get_job(job_id)
        return JSONResponse(status_code=202, content=job_response(row) | {"reused": False, "request_id": request.state.request_id})

    @app.get("/jobs", dependencies=[Depends(authenticate)])
    async def list_jobs(limit: int = Query(default=50, ge=1, le=100), status: str | None = Query(default=None), before: str | None = Query(default=None)) -> dict[str, Any]:
        rows = store.list_jobs(limit, status, before)
        jobs = [job_response(row) for row in rows]
        return {"items": jobs, "next_cursor": rows[-1]["created_at"] if len(rows) == limit else None}

    @app.get("/jobs/{job_id}", dependencies=[Depends(authenticate)])
    async def get_job(job_id: str) -> dict[str, Any]:
        row = store.get_job(job_id)
        if row is None:
            raise HTTPException(status_code=404, detail="Job not found", headers={"X-Error-Code": "JOB_NOT_FOUND"})
        return job_response(row)

    @app.post("/jobs/{job_id}/cancel", dependencies=[Depends(authenticate)])
    async def cancel_job(job_id: str) -> dict[str, Any]:
        row = store.get_job(job_id)
        if row is None:
            raise HTTPException(status_code=404, detail="Job not found", headers={"X-Error-Code": "JOB_NOT_FOUND"})
        if row["status"] == "completed":
            raise HTTPException(status_code=409, detail="Completed jobs cannot be cancelled", headers={"X-Error-Code": "JOB_NOT_CANCELLABLE"})
        manager.cancel(job_id)
        return job_response(store.get_job(job_id))

    @app.post("/jobs/{job_id}/resume", dependencies=[Depends(authenticate)])
    async def resume_job(job_id: str) -> dict[str, Any]:
        row = store.get_job(job_id)
        if row is None:
            raise HTTPException(status_code=404, detail="Job not found", headers={"X-Error-Code": "JOB_NOT_FOUND"})
        if row["status"] not in {"failed", "interrupted", "cancelled"} or not row["resume_available"]:
            raise HTTPException(status_code=409, detail="Job has no resumable checkpoint", headers={"X-Error-Code": "JOB_NOT_RESUMABLE"})
        if not row["engine_job_id"]:
            raise HTTPException(status_code=409, detail="Job has no engine checkpoint", headers={"X-Error-Code": "CHECKPOINT_NOT_FOUND"})
        resumed = store.prepare_resume(job_id)
        if not manager.submit(job_id):
            raise HTTPException(status_code=429, detail="Backend worker is busy; retry shortly", headers={"X-Error-Code": "WORKER_BUSY"})
        return job_response(resumed)

    @app.get("/jobs/{job_id}/results", dependencies=[Depends(authenticate)])
    async def get_results(job_id: str) -> dict[str, Any]:
        row = store.get_job(job_id)
        if row is None:
            raise HTTPException(status_code=404, detail="Job not found", headers={"X-Error-Code": "JOB_NOT_FOUND"})
        if row["status"] != "completed":
            raise HTTPException(status_code=409, detail="Results are not ready", headers={"X-Error-Code": "RESULTS_NOT_READY", "X-Retryable": "true"})
        payload = job_response(row)
        return {"job_id": job_id, "results": payload["results"], "clips_url": f"/jobs/{job_id}/clips"}

    @app.get("/jobs/{job_id}/clips", dependencies=[Depends(authenticate)])
    async def get_clips(job_id: str) -> dict[str, Any]:
        row = store.get_job(job_id)
        if row is None:
            raise HTTPException(status_code=404, detail="Job not found", headers={"X-Error-Code": "JOB_NOT_FOUND"})
        if row["status"] != "completed":
            raise HTTPException(status_code=409, detail="Clips are not ready", headers={"X-Error-Code": "CLIPS_NOT_READY", "X-Retryable": "true"})
        return {"job_id": job_id, "items": clip_response(job_id, row)}

    @app.get("/jobs/{job_id}/clips/{clip}/download", dependencies=[Depends(authenticate)])
    async def download_clip(job_id: str, clip: int):
        row = store.get_job(job_id)
        if row is None or row["status"] != "completed" or not row["engine_job_id"]:
            raise HTTPException(status_code=404, detail="Clip not found", headers={"X-Error-Code": "CLIP_NOT_FOUND"})
        items = clip_response(job_id, row)
        match = next((item for item in items if int(item.get("clip", -1)) == clip), None)
        if not match or not match.get("filename"):
            raise HTTPException(status_code=404, detail="Clip not found", headers={"X-Error-Code": "CLIP_NOT_FOUND"})
        try:
            target = storage.resolve_engine_clip(str(row["engine_job_id"]), str(match["filename"]))
        except StorageError as error:
            raise HTTPException(status_code=404, detail="Clip file not found", headers={"X-Error-Code": "CLIP_FILE_NOT_FOUND"}) from error
        return FileResponse(target, media_type="video/mp4", filename=target.name)

    @app.post("/jobs/{job_id}/clips/{clip}/render", dependencies=[Depends(authenticate)])
    async def render_clip(job_id: str, clip: int, _payload: RenderRequest | None = None) -> dict[str, Any]:
        try:
            rendered = await asyncio.to_thread(manager.render_clip, job_id, clip)
        except KeyError as error:
            raise HTTPException(status_code=404, detail="Job not found", headers={"X-Error-Code": "JOB_NOT_FOUND"}) from error
        except ValueError as error:
            raise HTTPException(status_code=409, detail=str(error), headers={"X-Error-Code": "JOB_NOT_RENDERABLE"}) from error
        except Exception as error:  # noqa: BLE001 - adapter normalizes engine failures in job runs
            raise HTTPException(status_code=502, detail=str(error), headers={"X-Error-Code": "RENDER_FAILED"}) from error
        return {"job_id": job_id, "clip": clip, "render": rendered, "download_url": f"/jobs/{job_id}/clips/{clip}/download"}

    return app


app = create_app()
