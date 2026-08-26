# Security boundaries

This application is private and single-user, but it still treats the Android APK, Gateway, engine, models, and media storage as separate trust boundaries.

## Controls

| Boundary | Control |
|---|---|
| Android → Gateway | HTTPS in production, non-empty bearer token, bounded timeouts, no Python-module coupling. |
| Gateway → engine | Validate source/settings, stable facade, safe error conversion, correlation IDs. |
| Gateway → storage | Safe basenames, resolved-path containment, per-job roots, cleanup of abandoned parts. |
| Gateway → providers | Server-side keys/vault; redact secrets and authorization headers from logs/responses. |
| Model/runtime | Verify downloads/checksums before loading; expose health without exposing paths or credentials. |
| Media serving | Serve only authorized artifacts below the job root; do not expose arbitrary filesystem paths. |
| Repository/CI | No secrets, keystores, model weights, downloaded media, or generated build caches in Git. |

## Threats addressed

The Gateway validates remote sources and rejects unsafe/reserved network targets according to its existing URL policy. Uploads use declared byte size and SHA-256, resumable offsets, safe filenames, and final media probing. Idempotency prevents duplicate processing when mobile retries a request. Cancellation is persisted so restart recovery cannot resurrect a user-cancelled job.

## Deployment requirements

Use a private network or HTTPS reverse proxy, set `REQUIRE_GATEWAY_TOKEN=true`, provide a strong token through environment configuration, restrict source/processing roots, and do not expose provider credentials to Android. Release signing credentials are injected into CI or the local build environment and never committed.
