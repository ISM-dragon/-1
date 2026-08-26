# Environment Evidence

**Date:** 2026-08-26 (user timezone GMT+1)

| Check | Result | Evidence |
|---|---|---|
| Host | Sandbox Linux x86_64 | `uname -a` in session `env_check` |
| Java runtime | PASS, OpenJDK 21 initially; OpenJDK 17 installed for Android build | `/usr/lib/jvm/java-17-openjdk-amd64/bin/javac -version` → `javac 17.0.20` |
| Android SDK | PASS after installation | `/home/ubuntu/android-sdk`; platform `android-36`; build-tools `36.0.0`; platform-tools `37.0.1` |
| ADB | PASS as host tool, no target attached | `adb devices -l` returned no devices |
| FFmpeg/FFprobe | PASS | system FFmpeg 6.1.1 / FFprobe 6.1.1 |
| Docker | NOT AVAILABLE | `docker` command not found |
| Android unit tests | PASS | `./gradlew :app:testDebugUnitTest --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx900m`; `BUILD SUCCESSFUL` |
| Release APK build | PASS | `./gradlew :app:assembleRelease --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1400m`; `BUILD SUCCESSFUL` |
| Release signing | BLOCKED / unsigned | `android/app/build/outputs/apk/release/app-release-unsigned.apk`; no release keystore/passwords supplied |
| Physical Android device | BLOCKED | No device/emulator was present in `adb devices -l`; therefore install, UI, and on-device acceptance cannot be claimed |

The first combined build attempt exceeded the sandbox memory/time envelope. A conservative, sequential rerun passed unit tests and release assembly. No source or architecture changes were made to obtain the build.
