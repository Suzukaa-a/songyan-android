#!/usr/bin/env bash
# The script engine ships as libsongyan-script.so next to the Rime engine. A release APK without it
# installs fine and only fails when a script runs, so check each APK: every ABI that carries the
# Rime engine must also carry the script library.
#
# Usage: scripts/check-apk-script-library.sh <app.apk> [...]
set -euo pipefail

[[ $# -ge 1 ]] || { echo "usage: scripts/check-apk-script-library.sh <app.apk> [...]" >&2; exit 64; }

fail() {
  echo "apk script library: $*" >&2
  exit 1
}

for apk in "$@"; do
  [[ -f "$apk" ]] || fail "missing APK: $apk"
  listing=$(unzip -l "$apk") || fail "cannot list $apk"

  abis=$(awk '{ print $NF }' <<<"$listing" | sed -nE 's|^lib/([^/]+)/libsongyan_rime\.so$|\1|p' | sort -u)
  [[ -n "$abis" ]] || fail "no libsongyan_rime.so in $apk; nothing to pair the script library with"

  for abi in $abis; do
    size=$(awk -v want="lib/$abi/libsongyan-script.so" '$NF == want { print $1 }' <<<"$listing" | head -1)
    [[ -n "$size" ]] || fail "$apk has no lib/$abi/libsongyan-script.so"
    # The QuickJS build is ~900 KB; a stub or a truncated entry would be far smaller.
    [[ "$size" -ge 500000 ]] || fail "$apk lib/$abi/libsongyan-script.so is only $size bytes"
  done
  echo "apk script library: $apk carries libsongyan-script.so for $(wc -w <<<"$abis" | tr -d ' ') ABI(s)"
done
