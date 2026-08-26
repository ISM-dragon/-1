# Migration decisions

**Decision owner:** Manus AI
**Date:** 2026-08-26

| Decision | Outcome | Evidence/constraint |
|---|---|---|
| Official Android processing model | Remote Gateway processing | The APK cannot safely embed Python, uv, WhisperX, model caches, or desktop FFmpeg. |
| Maintained Android client | Native Compose client | Room and WorkManager provide durable lifecycle behavior; the Tauri shell remains compatibility-only. |
| Engine strategy | Preserve existing stage graph | Existing stages and explainable scoring are more complete than the reference alternatives. |
| Backend persistence | Keep SQLite + durable worker queue | Appropriate for a personal private backend; avoids unnecessary Redis/Postgres/S3 operations. |
| API boundary | Versioned `/v1` contract | Android depends on stable JSON projections, not internal pipeline modules. |
| Media errors | Use typed categories | Invalid media, missing tools, model failures, disk exhaustion, and unsupported formats need distinct recovery UX. |
| Model management | Server-side metadata and health | Model paths, checksums, installation state, and loading stay off-device. |
| Captions | Keep Python word-timed ASS render path | Android reviews and exports server-produced clips; it does not silently produce a different canonical result. |
| Camera | Keep current implementation until benchmarked | A reference camera implementation is not a sufficient reason for replacement. |
| LLM failure | Degrade or return structured failure | A provider failure must not crash the complete job when a safe fallback exists. |
| Reference licensing | No source copied | The archive's root license is MIT; dependencies require independent review. The current repository remains AGPL-3.0-or-later. |
| Social publishing | Out of scope for this migration | Private clip generation is the target; existing social surfaces remain isolated and mock-first where applicable. |
| Release signing | Environment/CI secret only | No keystore, password, API key, or provider secret may be committed. |

## Rejected alternatives

Replacing the entire repository with the supplied archive was rejected because it would discard working pipeline stages, durable state, and existing test evidence. Porting the desktop Python stack directly into Android was rejected because its runtime and model dependencies do not match the Android process model. A broad service stack with billing, multi-user authentication, Redis, PostgreSQL, and S3 was rejected because the requested product is a personal private tool.

## Review triggers

Revisit these decisions only if a real-device benchmark demonstrates that the remote flow is unusable, if a native Android runtime can provide equivalent stage outputs within the device resource budget, or if the product scope explicitly expands to multiple users or public hosting.
