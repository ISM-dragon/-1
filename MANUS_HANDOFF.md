# MANUS HANDOFF — ISM Android Audit

**Session date:** 2026-08-26  
**Repository:** `ISM-dragon/-1`  
**Audit baseline:** `e21f891`
**Current work:** limited implementation patch based on the audit; no large rebuild started.

## Decision summary

The repository currently contains two Android surfaces:

| Surface | Actual role | Current processing reality |
|---|---|---|
| `app/src-tauri/gen/android` | Tauri-generated React shell | Does not spawn Python/uv/FFmpeg on Android; React uses remote Gateway HTTP processing |
| `android/` | Separate Kotlin/Compose application | Has local simplified processing and remote processing; remote HTTP/HTTPS routing is now accepted when Gateway is configured |

The recommended short path is to select the Tauri Android shell as the first official remote-processing APK. Keep Python, model loading, FFmpeg, scoring, captions, rendering, and durable jobs on the Gateway. Do not port the desktop pipeline into Android in the first delivery.

## Confirmed working areas

`Gateway` has FastAPI routes for health/capabilities, source upload/download, processing jobs, polling, cancellation, retry, resume, artifact serving, and restart recovery. It persists job state and transition history in SQLite and executes the Python pipeline as a subprocess.

The Python pipeline has an ordered stage graph from ingest through ASR, diarization, events, candidates, scoring, camera, and rendering. Checkpoints are atomic JSON files with schema validation and artifact checks. The renderer produces verified 9:16 H.264/AAC MP4s and can burn ASS captions when a caption-capable FFmpeg is available.

React typecheck and Vite production build succeeded. Gateway tests passed 36 tests after the patch, and pipeline tests passed 91 tests. Windows CI contains a stronger desktop validation path for `uv`, FFmpeg, pipeline tests, NSIS packaging, silent install, and launch. Android CI currently validates only unit tests, lint, and debug APK assembly.

## Critical blockers

1. The Android APK → Gateway → real processing job → MP4 E2E path is still unverified. The previous URL-routing mismatch was corrected in this patch.
2. Tauri Android intentionally cannot run the desktop Python/uv runtime locally; Native Android does not implement desktop-equivalent WhisperX, diarization, active-speaker, ASS/libass rendering, or the full scoring graph.
3. Gateway live social adapters and OAuth are not implemented outside `PROVIDER_MODE=mock`; Instagram/Gateway publishing is not production-ready.
4. Android CI produces only debug APKs. Release signing is conditional on local/CI keystore variables and is not validated by the workflow.
5. Gateway capability checks can report structural pipeline readiness while heavy runtime imports/models may still fail when a real worker starts.
6. Native Android and Tauri Android have different package identities and different processing contracts. One official Android surface must be selected before substantial implementation.

## Patch applied in this session

The minimal Native Compose contract correction is implemented: `ProcessingEngine` and `VideoProcessingWorker` accept HTTP/HTTPS when a Gateway is configured; `ProcessingGatewayClient` sends remote URLs directly while retaining upload for `content://`/`file://`; and Gateway result metadata now carries start/end/title/transcript from score outputs. Regression coverage was added for URL routing and result enrichment.

## Next execution step

Choose one official Android surface, preferably the Tauri Android shell for the first remote-processing APK, provision the Gateway with Python 3.12, pipeline dependencies, FFmpeg/ffprobe, model cache policy, writable storage, `PUBLIC_BASE_URL`, gateway auth, and server-side Gemini key, then run a short real YouTube job. Do not copy the Python pipeline into Android.

## Files produced by this audit

- `docs/ANDROID_AUDIT.md` — full evidence-based audit, proposed architecture, blockers, minimal path, and phased execution plan.
- `MANUS_HANDOFF.md` — this short handoff, updated with the implementation patch.
- Android/Gateway source and regression test files — limited routing and metadata fixes.

## Verification notes

The local Android test command was attempted with `cd android && ./gradlew :app:testDebugUnitTest --no-daemon`. Gradle downloaded successfully, but execution stopped before tests because the environment had no Android SDK (`SDK location not found`). Gateway tests passed 36 tests after the patch, and Python pipeline tests passed 91 tests. Temporary build/cache outputs were removed before committing.

See `docs/ANDROID_AUDIT.md` for repository-file references and the complete test matrix.
