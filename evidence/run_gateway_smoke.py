from __future__ import annotations

import json
import time
from pathlib import Path

import requests

BASE = "http://127.0.0.1:8787"
TOKEN = "final-acceptance-token"
ROOT = Path(__file__).resolve().parent
MEDIA = ROOT / "fixtures" / "short.mp4"
CORRUPTED = ROOT / "fixtures" / "corrupted.mp4"
OUT = ROOT / "gateway_smoke.json"

session = requests.Session()
headers = {"Authorization": f"Bearer {TOKEN}"}
results: list[dict] = []


def record(name: str, response: requests.Response, expected: set[int] | None = None, body: object | None = None) -> dict:
    try:
        payload = response.json()
    except Exception:
        payload = response.text[:1000]
    item = {
        "name": name,
        "status": response.status_code,
        "expected": sorted(expected) if expected else None,
        "pass": response.status_code in expected if expected else response.ok,
        "body": body if body is not None else payload,
    }
    results.append(item)
    return item


def main() -> int:
    record("health", session.get(f"{BASE}/health", timeout=15), {200})
    record("private_route_without_token", session.get(f"{BASE}/v1/processing/capabilities", timeout=15), {401, 403})
    record("capabilities", session.get(f"{BASE}/v1/processing/capabilities", headers=headers, timeout=15), {200})
    record("pipeline_diagnostic", session.post(f"{BASE}/v1/diagnostics/pipeline", headers=headers, timeout=90), {200, 503})
    record("gemini_diagnostic", session.post(f"{BASE}/v1/diagnostics/gemini", headers=headers, timeout=30), {200, 503})

    with MEDIA.open("rb") as stream:
        upload = session.post(
            f"{BASE}/v1/sources/upload",
            headers={**headers, "Content-Type": "video/mp4"},
            data=stream,
            timeout=60,
        )
    upload_item = record("upload_valid_video", upload, {200, 201})
    source = upload_item["body"].get("source") if isinstance(upload_item["body"], dict) else None

    with CORRUPTED.open("rb") as stream:
        record(
            "upload_invalid_video",
            session.post(
                f"{BASE}/v1/sources/upload",
                headers={**headers, "Content-Type": "video/mp4"},
                data=stream,
                timeout=60,
            ),
            {400, 409, 415, 422},
        )

    job_id = None
    if source:
        create = session.post(
            f"{BASE}/v1/processing/jobs",
            headers={**headers, "Content-Type": "application/json"},
            json={
                "source": source,
                "llm": "gemini",
                "captions": "classic",
                "mode": "balanced",
                "idempotency_key": "final-acceptance-smoke-001",
            },
            timeout=30,
        )
        create_item = record("create_processing_job", create, {200, 201, 202, 503})
        if isinstance(create_item["body"], dict):
            job_id = create_item["body"].get("id") or create_item["body"].get("job_id")

    if job_id:
        terminal = None
        history = []
        for _ in range(45):
            status_response = session.get(f"{BASE}/v1/processing/jobs/{job_id}", headers=headers, timeout=30)
            payload = status_response.json()
            history.append({
                "status": payload.get("status"),
                "state": payload.get("state"),
                "stage": payload.get("stage"),
                "error_code": payload.get("error_code"),
                "progress": payload.get("progress"),
            })
            if str(payload.get("state", "")).upper() in {"COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED"} or payload.get("status") in {"done", "failed", "cancelled"}:
                terminal = payload
                break
            time.sleep(2)
        results.append({"name": "processing_poll", "pass": terminal is not None, "history": history, "terminal": terminal})
        if terminal and str(terminal.get("state", "")).upper() == "FAILED":
            record("cancel_failed_job", session.post(f"{BASE}/v1/processing/jobs/{job_id}/cancel", headers=headers, json={}, timeout=30), {400, 409})
            record("retry_failed_job", session.post(f"{BASE}/v1/processing/jobs/{job_id}/retry", headers=headers, json={}, timeout=30), {200, 202, 409})

    OUT.write_text(json.dumps({"base": BASE, "results": results}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(OUT)
    print(json.dumps(results, ensure_ascii=False, indent=2))
    return 0 if all(item.get("pass", True) for item in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
