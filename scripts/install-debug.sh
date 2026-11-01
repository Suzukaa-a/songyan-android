#!/usr/bin/env bash
# Builds and installs the debug APK on the connected device/emulator.
# With --enable-ime it also enables and selects the Songyan input method.
# Usage: scripts/install-debug.sh [--enable-ime]
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
enable_ime=false
if [[ "${1:-}" == "--enable-ime" ]]; then
  enable_ime=true
fi

cd "$root"
./gradlew --console=plain :app:installDebug

if [[ "$enable_ime" == true ]]; then
  adb shell ime enable app.songyan.ime.debug/app.songyan.ime.SongyanInputMethodService
  adb shell ime set app.songyan.ime.debug/app.songyan.ime.SongyanInputMethodService
fi
