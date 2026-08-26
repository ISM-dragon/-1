# ISM Android Mobile-Port Integration Report

## Result

The available repository branches were merged into the local `main` branch, and the resulting branch is eight commits ahead of `origin/main`. The requested refs were not present in `ISM-dragon/-1`, so the closest available branches were used and recorded explicitly.

| Requested role | Available branch used | Merge commit |
|---|---|---|
| On-device AI | `origin/agent/ai-media` | `5670023` |
| Compose/captions UI | `origin/agent/android-ui` | `1da0e53` |
| CI and testing | `origin/agent/qa` | `377c93f` |
| Pipeline integration | Local implementation | `57e809f` |
| Test and golden-image correction | Local implementation | `9b41753` |

## Implemented integration

The Android app now exposes `LocalASR` and `AudioEventDetector` contracts, orchestrated by `OnDevicePipeline`. `VideoProcessingWorker` selects the local analysis route when no Gateway is configured, runs timestamped transcription and MediaCodec audio-event detection, generates interest curves and candidate clips, and persists real transcript-derived `AnimatedWord` timing data into Room. The Compose caption editor now regenerates captions from the selected project’s actual source URI rather than passing the transcript text as mock audio input.

The merged Compose workflow also received the missing repository render APIs. `ClipRenderWorker` can enqueue Media3 exports, burn in persisted caption cues, and update the clip’s output path. The corrupted Roborazzi golden PNG was regenerated from the merged UI.

> **Implementation caveat:** audio-event extraction and candidate analysis execute locally, but the available repository did not contain an on-device ASR model implementation. The `LocalASR` adapter therefore reuses the existing configured Whisper HTTP service behind the new interface. A bundled on-device model can be substituted later without changing the WorkManager or Compose contracts.

## Verification

| Check | Result |
|---|---|
| `:app:compileDebugKotlin` | Passed |
| `:app:testDebugUnitTest` | Passed; full unit suite completed with 53 tests |
| `:app:recordRoborazziDebug` | Passed; regenerated valid `greeting.png` |
| `:app:verifyRoborazziDebug` | Passed |
| `:app:connectedDebugAndroidTest` | Blocked: no connected device; the sandbox emulator cannot start because `/dev/kvm` is unavailable |
| `:app:assembleRelease` | Passed |
| APK signature | Passed; APK Signature Scheme v2, one signer |

## Release artifact

The signed Release APK is available here:

[Download the signed Release APK](https://files.manuscdn.com/user_upload_by_module/session_file/310519663382650362/RlhkVntTIzMDndgf.apk)

| Property | Value |
|---|---|
| File | `app-release.apk` |
| Size | 55,776,959 bytes, approximately 54 MB |
| SHA-256 | `326926ae938f2de2cc7a24f4c168e95dc0fb2f1b94237314ca8999d51018cf52` |
| Signing alias | `upload` |
| Certificate subject | `CN=ISM Mobile Release, OU=ISM, O=ISM, L=Sandbox, ST=NA, C=US` |

The APK was signed with a newly generated temporary PKCS12 release keystore for this build. It is suitable for verification/testing, but the keystore should be replaced with the organization’s production signing key before publishing or shipping updates.

## Repository publication status

The local `main` branch was fast-forwarded successfully. Publishing to GitHub was attempted but rejected because the configured GitHub credentials were invalid (`Invalid username or token`). The completed local branch and commits remain available in the workspace at `/home/ubuntu/ISM-dragon`.
