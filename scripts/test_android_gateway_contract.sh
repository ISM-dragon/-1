#!/usr/bin/env bash
set -euo pipefail

: "${GATEWAY_URL:?Set GATEWAY_URL to the HTTPS Gateway base URL}"
: "${GATEWAY_TOKEN:?Set GATEWAY_TOKEN to the Gateway session token}"
: "${VIDEO_FILE:?Set VIDEO_FILE to a local mp4 file}"

base="${GATEWAY_URL%/}"
auth=(-H "Authorization: Bearer ${GATEWAY_TOKEN}")
json_header=(-H "Accept: application/json")

echo "[1/5] health"
curl --fail-with-body --silent --show-error "${base}/health" "${auth[@]}" "${json_header[@]}"
echo

echo "[2/5] upload"
upload_json="$(curl --fail-with-body --silent --show-error -X POST "${base}/v1/sources/upload" "${auth[@]}" -H 'Content-Type: video/mp4' --data-binary "@${VIDEO_FILE}")"
echo "${upload_json}"
source_url="$(printf '%s' "${upload_json}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["source"])')"

idempotency="android-e2e-$(date +%s)-$RANDOM"
echo "[3/5] create processing job"
job_json="$(curl --fail-with-body --silent --show-error -X POST "${base}/v1/processing/jobs" "${auth[@]}" -H 'Content-Type: application/json' --data "{\"source\":\"${source_url}\",\"llm\":\"gemini\",\"captions\":\"classic\",\"mode\":\"balanced\",\"idempotency_key\":\"${idempotency}\"}")"
echo "${job_json}"
job_id="$(printf '%s' "${job_json}" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("job_id") or d.get("id"))')"

for attempt in $(seq 1 90); do
  status_json="$(curl --fail-with-body --silent --show-error "${base}/v1/processing/jobs/${job_id}" "${auth[@]}" "${json_header[@]}")"
  state="$(printf '%s' "${status_json}" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("state", ""))')"
  progress="$(printf '%s' "${status_json}" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("progress", d.get("fraction", 0)))')"
  echo "[4/5] poll ${attempt}/90 state=${state} progress=${progress}"
  case "${state}" in
    COMPLETED) break ;;
    FAILED|CANCELLED) echo "${status_json}"; exit 1 ;;
  esac
  sleep 2
done

if [ "${state}" != COMPLETED ]; then echo "Timed out waiting for job ${job_id}" >&2; exit 1; fi

echo "[5/5] results retrieval"
printf '%s' "${status_json}" | python3 -c 'import json,sys; d=json.load(sys.stdin); a=d.get("artifacts") or (d.get("results") or {}).get("artifacts") or []; print(json.dumps({"job_id":d.get("id") or d.get("job_id"),"artifact_count":len(a),"artifacts":a}, ensure_ascii=False, indent=2))'
