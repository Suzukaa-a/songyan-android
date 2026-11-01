#!/usr/bin/env bash
# Regenerates the app's Baseline Profile and startup profile
# (app/src/release/generated/baselineProfiles/) by running :baselineprofile's journeys on one
# connected API 33+ device or emulator, then reruns the Macrobenchmarks of :benchmark on the
# benchmarkRelease build so baseline numbers can be taken from the same device.
# Usage: scripts/generate-baseline-profile.sh [--serial <adb serial>] [--no-benchmark]
# Benchmark results land in benchmark/build/outputs/connected_android_test_additional_output/.
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
benchmark=true
while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial) export ANDROID_SERIAL="${2:?--serial needs a value}"; shift 2 ;;
    --no-benchmark) benchmark=false; shift ;;
    --help|-h) sed -n '2,7p' "${BASH_SOURCE[0]}" | sed -E 's/^# ?//'; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

cd "$root"
./gradlew --console=plain :app:generateBaselineProfile
if [[ "$benchmark" == true ]]; then
  ./gradlew --console=plain :benchmark:connectedBenchmarkReleaseAndroidTest
fi
