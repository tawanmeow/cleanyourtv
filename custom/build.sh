#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
(
  cd "$project_root/launcher"
  bash ./gradlew --no-daemon testReleaseUnitTest lintRelease assembleRelease
)
mkdir -p "$project_root/distribution/apk"
cp "$project_root/launcher/app/build/outputs/apk/release/app-release.apk" "$project_root/distribution/apk/CleanYourTV.apk"
"${CYTV_PYTHON:-python3}" "$project_root/tools/update_custom_manifest.py"
echo "Ready: $project_root/distribution/apk/CleanYourTV.apk"
