# MANUS HANDOFF — ISM Android Audit

**Session date:** 2026-08-26  
**Repository:** `ISM-dragon/-1`  
**Audited revision:** `e21f891`  
**Scope completed:** audit only; no large implementation started.

## Decision summary

The repository currently contains two Android surfaces:

| Surface | Actual role | Current processing reality |
|---|---|---|
| `app/src-tauri/gen/android` | Tauri-generated React shell | Does not spawn Python/uv/FFmpeg on Android; React uses remote Gateway HTTP processing |
| `android/` | Separate Kotlin/Compose application | Has local simplified processing and remote processing, but currently accepts local `content://`/`file://` sources only |

The recommended short path is to select the Tauri Android shell as the first official remote-processing APK. Keep Python, model loading, FFmpeg, scoring, captions, rendering, and durable jobs on the Gateway. Do not port the desktop pipeline into Android in the first delivery.

## Confirmed working areas

`Gateway` has FastAPI routes for health/capabilities, source upload/download, processing jobs, polling, cancellation, retry, resume, artifact serving, and restart recovery. It persists job state and transition history in SQLite and executes the Python pipeline as a subprocess.

The Python pipeline has an ordered stage graph from ingest through ASR, diarization, events, candidates, scoring, camera, and rendering. Checkpoints are atomic JSON files with schema validation and artifact checks. The renderer produces verified 9:16 H.264/AAC MP4s and can burn ASS captions when a caption-capable FFmpeg is available.

React typecheck and Vite production build succeeded. Gateway tests passed 35 tests, and pipeline tests passed 91 tests. Windows CI contains a stronger desktop validation path for `uv`, FFmpeg, pipeline tests, NSIS packaging, silent install, and launch. Android CI currently validates only unit tests, lint, and debug APK assembly.

## Critical blockers

1. The Native Android Worker and `ProcessingEngine` reject YouTube and HTTPS sources before the Gateway route is selected, despite React/Android documentation describing URL-based remote processing. This is the highest Android contract mismatch.
2. There is no verified Android APK → Gateway → real processing job → MP4 E2E run in the repository or in this session.
3. Tauri Android intentionally cannot run the desktop Python/uv runtime locally; Native Android does not implement desktop-equivalent WhisperX, diarization, active-speaker, ASS/libass rendering, or the full scoring graph.
4. Gateway live social adapters and OAuth are not implemented outside `PROVIDER_MODE=mock`; Instagram/Gateway publishing is not production-ready.
5. Android CI produces only debug APKs. Release signing is conditional on local/CI keystore variables and is not validated by the workflow.
6. Gateway capability checks can report structural pipeline readiness while heavy runtime imports/models may still fail when a real worker starts.
7. Native Android and Tauri Android have different package identities and different processing contracts. One official Android surface must be selected before substantial implementation.

## Minimal implementation recommendation for the next session

If the target is a Tauri-based APK, implement only deployment and E2E verification first: provision the Gateway with Python 3.12, pipeline dependencies, FFmpeg/ffprobe, model cache policy, writable storage, `PUBLIC_BASE_URL`, gateway auth, and server-side Gemini key; build the generated Tauri Android project with Android SDK 36; run a short real YouTube job; verify polling, artifact URL authorization, MP4 download, and playback.

If the target is the Native Compose APK, make the smallest contract correction: accept HTTPS/HTTP sources when a valid Gateway is configured, add a direct-URL branch to `ProcessingGatewayClient`, retain upload for local URIs, persist the remote job ID, and add a real E2E test. Do not copy the Python pipeline into Android.

## Files produced by this audit

- `docs/ANDROID_AUDIT.md` — full evidence-based audit, proposed architecture, blockers, minimal path, and phased execution plan.
- `MANUS_HANDOFF.md` — this short handoff.

## Verification notes

The local Android test command was attempted with `cd android && ./gradlew :app:testDebugUnitTest --no-daemon`. Gradle downloaded successfully, but execution stopped before tests because the environment had no Android SDK (`SDK location not found`). Temporary build/cache outputs were removed before committing.

See `docs/ANDROID_AUDIT.md` for repository-file references and the complete test matrix.
