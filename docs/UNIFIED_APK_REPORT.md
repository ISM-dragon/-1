# ISM Unified APK Report

## Decision

The APK binaries from v0.6 through v0.9.3 were not concatenated or layered. They were successive builds of the source tree. The repository was fast-forwarded to the newest `origin/main` at commit `3eb13be`, which already includes the v0.10 Gateway/provider-worker changes and the synced native Android source. The canonical single APK for the Tauri/React Android workflow is built from `app/` at version `0.10.1`.

## Included source capabilities

The unified source contains Source Library for video/channel/playlist URLs, remote Processing Engine and TEST SYSTEM, Gateway health/capabilities/diagnostics, server-side Gemini routing, provider registry and secret vault, persistent processing/source workers, personal taste reranking, Social Hub, account health, scheduling, analytics snapshots, pipeline checkpoints, result retrieval, and Android-safe guards.

The repository also retains the synced `android/` native module as source for the Kotlin/Jetpack Compose application. It is not bundled into the Tauri APK as a second binary; two Android application runtimes cannot be merged by combining APK files. If a future product decision selects the native Kotlin runtime as canonical, its missing Tauri-only screens must be ported deliberately rather than copied as binaries.

## Build

| Item | Value |
|---|---|
| Canonical runtime | Tauri/React Android client + Personal FastAPI Gateway |
| Source commit before version bump | `3eb13be` |
| Unified release version | `v0.10.1` |
| APK type | Universal Debug APK |
| APK SHA-256 | `14b9a642e6ef8bbed1247b6d99f4b405e24b9ba30e560b0af8e2b1877bb8841d` |

## Verification

The frontend production build passed. Gateway Python compilation passed. Gateway unit tests passed. The selected 42 Pipeline logic tests passed. The APK Universal Debug build passed for Android targets. The single APK must still be connected to a personal Gateway with Pipeline dependencies, FFmpeg, and server-side provider credentials for real processing; an APK alone cannot execute the desktop Python runtime.
