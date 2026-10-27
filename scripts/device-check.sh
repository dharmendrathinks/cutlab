#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./scripts/gradle.sh :app:assembleDebug :app:assembleDebugAndroidTest
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -n "$sdk" ]]; then
  adb="$sdk/platform-tools/adb"
elif command -v adb >/dev/null 2>&1; then
  adb="$(command -v adb)"
elif [[ -x "$HOME/Library/Android/sdk/platform-tools/adb" ]]; then
  adb="$HOME/Library/Android/sdk/platform-tools/adb"
else
  echo "Set ANDROID_HOME or put adb on PATH." >&2
  exit 1
fi
"$adb" get-state >/dev/null
mkdir -p artifacts
"$adb" install -r app/build/outputs/apk/debug/app-debug.apk
"$adb" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$adb" shell am instrument -w -r com.dhasharma.cutlab.test/androidx.test.runner.AndroidJUnitRunner | tee artifacts/device-tests.txt
for name in engine-smoke.mp4 engine-reference-baseline.mp4 engine-portrait1080p30.mp4 engine-benchmark.json engine-codec-capabilities.json engine-av-sync.mp4 engine-av-sync.json completion-preview-benchmark.json completion-export-progress.png completion-export-saved.png completion-editor.png completion-effects-export.mp4 ui-failure.png; do
  if "$adb" shell run-as com.dhasharma.cutlab test -f "cache/$name"; then
    "$adb" exec-out run-as com.dhasharma.cutlab cat "cache/$name" > "artifacts/$name"
  fi
done
grep -Eq '^OK \([0-9]+ tests\)' artifacts/device-tests.txt
