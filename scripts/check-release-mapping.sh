#!/usr/bin/env bash
# The release build is shrunk by R8, but the script JNI layer
# (script/src/main/cpp/android/script_jni.c) looks its host class and members up by name from
# native code, and the IME names its script services by class name. A rename would only show up
# in a release build, so the R8 mapping has to keep these names.
# Usage: scripts/check-release-mapping.sh app/build/outputs/mapping/release/mapping.txt
set -euo pipefail

mapping=${1:?usage: scripts/check-release-mapping.sh <mapping.txt>}
[[ -f "$mapping" ]] || {
  echo "missing R8 mapping: $mapping" >&2
  exit 1
}

fail() {
  echo "release mapping: $*" >&2
  exit 1
}

expect_class() {
  local name=$1
  local line
  line=$(grep -E "^${name} -> " "$mapping" || true)
  [[ -n "$line" ]] || fail "$name is missing from the mapping"
  [[ "$line" == "${name} -> ${name}:" ]] || fail "$name was renamed: $line"
}

expect_class app.songyan.ime.script.ScriptRunHost
expect_class app.songyan.ime.script.ScriptNative
expect_class app.songyan.ime.script.ScriptIsolatedService
expect_class app.songyan.ime.script.ScriptProxyService

block=$(awk '/^app\.songyan\.ime\.script\.ScriptRunHost -> /{inside=1; next} inside && /^[^[:space:]#]/{inside=0} inside{print}' "$mapping")
for member in use fetch authorize log; do
  grep -Eq -- "-> ${member}$" <<<"$block" || fail "ScriptRunHost.${member} was renamed or removed"
done

echo "release mapping keeps the script JNI entry points"
