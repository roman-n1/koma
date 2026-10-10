#!/usr/bin/env bash
set -euo pipefail

task_root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$task_root"
seconds="${1:-300}"
python3 - "$seconds" <<'PY'
import sys
assert 3 <= int(sys.argv[1]) <= 3600
PY

./gradlew :resource-soak:assembleAndroidDeviceTest -PsoakSeconds="$seconds" --max-workers=2 --console=plain
output="verification/resource-soak/build/reports/resource-soak-device"
mkdir -p "$output"
adb install -t -r verification/resource-soak/build/outputs/apk/androidTest/resource-soak-androidTest.apk
trap 'adb uninstall actron.resources.soak.test >/dev/null 2>&1 || true' EXIT

# Keep the APK installed until its app-owned reports have been pulled. AGP/UTP's connected
# test cleanup can uninstall it before a subsequent workflow step sees those files.
instrument_status=0
adb shell am instrument -w -r -e class actron.soak.AndroidReports \
  actron.resources.soak.test/androidx.test.runner.AndroidJUnitRunner > "$output/instrumentation.txt" 2>&1 || instrument_status=$?
pull_status=0
adb exec-out run-as actron.resources.soak.test tar -C files -cf - resource-soak-android | tar -xf - -C "$output" || pull_status=$?
cat "$output/instrumentation.txt"
if [[ "$instrument_status" -ne 0 || "$pull_status" -ne 0 ]]; then exit 1; fi
python3 verification/resource-soak/verify-report.py "$output/resource-soak-android" Android "$seconds" "$output/instrumentation.txt"
