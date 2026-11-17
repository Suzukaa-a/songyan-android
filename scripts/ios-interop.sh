#!/usr/bin/env bash
# Purpose: check that appearance and layout files interoperate with the iOS app's own code.
# Usage:   scripts/ios-interop.sh <ios-repo> export <out-dir>   write iOS-encoded files (the test fixtures)
#          scripts/ios-interop.sh <ios-repo> verify <in-dir>    decode Android-exported files with the iOS decoders
#          scripts/ios-interop.sh <ios-repo> dump <in-dir> <out-dir>   canonical semantic dump + iOS re-encoding of
#                                                                      every layout file; see check-layout-interop.sh
# Needs a macOS Swift toolchain. The iOS repo is only read.
set -euo pipefail
# Resource compilation needs Xcode's toolchain; the Command Line Tools alone lack xcstringstool.
if ! xcrun --find xcstringstool >/dev/null 2>&1 && [ -d /Applications/Xcode.app ]; then
    export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
    unset SDKROOT
fi
here="$(cd "$(dirname "$0")" && pwd)"
ios="$(cd "$1" && pwd)"
mode="$2"
dir="$(mkdir -p "$3" && cd "$3" && pwd)"

stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
cp -R "$here/ios-interop/." "$stage/"
mkdir -p "$stage/Sources/SkinKit" "$stage/Sources/CandidateKit" "$stage/Sources/AppearanceKit"
ln -s "$ios/Packages/SkinKit/Sources/SkinKit/SkinDefinition.swift" "$stage/Sources/SkinKit/"
ln -s "$ios/Packages/CandidateKit/Sources/CandidateKit/CandidateBarDefinition.swift" "$stage/Sources/CandidateKit/"
ln -s "$ios/Packages/AppearanceKit/Sources/AppearanceKit/AppearanceDocument.swift" "$stage/Sources/AppearanceKit/"
# The format code words its errors through each package's own string catalog (Bundle.module).
for kit in SkinKit CandidateKit AppearanceKit; do
    ln -s "$ios/Packages/$kit/Sources/$kit/Resources" "$stage/Sources/$kit/Resources"
done

extra=()
[ -n "${4:-}" ] && extra=("$(mkdir -p "$4" && cd "$4" && pwd)")
IOS_REPO="$ios" swift run --package-path "$stage" --scratch-path "$stage/.build" interop "$mode" "$dir" ${extra[@]+"${extra[@]}"}
