"""Validate the public ISM product identity and compatible release metadata."""

from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VERSION = (ROOT / "VERSION").read_text(encoding="utf-8").strip()
if not re.fullmatch(r"\d+\.\d+\.\d+", VERSION):
    raise SystemExit(f"Invalid VERSION: {VERSION!r}")

package = json.loads((ROOT / "app" / "package.json").read_text(encoding="utf-8"))
if package.get("version") != VERSION:
    raise SystemExit(f"Desktop version drift: {package.get('version')} != {VERSION}")

android_gradle = (ROOT / "android" / "app" / "build.gradle.kts").read_text(encoding="utf-8")
version_match = re.search(r'versionName\s*=\s*"([^"]+)"', android_gradle)
application_match = re.search(r'applicationId\s*=\s*"([^"]+)"', android_gradle)
if not version_match or version_match.group(1) != VERSION:
    raise SystemExit("Android versionName does not match VERSION")
if not application_match or application_match.group(1).startswith("com.example"):
    raise SystemExit("Android applicationId must not use com.example")

main = (ROOT / "gateway" / "main.py").read_text(encoding="utf-8")
if 'title="ISM Social Gateway"' not in main or 'version="0.11.0"' not in main:
    raise SystemExit("Gateway product/version identity drift detected")

print(f"identity_ok product=ISM version={VERSION} android_application_id={application_match.group(1)} api=v1")
