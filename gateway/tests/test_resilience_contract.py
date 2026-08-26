"""Backend and local end-to-end resilience contracts.

External providers and worker subprocesses are mocked deliberately. The tests
assert the gateway's persisted state and public error codes, not provider
availability in the test environment.
"""

import asyncio
import sqlite3
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import httpx
import pytest
from fastapi import HTTPException

from gateway import main
from gateway.processing_service import classify_gemini_error
from gateway.worker_queue import validate_media_artifact
from pipeline.publikclip_pipeline.scoring import llm
from pipeline.publikclip_pipeline.scoring.llm import GeminiClient, LlmError


SOURCE = "https://gateway.example.test/v1/sources/jobs/upl_01/media/source.mp4"


def _request():
    return SimpleNamespace(state=SimpleNamespace(request_id="resilience-test"))


def _payload():
    return main.ProcessingPayload(source=SOURCE, llm="gemini", captions="classic", mode="balanced")


def _configured_gateway(tmp_path):
    return (
        patch.object(main, "DB_PATH", tmp_path / "gateway.db"),
        patch.object(main, "PROCESSING_ROOT", tmp_path / "processing"),
        patch.object(main, "SOURCE_ROOT", tmp_path / "sources"),
        patch.object(main, "PUBLIC_BASE_URL", "https://gateway.example.test"),
    )


def _create_job(tmp_path, *, submit=True):
    patches = _configured_gateway(tmp_path)
    for item in patches:
        item.start()
    try:
        main.init_db()
        with (
            patch.object(main, "read_server_gemini_key", return_value="server-side-key"),
            patch.object(main, "pipeline_checks", return_value={"pipeline": True, "storage": True}),
            patch.object(main, "_ffmpeg_capability", return_value={"ready": True}),
            patch.object(main._processing_workers, "submit", return_value=submit),
        ):
            created = asyncio.run(main.start_processing(_payload(), _request()))
        return created
    except Exception:
        for item in reversed(patches):
            item.stop()
        raise


def test_missing_model_is_explicitly_not_present(tmp_path, monkeypatch):
    monkeypatch.setenv("PUBLIKCLIP_HOME", str(tmp_path / "home"))
    from pipeline.publikclip_pipeline.models.registry import is_present
    from pipeline.publikclip_pipeline.models.specs import CAMPPLUS

    assert is_present(CAMPPLUS) is False


def test_llm_unavailable_has_stable_error_code(monkeypatch):
    monkeypatch.setattr("pipeline.publikclip_pipeline.scoring.llm.gemini_api_key", lambda: None)

    with pytest.raises(LlmError) as error:
        GeminiClient()

    assert error.value.code == "GEMINI_NOT_CONFIGURED"


def test_network_interruption_is_classified_without_leaking_provider_error(monkeypatch):
    monkeypatch.setattr(llm.httpx, "post", lambda *_args, **_kwargs: (_ for _ in ()).throw(httpx.ConnectError("simulated network interruption")))
    client = GeminiClient(api_key="test-key")

    with pytest.raises(LlmError) as error:
        client.generate_json("prompt", {"type": "object"}, use_cache=False)

    assert classify_gemini_error(error.value) == ("network_error", "GEMINI_NETWORK_ERROR")


def test_ffmpeg_unavailable_returns_503_before_job_creation(tmp_path):
    with _configured_gateway(tmp_path)[0], _configured_gateway(tmp_path)[1], _configured_gateway(tmp_path)[2], _configured_gateway(tmp_path)[3]:
        main.init_db()
        with (
            patch.object(main, "read_server_gemini_key", return_value="server-side-key"),
            patch.object(main, "pipeline_checks", return_value={"pipeline": True, "storage": True}),
            patch.object(main, "_ffmpeg_capability", return_value={"ready": False}),
        ):
            with pytest.raises(HTTPException) as error:
                asyncio.run(main.start_processing(_payload(), _request()))

        assert error.value.status_code == 503
        assert "FFMPEG_UNAVAILABLE" in str(error.value.detail)
        with sqlite3.connect(main.DB_PATH) as connection:
            assert connection.execute("SELECT COUNT(*) FROM processing_jobs").fetchone()[0] == 0


def test_job_failure_can_be_resumed_once_and_duplicate_resume_is_rejected(tmp_path):
    patches = _configured_gateway(tmp_path)
    with patches[0], patches[1], patches[2], patches[3]:
        main.init_db()
        timestamp = main.now_iso()
        with sqlite3.connect(main.DB_PATH) as connection:
            connection.execute(
                "INSERT INTO processing_jobs (id, source, status, state, recoverable, retry_count, cancel_requested, created_at, updated_at) VALUES (?, ?, 'failed', 'FAILED', 1, 0, 0, ?, ?)",
                ("proc_failed", SOURCE, timestamp, timestamp),
            )
            connection.commit()

        with patch.object(main._processing_workers, "submit", return_value=True):
            resumed = asyncio.run(main.resume_processing("proc_failed"))
            with pytest.raises(HTTPException) as error:
                asyncio.run(main.resume_processing("proc_failed"))

        assert resumed["state"] == "QUEUED"
        assert resumed["status"] == "queued"
        assert error.value.status_code == 409
        assert len(main.processing_transition_history("proc_failed")) == 1


def test_job_cancellation_persists_terminal_state(tmp_path):
    patches = _configured_gateway(tmp_path)
    with patches[0], patches[1], patches[2], patches[3]:
        main.init_db()
        timestamp = main.now_iso()
        with sqlite3.connect(main.DB_PATH) as connection:
            connection.execute(
                "INSERT INTO processing_jobs (id, source, status, state, recoverable, retry_count, cancel_requested, created_at, updated_at) VALUES (?, ?, 'queued', 'QUEUED', 1, 0, 0, ?, ?)",
                ("proc_cancel", SOURCE, timestamp, timestamp),
            )
            connection.commit()

        cancelled = asyncio.run(main.cancel_processing("proc_cancel"))
        cancelled_again = asyncio.run(main.cancel_processing("proc_cancel"))

        assert cancelled["state"] == "CANCELLED"
        assert cancelled["status"] == "cancelled"
        assert cancelled_again["state"] == "CANCELLED"
        assert main.processing_cancel_requested("proc_cancel") is True


def test_backend_restart_marks_inflight_job_interrupted(tmp_path):
    patches = _configured_gateway(tmp_path)
    with patches[0], patches[1], patches[2], patches[3]:
        main.init_db()
        timestamp = main.now_iso()
        with sqlite3.connect(main.DB_PATH) as connection:
            connection.execute(
                "INSERT INTO processing_jobs (id, source, status, state, recoverable, retry_count, cancel_requested, created_at, updated_at) VALUES (?, ?, 'running', 'ANALYZING', 0, 0, 0, ?, ?)",
                ("proc_restart", SOURCE, timestamp, timestamp),
            )
            connection.commit()

        with (
            patch.object(main._processing_workers, "start", new=AsyncMock()),
            patch.object(main._source_workers, "start", new=AsyncMock()),
            patch.object(main._processing_workers, "submit", return_value=False),
            patch.object(main._source_workers, "submit", return_value=False),
            patch.object(main, "scheduler_loop", new=AsyncMock()),
        ):
            asyncio.run(main.startup())
            current = asyncio.run(main.processing_status("proc_restart"))
            asyncio.run(main.shutdown())

        assert current["state"] == "INTERRUPTED"
        assert current["status"] == "queued"
        assert current["recoverable"] is True
        assert "restart" in current["message"].lower()


def test_end_to_end_create_and_poll_contract(tmp_path):
    patches = _configured_gateway(tmp_path)
    with patches[0], patches[1], patches[2], patches[3]:
        main.init_db()
        with (
            patch.object(main, "read_server_gemini_key", return_value="server-side-key"),
            patch.object(main, "pipeline_checks", return_value={"pipeline": True, "storage": True}),
            patch.object(main, "_ffmpeg_capability", return_value={"ready": True}),
            patch.object(main._processing_workers, "submit", return_value=True),
        ):
            created = asyncio.run(main.start_processing(_payload(), _request()))
            polled = asyncio.run(main.processing_status(created["id"]))

        assert created["state"] == "QUEUED"
        assert polled["id"] == created["id"]
        assert polled["state"] == "QUEUED"
        assert "transitions" in polled
        assert polled["transitions"][0]["to_state"] == "QUEUED"


def test_large_and_invalid_artifacts_are_rejected_or_accepted_by_size_and_container(tmp_path):
    large = tmp_path / "large.mp4"
    with large.open("wb") as handle:
        handle.truncate(64 * 1024 * 1024)
    valid, reason = validate_media_artifact(large)
    assert valid is True
    assert reason is None

    invalid = tmp_path / "not-video.txt"
    invalid.write_bytes(b"x" * 2048)
    valid, reason = validate_media_artifact(invalid)
    assert valid is False
    assert "unsupported media container" in reason
