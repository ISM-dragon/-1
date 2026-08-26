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

publikclip was originally designed as a desktop application. Its production pipeline uses Python machine-learning dependencies such as WhisperX, SpeechBrain, ONNX Runtime, OpenCV, and FFmpeg. The Tauri Android shell does not spawn that desktop runtime. The standalone native Android APK now uses the same private Processing Gateway for every video job: it uploads a selected local `content://` or `file://` video, polls the durable job, downloads the returned MP4 files, and imports them into the local Room library. Set the Gateway URL and token in the Android Studio screen, then press `TEST SYSTEM`. The card checks Gateway, Pipeline, Gemini, and FFmpeg before a job is created. Configure the server with `PUBLIC_BASE_URL`, `ISM_PROCESSING_ROOT`, `ISM_PIPELINE_DIR`, `GATEWAY_TOKEN`, and server-side `GEMINI_API_KEY` as documented in `gateway/README.md`. Android never sends the Gemini secret; it sends only the selected media and Gateway bearer token. For a private deployment set `REQUIRE_GATEWAY_TOKEN=true` and use HTTPS outside a trusted LAN. The legacy `ProductionVideoPipeline` source remains present but is not selected by the APK worker.

## License

The project remains licensed under AGPL-3.0-or-later. See `LICENSE` and `VENDORED-LICENSES.md`.
