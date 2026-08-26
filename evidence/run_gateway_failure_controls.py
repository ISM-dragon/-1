from __future__ import annotations

import json
import time
from pathlib import Path

import requests

BASE = "http://127.0.0.1:8787"
TOKEN = "final-acceptance-token"
ROOT = Path(__file__).resolve().parent
MEDIA = ROOT / "fixtures" / "short.mp4"
headers = {"Authorization": f"Bearer {TOKEN}"}
output = {"checks": []}


def check(name: str, response: requests.Response, expected: set[int]) -> dict:
    try:
        body = response.json()
    except Exception:
        body = response.text[:1000]
    item = {"name": name, "status": response.status_code, "expected": sorted(expected), "pass": response.status_code in expected, "body": body}
    output["checks"].append(item)
    return item


with MEDIA.open("rb") as stream:
    upload = check(
        "upload_for_failure_controls",
        requests.post(f"{BASE}/v1/sources/upload", headers={**headers, "Content-Type": "video/mp4"}, data=stream, timeout=60),
        {200, 201},
    )
source = upload["body"].get("source") if isinstance(upload["body"], dict) else None
if source:
    created = check(
        "create_ollama_job",
        requests.post(
            f"{BASE}/v1/processing/jobs",
            headers={**headers, "Content-Type": "application/json"},
            json={"source": source, "llm": "ollama", "captions": "classic", "mode": "balanced", "idempotency_key": "final-acceptance-ollama-001"},
            timeout=30,
        ),
        {200, 201, 202},
    )
    job_id = created["body"].get("id") if isinstance(created["body"], dict) else None
    if job_id:
        history = []
        terminal = None
        for _ in range(60):
            response = requests.get(f"{BASE}/v1/processing/jobs/{job_id}", headers=headers, timeout=30)
            body = response.json()
            history.append({k: body.get(k) for k in ("status", "state", "stage", "error", "error_code", "retry_count", "recoverable")})
            if str(body.get("state", "")).upper() in {"COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED"}:
                terminal = body
                break
            time.sleep(2)
        output["poll"] = {"job_id": job_id, "terminal": terminal, "history": history}
        if terminal:
            check("cancel_terminal_job", requests.post(f"{BASE}/v1/processing/jobs/{job_id}/cancel", headers=headers, json={}, timeout=30), {400, 409})
            if str(terminal.get("state", "")).upper() == "FAILED":
                retry = check("retry_failed_job", requests.post(f"{BASE}/v1/processing/jobs/{job_id}/retry", headers=headers, json={}, timeout=30), {200, 202})
                output["retry_job_id"] = retry["body"].get("id", job_id) if isinstance(retry["body"], dict) else job_id
                time.sleep(3)
                output["after_retry"] = requests.get(f"{BASE}/v1/processing/jobs/{job_id}", headers=headers, timeout=30).json()

Path(ROOT / "gateway_failure_controls.json").write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(output, ensure_ascii=False, indent=2))
