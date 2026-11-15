#!/usr/bin/env bash
# The release build is shrunk by R8, but the script JNI layer
# (script/src/main/cpp/android/script_jni.c) looks its host class and members up by name from
# native code, and the IME names its script services by class name. A rename or a signature change
# would only show up in a release build, so the R8 mapping has to keep these names and shapes.
#
# The format assumptions are R8's own: a class line `original -> renamed:`, then indented member
# lines ending in `-> name`. A future R8 that changes the format fails here loudly (fail-closed).
#
# Usage: scripts/check-release-mapping.sh app/build/outputs/mapping/release/mapping.txt
set -euo pipefail

mapping=${1:?usage: scripts/check-release-mapping.sh <mapping.txt>}
[[ -f "$mapping" ]] || {
  echo "missing R8 mapping: $mapping" >&2
  exit 1
}

# Windows checkouts and CRLF transport show up in the mapping as trailing CRs, which would turn the
# exact comparisons below into false failures.
cleaned=$(mktemp)
trap 'rm -f "$cleaned"' EXIT
tr -d '\r' <"$mapping" >"$cleaned"
mapping=$cleaned

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

expect_member() {
  local signature=$1 name=$2 line
  line=$(grep -F -- "${signature}" "$mapping" | grep -F -- "-> " | head -1 || true)
  [[ -n "$line" ]] || fail "$signature is missing from the mapping"
  [[ "$line" == *"-> ${name}" ]] || fail "$signature was renamed: $line"
}

expect_class app.songyan.ime.script.ScriptRunHost
expect_class app.songyan.ime.script.ScriptNative
expect_class app.songyan.ime.script.ScriptIsolatedService
expect_class app.songyan.ime.script.ScriptProxyService

# Signatures match ScriptRunHost.kt and the GetMethodID/GetStaticMethodID descriptors in
# script_jni.c; a changed parameter list breaks JNI lookup even when the name survives.
expect_member 'app.songyan.ime.script.ScriptRunHost use()' use
expect_member 'void fetch(java.lang.String,java.lang.String,java.lang.String,byte[],long)' fetch
expect_member 'boolean authorize(java.lang.String,int)' authorize
expect_member 'void log(byte[])' log

echo "release mapping keeps the script JNI entry points"
