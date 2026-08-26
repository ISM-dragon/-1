from __future__ import annotations

import json
import time
from pathlib import Path
import requests

ROOT = Path(__file__).resolve().parent
BASE = "http://127.0.0.1:8787"
HEADERS = {"Authorization": "Bearer final-acceptance-token"}
job_id = json.loads((ROOT / "network_loss_job.json").read_text(encoding="utf-8"))["create"]["id"]
result = {"job_id": job_id, "checks": []}
retry = requests.post(f"{BASE}/v1/processing/jobs/{job_id}/retry", headers=HEADERS, json={}, timeout=30)
result["checks"].append({"name": "retry_after_network_loss", "status": retry.status_code, "body": retry.json()})
for _ in range(45):
    body = requests.get(f"{BASE}/v1/processing/jobs/{job_id}", headers=HEADERS, timeout=30).json()
    result.setdefault("history", []).append({k: body.get(k) for k in ("status", "state", "stage", "progress", "error_code", "retry_count")})
    if str(body.get("state", "")).upper() in {"COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED"}:
        result["terminal"] = body
        break
    time.sleep(2)
(ROOT / "retry_after_network_loss.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(result, ensure_ascii=False, indent=2))
