# Android build

The repository contains a native Kotlin/Jetpack Compose Android client under `android/`. The Android artifact is intentionally separate from the desktop Tauri app, Python pipeline, and private Processing Gateway.

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
android/app/build/outputs/apk/release/app-release.apk
```

The release package is `com.aistudio.opuspro.apk`, version `0.10.1`, `versionCode=5`. Never commit the keystore or its passwords. The artifact built during the current handoff used a temporary test signing key outside Git; it must be replaced with the product's release key before public distribution.

## Runtime boundary

Android is a client for the private Processing Gateway. The `ProcessingEngine` has no local fallback. `VideoProcessingWorker` uploads the selected video, starts and polls a remote job, downloads returned MP4 clips, and persists job/project state in Room. The Gateway owns the Python pipeline, FFmpeg, Gemini credentials, and other provider secrets. Android accepts only HTTPS Gateway URLs and stores the Gateway token through the app's secure secret storage.

The device picker uses Android Photo Picker with a `GetContent` fallback. The selected URI is copied into app-private `filesDir/source_media` before background work begins. Remote outputs are written to app-private `filesDir/gateway_exports`. No broad media-library or public-storage permission is required.

## Verification

The current handoff passed unit tests, Android Lint, release assembly, APK v2 signature verification, manifest/package inspection, and an archive scan for Python/`uv`/Node/Rust/Cargo/FFmpeg runtimes. A clean Android 15 AVD was created and an install/launch attempt was made, but this sandbox's software-emulated TCG device exited before stable `sys.boot_completed=1`; therefore device-level install/launch/restart/file-picker/background verification remains a release-gate item for a physical device or stable accelerated emulator.

See [`docs/RELEASE.md`](docs/RELEASE.md) and [`MANUS_HANDOFF.md`](MANUS_HANDOFF.md) for the complete release matrix, artifact digest, permissions, lifecycle behavior, and test limitations.

## License

The project remains licensed under AGPL-3.0-or-later. See `LICENSE` and `VENDORED-LICENSES.md`.
