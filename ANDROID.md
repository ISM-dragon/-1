# Android build

The repository contains a native Kotlin/Jetpack Compose Android client under `android/`. The canonical Android path is `ContractJobRepository` → `GatewayProcessingWorker` → `ApiContractClient` → private Processing Gateway. The Android artifact is intentionally separate from the desktop Tauri app, Python pipeline, and private Processing Gateway.

## Release requirements

Build tools are required only on the build machine: JDK 21, Android SDK 36, Android build-tools 36, and the Gradle wrapper included in this repository. The final APK does not embed Python, `uv`, Node, Rust, or the desktop FFmpeg runtime.

For an installable release APK, use a product-owned keystore outside the repository:

```sh
cd android
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export KEYSTORE_PATH=/secure/path/release-upload.jks
export STORE_PASSWORD='***'
export KEY_PASSWORD='***'
./gradlew :app:testDebugUnitTest :app:lint :app:assembleRelease
```

The APK is produced at:

```text
android/app/build/outputs/apk/release/app-release-unsigned.apk
```

The release package is `com.aistudio.opuspro.apk`, version `0.12.0`, `versionCode=7`. The current reproducible unsigned artifact is `55,690,915` bytes with SHA-256 `58bc1d81adbe30fa2920e0555baa91dfd6b019578d81d6ddf14871658160e06f`. Never commit the keystore or its passwords; the unsigned artifact must be replaced with the product's release key before public distribution.

## Runtime boundary

Android is a client for the private Processing Gateway. The `ProcessingEngine` has no local fallback. `GatewayProcessingWorker` uploads the selected video, starts and polls a remote job, resumes interrupted or retry-waiting jobs with a bounded policy, downloads returned MP4 clips, and persists job/project state in Room. The Gateway owns the Python pipeline, FFmpeg, Gemini credentials, and other provider secrets. Android accepts only HTTPS Gateway URLs and stores the Gateway token through the app's secure secret storage.

The device picker uses Android Photo Picker with a `GetContent` fallback. The selected URI is copied into app-private `filesDir/source_media` before background work begins. `ApiContractClient` uses the Gateway resumable upload resource API with SHA-256, persisted offsets, and `Content-Range`; a repeated upload initialization resumes or reuses the existing server session. Remote outputs are written to app-private `filesDir/gateway_exports`. No broad media-library or public-storage permission is required.

## Verification

The committed source passes Python regression (`174 passed, 1 skipped`), Kotlin/Java compilation, Android Lint, and release assembly in the verification environment. The Android test source compiles, but full Robolectric execution was not completed because the test worker remained blocked while fetching an external Android artifact; device-level install/launch/restart/file-picker/background verification remains a release-gate item for a physical device or stable accelerated emulator. The current release artifact is intentionally unsigned and contains no Python, `uv`, Node, Rust/Cargo, or FFmpeg desktop runtime.

See [`docs/RELEASE.md`](docs/RELEASE.md) and [`MANUS_HANDOFF.md`](MANUS_HANDOFF.md) for the complete release matrix, artifact digest, permissions, lifecycle behavior, and test limitations.

## License

The project remains licensed under AGPL-3.0-or-later. See `LICENSE` and `VENDORED-LICENSES.md`.
