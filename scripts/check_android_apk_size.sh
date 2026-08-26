#!/usr/bin/env bash
set -euo pipefail

apk_path="${1:-android/app/build/outputs/apk/release/app-release.apk}"
max_bytes=$((150 * 1024 * 1024))

if [[ ! -f "$apk_path" ]]; then
  echo "Release APK not found: $apk_path" >&2
  exit 1
fi

size_bytes="$(stat -c '%s' "$apk_path")"
size_mib="$(awk -v bytes="$size_bytes" 'BEGIN { printf "%.2f", bytes / 1024 / 1024 }')"
limit_mib="$(awk -v bytes="$max_bytes" 'BEGIN { printf "%.0f", bytes / 1024 / 1024 }')"

echo "Release APK: $apk_path"
echo "APK size: ${size_mib} MiB (${size_bytes} bytes)"
echo "APK size limit: ${limit_mib} MiB (${max_bytes} bytes)"

if (( size_bytes > max_bytes )); then
  echo "APK size check failed: artifact exceeds ${limit_mib} MiB." >&2
  exit 1
fi

echo "APK size check passed."
