# Reference migration plan

**Status:** Approved implementation plan for this repository
**Date:** 2026-08-26

## Principles

The migration is incremental. The existing pipeline remains the source of truth for generated clips. The Android application communicates through a narrow private API and never imports Python internals. Every cross-component change begins with a contract and ends with tests. No stage is replaced solely because a reference implementation is larger or newer.

## Waves

| Wave | Scope | Exit criteria |
|---:|---|---|
| 1 | Audit, comparison, architecture, license review | Required documents exist; ownership and non-goals are explicit. |
| 2 | Engine, AI/media runtime, Gateway foundations | Stable engine facade, typed errors, runtime readiness, durable job behavior, and regression tests. |
| 3 | Android core and UI | Remote import, upload, progress, restoration, result review, edit, and export are contract-driven. |
| 4 | Integration | Android and Gateway agree on source/upload, status, result manifest, cancel, retry, and resume. |
| 5 | QA and release | Unit/integration/build checks pass; real-device E2E and signing prerequisites are recorded. |

## Implementation order

1. Keep the existing engine stages and formalize the public facade.
2. Add media and model managers on the processing host, with explicit capability and error reporting.
3. Keep Gateway SQLite and its persistent worker queue for this single-user deployment.
4. Treat the Gateway as authoritative for job state, checkpoints, and artifact manifests.
5. Keep Android local storage limited to imported source copies, job projections, cached results, and user editing state.
6. Add contract tests before changing payload shapes.
7. Add Android workflow tests and release-build validation without committing signing keys.

## Explicitly deferred

Native WhisperX/diarization parity, Android-local active-speaker detection, full ASS renderer parity on-device, live social publishing, billing, multi-user accounts, and cloud storage are deferred. They require independent benchmarks, provider reviews, or infrastructure decisions and are not prerequisites for a private remote-processing APK.

## Rollback strategy

Changes are grouped into focused commits. If runtime capability probing causes compatibility issues, the Gateway can fall back to the existing contract-level checks while retaining typed error responses. If Android UI changes fail, the existing Compose screens remain available because the remote client and worker are isolated from the presentation layer.

## Ownership boundaries

| Owner | Paths |
|---|---|
| Engine/runtime | `pipeline/publikclip_pipeline/engine`, `pipeline/publikclip_pipeline/runtime`, related tests |
| Gateway | `gateway`, Gateway tests, deployment docs |
| Android | `android`, Android tests, Android README |
| Documentation | `docs`, `MANUS_HANDOFF.md` |

Cross-boundary changes must update the relevant contract documentation and regression tests in the same change set.
