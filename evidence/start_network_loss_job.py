from __future__ import annotations

import json
from pathlib import Path
import requests

ROOT = Path(__file__).resolve().parent
headers = {"Authorization": "Bearer final-acceptance-token"}
with (ROOT / "fixtures" / "speech.mp4").open("rb") as stream:
    upload = requests.post("http://127.0.0.1:8787/v1/sources/upload", headers={**headers, "Content-Type": "video/mp4"}, data=stream, timeout=60)
source = upload.json()["source"]
create = requests.post("http://127.0.0.1:8787/v1/processing/jobs", headers={**headers, "Content-Type": "application/json"}, json={"source": source, "llm": "ollama", "captions": "classic", "mode": "balanced", "idempotency_key": "final-acceptance-network-loss-001"}, timeout=30)
result = {"upload": upload.json(), "create": create.json()}
(ROOT / "network_loss_job.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(result, ensure_ascii=False, indent=2))
