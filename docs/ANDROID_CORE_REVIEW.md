# Android Core Review

## Contract files

The requested `docs/ARCHITECTURE.md`, `docs/CONTRACTS.md`, and `docs/API.md` are not present in this checkout. The canonical equivalents reviewed are `docs/MASTER-ARCHITECTURE.md`, `docs/API-CONTRACT.md`, and `docs/CLIENT-RESPONSIBILITIES.md`.

## Relevant findings

- The Gateway is the source of truth for readiness, job state, cancellation, retry/resume, and artifact manifests.
- Android must not bundle or assume Python, FFmpeg, uv, WhisperX, or PyTorch.
- Processing submission is `POST /v1/processing/jobs`; status is `GET /v1/processing/jobs/{id}`; control routes are `/cancel`, `/retry`, and `/resume`; upload is `POST /v1/sources/upload`.
- Current Android already has Room persistence, WorkManager orchestration, `ProcessingGatewayClient`, and Android Keystore-backed token encryption, but these concerns are embedded in the large `OpusRepository` and worker.
- The documented API does not expose `GET /v1/processing/jobs` or a separate render endpoint. The client should therefore implement `getJobs(ids)` as repeated authoritative status reads and model render as the server-side render request fields of job creation, while exposing a future-compatible render method only if a backend endpoint is added.
- Remote result media is returned in the processing job's `results.render.outputs`, with URLs in the `path` field.
