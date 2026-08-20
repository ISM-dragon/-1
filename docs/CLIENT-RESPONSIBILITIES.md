# ISM Client Responsibilities

## Shared rule

Clients are stateful views and interaction surfaces over the Gateway contract. They may cache data for offline display, but the Gateway remains authoritative for readiness, job state, provider state, publishing state, and analytics provenance.

| Client | Responsibilities | Prohibited behavior |
|---|---|---|
| React/Tauri desktop | Import and preview media, project review, local editing where supported, remote job submission, export presentation, Social Hub controls, diagnostics presentation. | Hardcoding provider credentials, treating a configured URL as a healthy Gateway, fabricating progress or publish success. |
| Native Android | Native media picker/share import, Gateway URL/session configuration, upload and WorkManager orchestration, durable job ID persistence, remote progress display, output download/cache, mobile Social Hub. | Bundling or assuming a Python runtime, storing refresh tokens/provider secrets, displaying completion before the Gateway reports it. |
| Gateway | Authentication, validation, correlation/request IDs, job state and transitions, retry/cancel/resume, provider and OAuth lifecycle, publishing validation, diagnostics, redacted errors. | Moving client presentation rules into server responses or creating a parallel backend. |
| Pipeline | Deterministic media stages, checkpointed execution, score/edit/render manifests, output validation. | Owning account credentials, social publishing, or client navigation. |

## Remote processing flow

1. The client validates that a source is selectable and asks the Gateway for capabilities.
2. The client uploads the source and persists the returned upload ID/source URL.
3. The client submits the processing request with an idempotency key and immediately persists the Gateway job ID.
4. The client polls the job resource or uses a future push transport; all visible progress comes from the server response.
5. Background workers continue polling after app backgrounding. Process recreation reloads the persisted job ID and resumes polling.
6. Cancellation calls the Gateway control route and waits for the durable state to become `CANCELLED` or a documented failure.
7. Completed artifacts are downloaded, validated locally, and persisted to the client repository.

## Error mapping

Clients should show three layers: what happened, why it happened, and what the user can do next. Stable error codes are mapped to localized copy. Raw stack traces, URLs containing secrets, authorization headers, and private filesystem paths are never shown.

| Error class | User-facing state | Safe action |
|---|---|---|
| Authentication | Authentication required | Re-enter or refresh the Gateway session. |
| Capability | Not ready | Open diagnostics; configure the missing service. |
| Retryable provider/network | Temporarily unavailable | Retry with bounded backoff. |
| Validation | Cannot start | Fix the source, media, text, account, or schedule. |
| Terminal job failure | Processing failed | Inspect error code; retry only if `recoverable=true`. |
| Offline | Waiting for connection | Keep persisted state and retry when network returns. |

## Product identity

All clients use the product name **ISM** and API prefix `/v1`. Compatibility package namespaces may remain in Android source until a deliberate migration release. A package migration must preserve existing installations where feasible, update deep links, and be verified by CI.
