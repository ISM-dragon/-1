# ISM Full Audit and Fix Report

## Scope

This audit covered the React/Tauri Android shell, Studio onboarding and API-key flows, Source Library, Social Hub, FastAPI Gateway, publishing scheduler, remote processing, analytics snapshots, and the Python pipeline contract tests.

## Fixed findings

| Area | Finding | Fix |
|---|---|---|
| Android execution | Android could reach the desktop-only local Pipeline path and show a misleading failure. | CUT IT is guarded on Android, accepts a Gateway URL directly in Studio, saves the setting immediately, and blocks execution until the remote path is configured. Resume is also blocked for local desktop jobs on Android. |
| Startup | A failed `get_setup_state` call could leave a blank boot screen. | Added a visible boot error with retry. Onboarding completion now waits for `mark_onboarded` and reports failures. |
| API keys | Key and Pexels writes had no user-facing error handling and used non-atomic writes. | Added saving states, validation, error messages, and atomic private-file writes. |
| Source downloads | A large source preview could request an unbounded playlist, and polling could wait forever. | Preview defaults to 50 items, download polling has a two-hour deadline and a stop-waiting action, and Gateway limits active source jobs. |
| Gateway restart | Queued/running jobs could remain visible forever after a server restart. | Startup marks interrupted processing and source jobs as failed with a retryable message. |
| SSRF | URL checks did not inspect DNS-resolved addresses. | Public-source validation now rejects private, loopback, link-local, reserved, and multicast IPs both directly and after DNS resolution. |
| Scheduler | Concurrent publish requests could both reset and publish the same post. | Preserved `publishing` during upsert and added an atomic publishing claim. The race smoke test now keeps the account count at one. |
| Social Hub | The app-side due timer could duplicate Gateway's server-side scheduler. | Gateway is the only automatic publisher when configured; the old local publish path was removed. |
| Account integrity | Posts could be scheduled without a connected account or with a mismatched platform. | Scheduled and automatic posts require a matching connected account. |
| Provider mode | Mock OAuth and mock publication routes could appear available in live mode. | Mock routes are disabled outside mock mode and live adapters return explicit not-configured errors. |
| Release metadata | Cargo crate metadata lagged behind the app version. | Rust, Tauri, npm, lockfile, Gateway, and APK version are aligned at v0.9.2. |

## Verification

The frontend production build passes. Android-targeted Rust `cargo check --target aarch64-linux-android --lib` passes. Gateway unit tests pass. The selected lightweight Pipeline contract suite passes with 42 tests. npm production and full audits reported no vulnerabilities at audit time. Full Pipeline installation was not used because WhisperX/Torch dependencies exhausted the sandbox disk during the first attempt; the disk was cleaned and the logic-focused suite was run separately.

## Operational limits

Android still requires a running Processing Gateway for video clipping because the desktop Python runtime is not an Android-native runtime. Real social publishing remains disabled in the default mock mode and requires provider-approved OAuth adapters and credentials. The system intentionally does not rotate IP addresses or use proxies to bypass provider enforcement.

## v0.9.3 Android processing fix

The Android path now tests `/health`, `/v1/processing/capabilities`, `/v1/diagnostics/pipeline`, and `/v1/diagnostics/gemini` before creating a processing job. Studio exposes a dedicated Processing Engine section with `TEST SYSTEM`, actionable readiness states, and LAN Debug guidance. The Gateway reads Gemini only server-side from `PUBLIKCLIP_GEMINI_API_KEY` or an ignored `gateway/secrets/gemini.key` file, then maps it into `PUBLIKCLIP_GEMINI_API_KEY` for the existing Pipeline process. The Android client never transmits the Gemini key.

A diagnostics smoke test confirmed JSON responses for health, capabilities, Gemini-not-configured, and Pipeline readiness without returning API keys, filesystem paths, or secrets. The actual end-to-end clip test still requires a user-controlled Gateway with WhisperX/Python 3.12, FFmpeg, a valid Gemini key, and a real YouTube URL; it cannot be honestly completed inside this sandbox without those private runtime prerequisites.
