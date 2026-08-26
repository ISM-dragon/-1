from __future__ import annotations

import json
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parent
BASE = "http://127.0.0.1:8787"
HEADERS = {"Authorization": "Bearer final-acceptance-token"}
source = json.loads((ROOT / "gateway_failure_controls.json").read_text(encoding="utf-8"))
job_id = source.get("poll", {}).get("job_id")
result = {"job_id": job_id, "checks": []}
if job_id:
    response = requests.get(f"{BASE}/v1/processing/jobs/{job_id}", headers=HEADERS, timeout=30)
    result["checks"].append({"name": "status_after_backend_restart", "status": response.status_code, "body": response.json()})
    body = response.json()
    state = str(body.get("state", "")).upper()
    if state == "INTERRUPTED":
        resume = requests.post(f"{BASE}/v1/processing/jobs/{job_id}/resume", headers=HEADERS, json={}, timeout=30)
        result["checks"].append({"name": "resume_after_backend_restart", "status": resume.status_code, "body": resume.json()})
        result["checks"].append({"name": "status_after_resume_request", "status": requests.get(f"{BASE}/v1/processing/jobs/{job_id}", headers=HEADERS, timeout=30).status_code, "body": requests.get(f"{BASE}/v1/processing/jobs/{job_id}", headers=HEADERS, timeout=30).json()})
    elif state in {"FAILED", "CANCELLED"}:
        result["checks"].append({"name": "terminal_after_backend_restart", "pass": True, "state": state})
else:
    result["error"] = "No job ID captured"
(ROOT / "gateway_restart_recovery.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(result, ensure_ascii=False, indent=2))
