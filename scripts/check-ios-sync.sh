#!/usr/bin/env bash
# Verifies that every file listed in scripts/ios-sync-manifest matches the content of the
# iOS commit recorded in native/UPSTREAM (read with git, never from the iOS working tree,
# so uncommitted edits there cannot mask or fake drift).
# Usage: scripts/check-ios-sync.sh /path/to/songyan-ios
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ios_root=${1:?usage: scripts/check-ios-sync.sh /path/to/songyan-ios}
failures=0

commit=$(tr -d '[:space:]' < "$root/native/UPSTREAM" 2>/dev/null || true)
if [[ -z "$commit" ]]; then
  echo "native/UPSTREAM is missing or empty" >&2
  exit 1
fi
if ! git -C "$ios_root" cat-file -e "$commit^{commit}" 2>/dev/null; then
  echo "commit $commit from native/UPSTREAM is not in $ios_root (fetch it first)" >&2
  exit 1
fi

scratch=$(mktemp -d "${TMPDIR:-/tmp}/ios-sync-check.XXXXXX")
trap 'rm -rf "$scratch"' EXIT

while read -r ios_path android_path; do
  [[ -z "$ios_path" || "$ios_path" == \#* ]] && continue
  dst="$root/$android_path"
  if [[ "$ios_path" == */ ]]; then
    tree_path=${ios_path%/}
    [[ -d "$dst" ]] || { echo "missing tree: $dst" >&2; failures=$((failures + 1)); continue; }
    expected="$scratch/$(echo "$tree_path" | tr '/' '_')"
    depth=$(awk -F/ '{print NF}' <<<"$tree_path")
    mkdir -p "$expected"
    if ! git -C "$ios_root" archive "$commit" -- "$tree_path" | tar -x -C "$expected" --strip-components="$depth"; then
      echo "missing in $commit: $ios_path" >&2
      failures=$((failures + 1))
      continue
    fi
    if ! diff_out=$(diff -r -x '__pycache__' -x '.DS_Store' "$expected" "$dst" 2>&1); then
      echo "drift: $dst" >&2
      echo "$diff_out" | head -20 >&2
      failures=$((failures + 1))
    fi
  else
    if ! git -C "$ios_root" show "$commit:$ios_path" > "$scratch/file" 2>/dev/null; then
      echo "missing in $commit: $ios_path" >&2
      failures=$((failures + 1))
    elif ! cmp -s "$scratch/file" "$dst"; then
      echo "drift: $dst" >&2
      failures=$((failures + 1))
    fi
  fi
done < "$root/scripts/ios-sync-manifest"

if (( failures > 0 )); then
  echo "iOS sync check failed ($failures differences)" >&2
  exit 1
fi
echo "iOS sync check passed against $commit"
