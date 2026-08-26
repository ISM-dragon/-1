from __future__ import annotations

import json
import time
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parent
BASE = "http://127.0.0.1:8787"
HEADERS = {"Authorization": "Bearer final-acceptance-token"}
MEDIA = ROOT / "fixtures" / "speech.mp4"
result: dict = {"checks": []}

with MEDIA.open("rb") as stream:
    upload = requests.post(f"{BASE}/v1/sources/upload", headers={**HEADERS, "Content-Type": "video/mp4"}, data=stream, timeout=60)
result["checks"].append({"name": "upload_speech", "status": upload.status_code, "body": upload.json()})
source = upload.json().get("source")
create = requests.post(f"{BASE}/v1/processing/jobs", headers={**HEADERS, "Content-Type": "application/json"}, json={"source": source, "llm": "ollama", "captions": "classic", "mode": "balanced", "idempotency_key": "final-acceptance-cancel-active-001"}, timeout=30)
result["checks"].append({"name": "create_active_cancel_job", "status": create.status_code, "body": create.json()})
job_id = create.json().get("id")
if job_id:
    observed = []
    for _ in range(10):
        body = requests.get(f"{BASE}/v1/processing/jobs/{job_id}", headers=HEADERS, timeout=30).json()
        observed.append({k: body.get(k) for k in ("status", "state", "stage", "progress", "error_code")})
        if str(body.get("state", "")).upper() in {"TRANSCRIBING", "INGESTING", "PREPARING", "RUNNING"}:
            break
        time.sleep(1)
    result["before_cancel"] = observed
    cancel = requests.post(f"{BASE}/v1/processing/jobs/{job_id}/cancel", headers=HEADERS, json={}, timeout=30)
    result["checks"].append({"name": "cancel_active_job", "status": cancel.status_code, "body": cancel.json()})
    terminal = None
    history = []
    for _ in range(30):
        body = requests.get(f"{BASE}/v1/processing/jobs/{job_id}", headers=HEADERS, timeout=30).json()
        history.append({k: body.get(k) for k in ("status", "state", "stage", "progress", "error_code", "cancel_requested")})
        if str(body.get("state", "")).upper() in {"CANCELLED", "FAILED", "COMPLETED", "INTERRUPTED"}:
            terminal = body
            break
        time.sleep(1)
    result["after_cancel"] = {"terminal": terminal, "history": history}
(ROOT / "active_cancel.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(result, ensure_ascii=False, indent=2))
