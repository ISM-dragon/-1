#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

fail() {
  echo "verify: $*" >&2
  exit 1
}

run_step() {
  local name="$1"
  shift
  echo
  echo "==> $name"
  "$@"
}

printf '%s\n' 'PublikClip verification'
printf 'root: %s\n' "$ROOT_DIR"
printf 'python: '; python3 --version
printf 'node: '; node --version

if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" && ! -f "$ROOT_DIR/android/local.properties" ]]; then
  echo "verify: Android SDK is not configured; Android checks will be skipped." >&2
  ANDROID_AVAILABLE=0
else
  ANDROID_AVAILABLE=1
fi

run_step "Python regression suite" python3 -m pytest -q

if [[ -d "$ROOT_DIR/app/node_modules" ]]; then
  run_step "Frontend production build" bash -c "cd '$ROOT_DIR/app' && npm run build"
else
  run_step "Frontend dependency install and production build" bash -c "cd '$ROOT_DIR/app' && npm ci && npm run build"
fi

if [[ "$ANDROID_AVAILABLE" -eq 1 ]]; then
  [[ -x "$ROOT_DIR/android/gradlew" ]] || fail "android/gradlew is not executable"
  run_step "Android unit tests" bash -c "cd '$ROOT_DIR/android' && ./gradlew :app:testDebugUnitTest --no-daemon --stacktrace"
  run_step "Android lint" bash -c "cd '$ROOT_DIR/android' && ./gradlew :app:lint --no-daemon --stacktrace"
  run_step "Android release assembly" bash -c "cd '$ROOT_DIR/android' && ./gradlew :app:assembleRelease --no-daemon --stacktrace"
else
  echo
  echo "==> Android checks skipped: configure ANDROID_HOME/ANDROID_SDK_ROOT or android/local.properties."
fi

echo
echo "Verification completed. Device E2E, provider readiness, release signing, and large-media tests require their documented external prerequisites."
