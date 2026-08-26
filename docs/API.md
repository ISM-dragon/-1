# Private processing API

**Contract version:** `/v1`
**Audience:** native Android client and private Gateway operators

## Boundary

The Android client sends a bearer Gateway token and treats the Gateway as the authoritative source of job state. The client may persist a local projection for offline display, but it must reconcile that projection with the server before offering resume, retry, or export.

## Common response fields

| Field | Type | Meaning |
|---|---|---|
| `job_id` | string | Stable immutable processing identity. |
| `state` | string | `QUEUED`, `RUNNING`, `CANCEL_REQUESTED`, `CANCELLED`, `FAILED`, or `COMPLETED`; stage-specific states may be exposed for compatibility. |
| `stage` | string | Current pipeline stage: `ingest`, `asr`, `diarization`, `events`, `candidates`, `scoring`, `camera`, or `render`. |
| `fraction` | number | Server-reported progress in the inclusive range 0–1. |
| `message` | string | User-safe status text. |
| `recoverable` | boolean | Whether retry/resume may be offered. |
| `retry_count` | integer | Number of persisted attempts. |
| `error_code` | string or null | Stable machine-readable error category. |
| `correlation_id` | string or null | Diagnostic identifier safe to show in support logs. |

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/health` | Unauthenticated liveness/readiness summary. |
| `GET` | `/v1/auth/session` | Validate the configured private token session. |
| `GET` | `/v1/processing/capabilities` | Check Gateway, pipeline, FFmpeg, storage, and optional provider readiness. |
| `POST` | `/v1/sources/uploads` | Initialize a resumable upload using filename, byte size, and SHA-256. |
| `PATCH` | `/v1/sources/uploads/{upload_id}` | Append a chunk at the expected byte offset. |
| `POST` | `/v1/sources/uploads/{upload_id}/complete` | Validate and finalize the uploaded media. |
| `POST` | `/v1/processing/jobs` | Create a job from a remote URL or finalized upload URL. |
| `GET` | `/v1/processing/jobs/{job_id}` | Read the current durable job projection. |
| `POST` | `/v1/processing/jobs/{job_id}/cancel` | Persist cancellation and interrupt active execution. |
| `POST` | `/v1/processing/jobs/{job_id}/retry` | Start a bounded new attempt when recoverable. |
| `POST` | `/v1/processing/jobs/{job_id}/resume` | Resume using valid stage checkpoints. |
| `GET` | `/v1/processing/jobs/{job_id}/results` | Read the validated clip manifest. |
| `GET` | `/v1/processing/jobs/{job_id}/media/{filename}` | Download an authorized artifact inside the job directory. |

## Source creation

A remote source submission uses `source_type=remote_url` and a validated HTTPS URL. A local Android video uses the resumable upload endpoints first; the Gateway returns a private source URL that is then referenced by the processing job. The Android app never sends a `content://` URI to the Gateway.

## Result manifest

Each output contains `id`, `title`, `start`, `end`, `duration`, `transcript`, `score`, `media_url`, and, when available, score provenance and caption metadata. Missing optional metadata is represented as `null`; the client must not invent timestamps or scores.

## Error mapping

| Code | Meaning | Suggested client behavior |
|---|---|---|
| `MEDIA_INVALID` | Source cannot be probed or has no video stream | Ask for another file. |
| `UNSUPPORTED_FORMAT` | Container/codec is not accepted | Transcode locally only if explicitly supported, otherwise ask for another file. |
| `FFMPEG_MISSING` | Processing host lacks FFmpeg/ffprobe | Operator action; show Gateway degraded. |
| `FFMPEG_FAILED` | FFmpeg command failed | Offer retry; preserve correlation ID. |
| `MODEL_MISSING` / `MODEL_INVALID` | Required model is absent or corrupt | Operator/model-manager action; do not retry indefinitely. |
| `INSUFFICIENT_DISK` | Storage threshold was exceeded | Operator cleanup action, then retry. |
| `NETWORK_UNAVAILABLE` | Client or Gateway connection failed | WorkManager retry with backoff. |
| `JOB_CANCELLED` | Cancellation was persisted | Stop polling and show cancelled state. |

## Idempotency and recovery

Job creation accepts an idempotency key. Repeating a request with the same key returns the existing immutable job rather than creating duplicate processing. On application restart, Android restores its local job projection and schedules reconciliation. On Gateway restart, non-terminal jobs are requeued unless cancellation was already persisted; valid checkpoints are reused.
