#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN_DIR="$(mktemp -d)"
PORT="${PRIVATE_BACKEND_TEST_PORT:-8797}"
TOKEN="private-e2e-test-token"
cleanup() {
  if [[ -n "${SERVER_PID:-}" ]]; then kill "$SERVER_PID" 2>/dev/null || true; fi
  rm -rf "$RUN_DIR"
}
trap cleanup EXIT

ffmpeg -hide_banner -loglevel error -f lavfi -i color=c=blue:s=320x180:r=10 -f lavfi -i sine=frequency=440:sample_rate=16000 -t 2 -shortest -c:v libx264 -pix_fmt yuv420p -c:a aac "$RUN_DIR/real-video.mp4"

GATEWAY_TOKEN="$TOKEN" \
REQUIRE_GATEWAY_TOKEN=true \
PROVIDER_MODE=mock \
ISM_GATEWAY_DB="$RUN_DIR/gateway.db" \
ISM_SOURCE_ROOT="$RUN_DIR/sources" \
ISM_PROCESSING_ROOT="$RUN_DIR/processing" \
ISM_PIPELINE_DIR="$ROOT/pipeline" \
PUBLIC_BASE_URL="http://127.0.0.1:$PORT" \
MIN_FREE_DISK_GB=0 \
PYTHONPATH="$ROOT" \
python3 -m uvicorn gateway.main:app --host 127.0.0.1 --port "$PORT" >"$RUN_DIR/server.log" 2>&1 &
SERVER_PID=$!

for _ in $(seq 1 60); do
  if curl -fsS -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:$PORT/health" >/dev/null; then break; fi
  sleep 0.25
done

create_response="$(curl -fsS -X POST "http://127.0.0.1:$PORT/jobs" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@$RUN_DIR/real-video.mp4;type=video/mp4" \
  -F "llm=ollama" \
  -F "mode=fast")"
printf '%s\n' "$create_response"
JOB_ID="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["job_id"])' <<<"$create_response")"

terminal_state=""
for _ in $(seq 1 60); do
  status_response="$(curl -fsS -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:$PORT/jobs/$JOB_ID")"
  printf '%s\n' "$status_response"
  state="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["state"])' <<<"$status_response")"
  case "$state" in COMPLETED|FAILED|CANCELLED) terminal_state="$state"; break;; esac
  sleep 0.5
done

if [[ -z "$terminal_state" ]]; then
  cancel_response="$(curl -fsS -X POST -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:$PORT/jobs/$JOB_ID/cancel")"
  printf '%s\n' "$cancel_response"
  status_response="$(curl -fsS -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:$PORT/jobs/$JOB_ID")"
  terminal_state="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["state"])' <<<"$status_response")"
fi

if [[ "$terminal_state" != "COMPLETED" && "$terminal_state" != "FAILED" && "$terminal_state" != "CANCELLED" ]]; then
  echo "E2E_ASSERTIONS=FAIL: job did not reach a terminal state" >&2
  exit 1
fi

python3 - "$status_response" <<'PY'
import json, sys
payload = json.loads(sys.argv[1])
assert payload["job_id"]
assert "current_stage" in payload
assert "progress" in payload
assert "status" in payload
assert "errors" in payload
assert payload["state"] in {"COMPLETED", "FAILED", "CANCELLED"}
assert payload["job_id"]
print("E2E_ASSERTIONS=PASS", payload["state"])

PY
