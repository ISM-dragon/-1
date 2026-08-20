# ISM Master Architecture

**Status:** Canonical architecture baseline for the current repository.
**Product:** ISM
**API contract version:** `/v1`
**Last audited:** 2026-08-19

## Purpose

ISM is a multi-client media intelligence and publishing system. The repository already contains a React/Tauri desktop client, a native Android/Jetpack Compose client, a FastAPI Gateway, and a Python processing pipeline. This document formalizes the existing architecture without introducing a second backend or replacing working implementations.

> The Gateway is the control plane. Clients request capabilities and state from the Gateway; they do not infer production readiness from configuration presence.

## Canonical topology

```text
ISM
├── Studio (React + Tauri desktop)
├── Social Hub (desktop and Android surfaces)
├── Native Android Studio (Kotlin + Compose)
└── ISM Gateway (FastAPI)
    ├── durable job state and worker coordination
    ├── source upload/download boundary
    ├── provider registry and server-side secret vault
    ├── OAuth/account lifecycle
    ├── publishing scheduler
    ├── diagnostics and correlation IDs
    └── Python processing pipeline
        ├── ingest / ASR / diarization
        ├── candidate generation and scoring
        ├── edit decisions and rendering
        └── artifact validation
```

| Boundary | Owns | Must not own |
|---|---|---|
| Desktop | Local UX, project review, optional local processing, export presentation | Provider secrets, OAuth refresh tokens, authoritative job state |
| Android | Mobile import, remote job submission, background work, local clip cache | Python runtime assumptions, provider secrets, refresh tokens |
| Gateway | Auth, API validation, job state, retries, cancellation, provider routing, OAuth, publishing, diagnostics | Client-only presentation details |
| Pipeline | Media analysis, deterministic edit decisions, rendering, checkpoint files | Account credentials, provider OAuth state, client navigation |
| Social providers | Platform-specific validation, upload, publish, schedule, analytics | ISM job ownership or client persistence |

## Authentication and authorization

The Gateway accepts a bearer session token when `GATEWAY_TOKEN` is configured. `REQUIRE_GATEWAY_TOKEN=true` makes missing configuration a deployment error rather than silently enabling an unauthenticated remote service. All state-changing and private read routes use the same dependency. Production OAuth tokens remain in the Gateway vault/database boundary; Android stores only the short-lived Gateway session configuration required to call the Gateway.

## Job lifecycle

A processing submission is validated, assigned a stable job ID and correlation ID, persisted in SQLite, and submitted to the persistent worker queue. The external status remains backward compatible (`queued`, `running`, `done`, `failed`) while the canonical stage is reported separately using the pipeline stage vocabulary. Cancellation is persisted before the worker is interrupted. Retry and resume create a new attempt against the same immutable job identity and reuse the pipeline checkpoint when available.

| Phase | Owner | Durable evidence |
|---|---|---|
| Submission | Gateway | job row, idempotency key, correlation ID |
| Execution | Gateway worker + pipeline | stage progress, checkpoint files, worker heartbeat |
| Recovery | Gateway startup + queue | requeued non-terminal jobs, checkpoint resume |
| Completion | Pipeline + Gateway | validated render manifest and artifact URLs |
| Failure | Gateway | structured error code, recoverable flag, retry count |
| Cancellation | Gateway + worker | cancelled state and termination timestamp |

## AI routing lifecycle

AI provider registration, model capabilities, health, credentials, and usage are separate concepts. A route selects by task and required capability first, then modality/context, health, user preference, cost, latency, and fallback policy. Structured output must be schema-validated. A failed primary provider may use an explicitly configured fallback; if no safe fallback exists, the request fails or returns a clearly marked degraded result. No production flow uses a mock success response.

## Provider and OAuth lifecycle

Provider health checks execute the required capability where supported. Social accounts use explicit states such as `DISCONNECTED`, `CONNECTING`, `CONNECTED`, `TOKEN_EXPIRING`, `REAUTH_REQUIRED`, `FAILED`, and `CAPABILITY_RESTRICTED`. OAuth state and nonce values are correlated at the Gateway. The current repository retains a mock OAuth surface for local development only; production providers must be configured explicitly and cannot be represented as connected merely because a URL or key exists.

## Publishing lifecycle

A publish request is validated against the media manifest, account state, token state, platform capabilities, quota, text limits, and schedule validity. An idempotency key prevents duplicate posts. The persisted post records provider post ID, permalink, status, error category, attempt count, and next retry time. Provider limitations remain visible to clients as capability restrictions instead of being converted to successful publishing.

## Analytics lifecycle

Analytics snapshots are stored only when supplied by a provider or explicitly marked manual. Unavailable metrics remain `null` rather than becoming fabricated zeroes. Prediction records carry score version and confidence provenance. Calibration is versioned and must not silently retrain an opaque production model.

## Storage model

SQLite is the Gateway source of truth for accounts, posts, processing jobs, source jobs, analytics snapshots, provider metadata, and job transitions. Media is stored below controlled source and processing roots. Every exposed media path is resolved and checked to remain inside its authorized job directory. Pipeline checkpoints are atomic JSON artifacts below the pipeline job directory. Temporary artifacts are removed on failed uploads and should be reclaimed after terminal retention windows.

## Offline, retry, cancellation, and recovery

Clients must show explicit offline and reconnect states. Android uses WorkManager for remote jobs and persists the Gateway job ID before polling. Polling reports server progress only; clients do not animate fake completion. External calls require bounded timeouts and classified errors. Retries use bounded attempts and exponential backoff with jitter. Gateway startup requeues interrupted non-terminal work, while pipeline resume uses checkpoint state. Cancellation is persistent and must not be undone by restart recovery.

## Security boundaries

Secrets are sourced from environment variables or the server-side vault and are redacted from responses and logs. Public URL validation rejects private, loopback, link-local, reserved, and multicast addresses to reduce SSRF risk. Media file names are reduced to safe basenames and path containment is checked before serving. Error responses expose stable codes and user-safe messages rather than raw traces, credentials, authorization headers, or private filesystem paths.

## Release boundaries

Product identity is ISM. Desktop, Android, Gateway, and pipeline versions are tracked in repository manifests and validated by CI. Android currently retains the existing package namespace for compatibility; any future application ID change requires an intentional migration and release-note entry. Release signing secrets are injected through CI and never committed.

## Audit baseline

The audit found a working Gateway, pipeline checkpoint implementation, Android remote client, desktop production build, and existing tests. The initial Gateway suite passed 29 tests. The first pipeline invocation from the repository root failed during collection because the package source directory was not on `PYTHONPATH`; this is a test-runner configuration issue and is addressed by the repository test configuration. The initial Android command was invoked from the wrong directory and is documented as `android/gradlew :app:testDebugUnitTest`.

## Definition of done for architecture changes

A change is complete only when its implementation, state contract, validation, error behavior, persistence impact, tests, documentation, and restart behavior have been considered. External provider credentials and app-review prerequisites remain explicit deployment dependencies and are never simulated.
