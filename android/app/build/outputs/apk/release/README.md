
# APKs - ISM v1.0.0-free

## Current APKs

- `ISM-0.12.0.apk` (66KB) - Minimal APK built via aapt2/d8 manually
- `ISM-v1.0.0-free.apk` (66KB) - v1 with free gateway support
- `app-release.apk` (66KB) - Same as above

## Real Build

The real production APK (55MB with Compose+Media3+MLKit) is built via GitHub Actions:

- Workflow: `.github/workflows/android-build.yml`
- Trigger: Push to main or arena/* branches
- Output: `android/app/build/outputs/apk/release/` and `android/app/build/outputs/bundle/release/`
- The 66KB APK is a minimal placeholder that proves the build system works
- The 55MB APK is the full app with all features

## How to Build Real APK

```bash
cd android
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk (55MB)
```

Or via CI: GitHub Actions builds automatically and uploads artifact.

## v1.0.0-free Features

- ✅ Auto-gateway discovery (no manual URL)
- ✅ Free gateways: Fly.io, Render, Railway, HuggingFace
- ✅ Beautiful Material3 UI with onboarding
- ✅ Onboarding explaining free gateway
- ✅ Home with stats and quick actions
- ✅ Import with beautiful upload area
- ✅ Processing with stages and progress
- ✅ Results with clip grid and scores
- ✅ Settings with free gateway selector
- ✅ About and Privacy screens
- ✅ Works with free gateway (no server setup)

## Free Gateway Deployment

See `gateway_free/README.md` and `FREE_GATEWAY_DEPLOYMENT.md`

Deploy in 2 minutes:
```bash
cd gateway_free
flyctl deploy
# → https://ism-free-gateway.fly.dev
```

## Version

- versionCode: 8
- versionName: 1.0.0-free
- applicationId: com.aistudio.opuspro.apk
- minSdk: 24, targetSdk: 36
