from __future__ import annotations

import json
import time
from pathlib import Path
import requests

ROOT = Path(__file__).resolve().parent
BASE = "http://127.0.0.1:8787"
HEADERS = {"Authorization": "Bearer final-acceptance-token"}
job_id = json.loads((ROOT / "network_loss_job.json").read_text(encoding="utf-8"))["create"]["id"]
result = {"job_id": job_id, "history": []}
for _ in range(45):
    response = requests.get(f"{BASE}/v1/processing/jobs/{job_id}", headers=HEADERS, timeout=30)
    body = response.json()
    result["history"].append({k: body.get(k) for k in ("status", "state", "stage", "progress", "error", "error_code", "retry_count")})
    if str(body.get("state", "")).upper() in {"COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED"}:
        result["terminal"] = body
        break
    time.sleep(2)
(ROOT / "network_loss_recovery.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(result, ensure_ascii=False, indent=2))
