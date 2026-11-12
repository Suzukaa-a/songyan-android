#!/usr/bin/env bash
# Builds the standalone Android S0 probe APK from the Java sources next to this script, and the
# minimal ordinary-app APK that selftest.sh uses to check the permission gate on the probe's
# control entry.
#
#   # a git worktree has no .local/env.sh of its own; point at the main checkout's:
#   source /path/to/songyan-android/.local/env.sh
#   scripts/probes/android-s0/build.sh          # prints the probe APK path
#   scripts/probes/android-s0/build.sh other    # prints the ordinary-app APK path
#
# Only the SDK build-tools (aapt2, d8, apksigner) and javac are used, so the probe stays out of
# the Gradle build and out of the app's manifests. The APKs are signed with the SDK debug keystore
# and written under .local/, which is never committed.
set -euo pipefail

DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$DIR/../../.." && pwd)
: "${ANDROID_HOME:?ANDROID_HOME is unset; source the main checkout .local/env.sh or export it}"
: "${JAVA_HOME:?JAVA_HOME is unset; source the main checkout .local/env.sh or export it}"

OUT=${S0_PROBE_OUT:-$ROOT/.local/s0-probe}
BT=$(printf '%s\n' "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)
BT=${BT%/}
AJAR=$ANDROID_HOME/platforms/android-37.0/android.jar
KS=${ANDROID_USER_HOME:-${HOME}/.android}/debug.keystore

# Intermediates live in one directory under .local/. Deleting directories permanently is not
# allowed here, so the build moves its workspace to the system trash when it finishes.
to_trash() {
  local target=$1 trash
  [ -n "$target" ] || return 0
  [ -d "$target" ] || return 0
  case "$target" in "$OUT"/work.*) ;; *)
    echo "refusing to trash $target" >&2
    return 1
    ;;
  esac
  if [ -d "$HOME/.Trash" ]; then
    trash=$HOME/.Trash
  elif [ -d "${XDG_DATA_HOME:-${HOME}/.local/share}/Trash/files" ]; then
    trash=${XDG_DATA_HOME:-${HOME}/.local/share}/Trash/files
  else
    echo "no system trash directory; leaving $target in place" >&2
    return 0
  fi
  mv "$target" "$trash/$(basename "$target")-$(date +%s)" 2>/dev/null ||
    echo "could not move $target to the trash; leaving it in place" >&2
}

cleanup_work() {
  local work
  for work in "$OUT"/work.*; do
    [ -d "$work" ] && to_trash "$work"
  done
}

require_tools() {
  local need
  for need in "$BT/aapt2" "$BT/d8" "$BT/apksigner" "$AJAR" "$KS" "$JAVA_HOME/bin/javac"; do
    [ -e "$need" ] || { echo "missing: $need" >&2; exit 1; }
  done
}

build() { # src manifest res assets apk
  local srcdir=$1 manifest=$2 resdir=$3 assetsdir=$4 apk=$5 work r
  work=$(mktemp -d "$OUT/work.XXXXXX") || exit 1
  mkdir -p "$work/gen" "$work/classes" "$work/dex"
  if [ -d "$resdir" ]; then
    "$BT/aapt2" compile --dir "$resdir" -o "$work/res.zip"
  fi
  if [ -n "$assetsdir" ]; then
    "$BT/aapt2" link -o "$work/unsigned.apk" -I "$AJAR" --manifest "$manifest" \
      --java "$work/gen" -A "$assetsdir" --min-sdk-version 30 --target-sdk-version 37 \
      "$work/res.zip"
  elif [ -d "$resdir" ]; then
    "$BT/aapt2" link -o "$work/unsigned.apk" -I "$AJAR" --manifest "$manifest" \
      --java "$work/gen" --min-sdk-version 30 --target-sdk-version 37 "$work/res.zip"
  else
    "$BT/aapt2" link -o "$work/unsigned.apk" -I "$AJAR" --manifest "$manifest" \
      --java "$work/gen" --min-sdk-version 30 --target-sdk-version 37
  fi
  find "$srcdir" -name '*.java' > "$work/sources.txt"
  r=$(find "$work/gen" -name 'R.java' 2>/dev/null | head -1)
  if [ -n "$r" ]; then
    "$JAVA_HOME/bin/javac" --release 8 -nowarn -classpath "$AJAR" -d "$work/classes" \
      @"$work/sources.txt" "$r"
  else
    "$JAVA_HOME/bin/javac" --release 8 -nowarn -classpath "$AJAR" -d "$work/classes" \
      @"$work/sources.txt"
  fi
  "$BT/d8" --release --min-api 30 --lib "$AJAR" --output "$work/dex" \
    $(find "$work/classes" -name '*.class')
  cp "$work/unsigned.apk" "$apk"
  ( cd "$work/dex" && zip -q "$apk" classes.dex )
  "$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias androiddebugkey --min-sdk-version 30 "$apk"
  echo "$apk"
}

trap cleanup_work EXIT

case "${1:-main}" in
  main)
    require_tools
    mkdir -p "$OUT"
    build "$DIR/src" "$DIR/AndroidManifest.xml" "$DIR/res" "$DIR/assets" \
      "$OUT/app.songyan.s0probe.apk"
    ;;
  other)
    require_tools
    mkdir -p "$OUT"
    build "$DIR/other/src" "$DIR/other/AndroidManifest.xml" "" "" \
      "$OUT/app.songyan.s0probe.other.apk"
    ;;
  *)
    echo "usage: $0 [main|other]" >&2
    exit 2
    ;;
esac
