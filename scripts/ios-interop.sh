#!/usr/bin/env bash
# Purpose: check that appearance, layout and keyboard-tool files interoperate with the iOS app's own code.
# Usage:   scripts/ios-interop.sh <ios-repo> export <out-dir>   write iOS-encoded files (the test fixtures)
#          scripts/ios-interop.sh <ios-repo> verify <in-dir>    decode Android-exported files with the iOS decoders
#
# Needs a macOS Swift toolchain and a full Xcode: QuickContentKit ships its localized tool messages in
# a String Catalog, which SwiftPM only builds with Xcode's toolchain, so DEVELOPER_DIR must point at a
# full Xcode (xcode-select may point at the Command Line Tools). The iOS repo is only read.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"
ios="$(cd "$1" && pwd)"
mode="$2"

if [ -n "${DEVELOPER_DIR:-}" ]; then
  developer="$DEVELOPER_DIR"
elif developer="$(xcode-select -p 2>/dev/null)"; then
  :
else
  developer=""
fi
case "$developer" in
  *.app/Contents/Developer) ;;
  *)
    printf 'ios-interop: set DEVELOPER_DIR to a full Xcode (e.g. /Applications/Xcode.app/Contents/Developer);\n' >&2
    printf 'ios-interop: it is %s and the QuickContentKit resource bundle needs the Xcode toolchain.\n' "${developer:-unset}" >&2
    exit 1
    ;;
esac
export DEVELOPER_DIR="$developer"
dir="$(mkdir -p "$3" && cd "$3" && pwd)"

# Stage inside the worktree's ignored directory so a failed run leaves nothing in the system temp dir.
mkdir -p "$root/.scratch"
stage="$(mktemp -d "$root/.scratch/ios-interop.XXXXXX")"
printf 'interop staging: %s\n' "$stage"
# The staging tree contains source symlinks; keep cleanup reversible.
cleanup_stage() {
  swift -e 'import Foundation; var result: NSURL?; try FileManager.default.trashItem(at: URL(fileURLWithPath: CommandLine.arguments[1]), resultingItemURL: &result)' "$stage" >/dev/null
}
trap cleanup_stage EXIT
cp -R "$here/ios-interop/." "$stage/"

IOS_REPO="$ios" swift run --package-path "$stage" --scratch-path "$stage/.build" interop "$mode" "$dir"
