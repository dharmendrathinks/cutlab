#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/check-repository.py
./scripts/gradle.sh :core:test :app:assembleDebug :app:compileDebugAndroidTestKotlin :app:lintDebug
