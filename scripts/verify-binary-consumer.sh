#!/usr/bin/env bash
set -euo pipefail
BINARY_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
BINARY_TARGET=${1:-jvm}
case "$BINARY_TARGET" in
  jvm) BINARY_PUBLICATION=Jvm; BINARY_TEST=jvmTest ;;
  js) BINARY_PUBLICATION=Js; BINARY_TEST=jsNodeTest ;;
  wasm) BINARY_PUBLICATION=WasmJs; BINARY_TEST=wasmJsNodeTest ;;
  ios) BINARY_PUBLICATION=IosSimulatorArm64; BINARY_TEST=iosSimulatorArm64Test ;;
  *) printf 'Usage: %s [jvm|js|wasm|ios]\n' "$0" >&2; exit 2 ;;
esac
BINARY_WORK=$(mktemp -d "${TMPDIR:-/tmp}/koma-binary-consumer.XXXXXX")
trap 'rm -rf "$BINARY_WORK"' EXIT
value() { python3 "$BINARY_ROOT/verification/published-consumer/config.py" "$BINARY_ROOT" "$1"; }
BINARY_GROUP=$(value group)
BINARY_VERSION=$(value version)
COMMON=(-Pbinary.target="$BINARY_TARGET" -Pbinary.repository="$BINARY_WORK/frozen" -Pfork.repository="$BINARY_WORK/fork" -Pfork.group="$BINARY_GROUP" -Pfork.version="$BINARY_VERSION" -Pcoroutines.version="$(value coroutines)")
# Freeze the compiler together with the published stable baseline, independent of fork upgrades.
"$BINARY_ROOT/gradlew" -p "$BINARY_ROOT/verification/binary-consumer/legacy" "${COMMON[@]}" -Pcompiler.version=2.3.20 publishKotlinMultiplatformPublicationToFrozenRepository "publish${BINARY_PUBLICATION}PublicationToFrozenRepository" verifyLegacyGraph --no-configuration-cache --max-workers=2
python3 "$BINARY_ROOT/verification/binary-consumer/frozen_hashes.py" snapshot "$BINARY_WORK/frozen" "$BINARY_WORK/hashes.json"
# Control: the frozen client must work with the baseline itself before testing an upgrade.
"$BINARY_ROOT/gradlew" -p "$BINARY_ROOT/verification/binary-consumer/runner" "${COMMON[@]}" -Pcompiler.version=2.3.20 -Pbinary.upgraded=false "$BINARY_TEST" verifyRunnerGraph --no-configuration-cache --max-workers=2
"$BINARY_ROOT/gradlew" -p "$BINARY_ROOT" :koma-core:publishKotlinMultiplatformPublicationToConsumerRepository ":koma-core:publish${BINARY_PUBLICATION}PublicationToConsumerRepository" -Pkoma.consumer.repository="$BINARY_WORK/fork" --no-configuration-cache --max-workers=2
# Only the runner is compiled here. It has no legacy source build/project dependency.
"$BINARY_ROOT/gradlew" -p "$BINARY_ROOT/verification/binary-consumer/runner" "${COMMON[@]}" -Pcompiler.version="$(value kotlin)" -Pbinary.upgraded=true "$BINARY_TEST" verifyRunnerGraph --rerun-tasks --no-configuration-cache --max-workers=2
python3 "$BINARY_ROOT/verification/binary-consumer/frozen_hashes.py" verify "$BINARY_WORK/frozen" "$BINARY_WORK/hashes.json"
printf 'Frozen stable 4.0.0 binary upgrade passed: %s\n' "$BINARY_TARGET"
