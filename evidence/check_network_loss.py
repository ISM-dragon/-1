from __future__ import annotations

import json
from pathlib import Path
import requests

result = {"endpoint": "http://127.0.0.1:8787/v1/processing/jobs", "reachable": False}
try:
    response = requests.get(result["endpoint"], headers={"Authorization": "Bearer final-acceptance-token"}, timeout=3)
    result.update({"reachable": True, "status": response.status_code, "body": response.text[:500]})
except requests.RequestException as error:
    result.update({"error_type": type(error).__name__, "error": str(error)})
Path(__file__).resolve().parent.joinpath("network_loss_observation.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(result, ensure_ascii=False, indent=2))
