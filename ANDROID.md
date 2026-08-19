# Android build

The Tauri Android project is generated under `app/src-tauri/gen/android` and is included in the repository as source. Generated build outputs, staged resources, and native build targets are intentionally excluded from version control.

## Build requirements

Install Node.js, Rust, Java 21 JDK, Android SDK command-line tools, Android platform 36, Android build tools, and NDK 27.0.12077973. Then run:

```sh
cd app
npm install
npx tauri android build --debug --target aarch64
```

The debug APK is produced at:

```text
app/src-tauri/gen/android/app/build/outputs/apk/universal/debug/app-universal-debug.apk
```

## Runtime note

publikclip was originally designed as a desktop application. Its processing pipeline uses a bundled `uv` launcher and Python machine-learning dependencies such as WhisperX, SpeechBrain, ONNX Runtime, OpenCV, and FFmpeg. The Android shell does not spawn that desktop runtime. Instead, the Android `CUT IT` flow now sends a YouTube or HTTPS source to the configured private Processing Gateway, polls the job, and plays the returned MP4 files. Set the Gateway URL and optional token in the Android Studio screen, and configure the server with `PUBLIC_BASE_URL`, `ISM_PROCESSING_ROOT`, and `ISM_PIPELINE_DIR` as documented in `gateway/README.md`. Local file paths remain desktop-only until a dedicated Android-native processing engine is added.

## License

The project remains licensed under AGPL-3.0-or-later. See `LICENSE` and `VENDORED-LICENSES.md`.
