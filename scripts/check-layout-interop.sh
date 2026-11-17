#!/usr/bin/env bash
# Purpose: semantic check that keyboard layout JSON files mean the same keyboard on Songyan iOS and Android.
#          Decodes every layout with both platforms' own decoders, dumps rows / keys / actions / widths / rails /
#          splits / top-level fields canonically, diffs the dumps, and runs both round trips
#          (Android encode -> iOS decode -> iOS encode -> Android decode, and the reverse).
#          Also compares the built-in layouts of the two platforms and the catalog (pages, pairings).
# Usage:   scripts/check-layout-interop.sh <ios-repo> [work-dir] [extra-layout.json ...]
#          <ios-repo> is only read. work-dir must not exist or be empty (default: a fresh temp dir; kept so the
#          dumps can be inspected). Extra files are layouts to test as well (e.g. hand-written or exported ones).
#          Exit 1 when any mismatch is found; the report is <work-dir>/report.txt. Needs a macOS Swift
#          toolchain (Xcode) and the Android build environment (.local/env.sh when present).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"
ios="$(cd "$1" && pwd)"
work="${2:-$(mktemp -d "${TMPDIR:-/tmp}/layout-interop.XXXXXX")}"
shift $(( $# > 1 ? 2 : 1 ))
mkdir -p "$work"
if [ -n "$(ls -A "$work")" ]; then
    echo "work dir $work is not empty; use a new one" >&2
    exit 2
fi
work="$(cd "$work" && pwd)"
extras=("$@")

[ -f "$root/.local/env.sh" ] && source "$root/.local/env.sh"

mkdir -p "$work/inputs" "$work/i1-enc" "$work/a1-enc" "$work/i2-enc" "$work/a2-enc"
echo "== iOS export (built-ins and custom fixtures)"
"$here/ios-interop.sh" "$ios" export "$work/ios-export" >/dev/null
for f in "$work"/ios-export/layout-*.json; do cp "$f" "$work/inputs/ios-$(basename "$f")"; done
cp "$work/ios-export/catalog/layouts.json" "$work/inputs/catalog-ios-custom.json"
for f in ${extras[@]+"${extras[@]}"}; do cp "$f" "$work/inputs/extra-$(basename "$f")"; done

gradle_step() {
    (cd "$root" && SONGYAN_LAYOUT_INTEROP_DIR="$work" SONGYAN_LAYOUT_INTEROP_STEP="$1" \
        ./gradlew --no-build-cache -q :core:layout:cleanTest :core:layout:test \
        --tests 'app.songyan.ime.layout.LayoutInteropCheckTest')
}
ios_dump() { "$here/ios-interop.sh" "$ios" dump "$1" "$2" >/dev/null; }
# Copies the re-encoded files of a dump directory under their original names, as the next step's input.
collect() { for f in "$1"/*.enc.json; do [ -e "$f" ] && cp "$f" "$2/$(basename "${f%.enc.json}")"; done; return 0; }

echo "== Android: built-ins, authored catalog, dump + encode of every input"
gradle_step first
echo "== iOS: dump + encode of every input"
ios_dump "$work/inputs" "$work/i1"
collect "$work/i1" "$work/i1-enc"
collect "$work/a1" "$work/a1-enc"
echo "== iOS: decode what Android encoded (Android -> iOS -> iOS)"
ios_dump "$work/a1-enc" "$work/i2"
collect "$work/i2" "$work/i2-enc"
echo "== Android: decode what iOS encoded (iOS -> Android), and what iOS re-encoded (Android -> iOS -> Android)"
gradle_step second
collect "$work/a2" "$work/a2-enc"
echo "== iOS: decode what Android re-encoded (iOS -> Android -> iOS)"
ios_dump "$work/a2-enc" "$work/i3"

python3 -I "$here/check-layout-interop.py" "$work" | tee "$work/report.txt"
exit "${PIPESTATUS[0]}"
