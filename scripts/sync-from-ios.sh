#!/usr/bin/env bash
# Copies the pinned iOS sources and resources listed in scripts/ios-sync-manifest
# into this repository and records the source commit in native/UPSTREAM.
# Usage: scripts/sync-from-ios.sh /path/to/songyan-ios
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ios_root=${1:?usage: scripts/sync-from-ios.sh /path/to/songyan-ios}

if [[ ! -f "$ios_root/ARCHITECTURE.md" ]]; then
  echo "not an iOS repository: $ios_root" >&2
  exit 1
fi

# UPSTREAM records HEAD, and check-ios-sync.sh reads that commit; copying uncommitted
# edits would record content that exists nowhere in git.
synced_paths=$(awk '!/^#/ && NF {print $1}' "$root/scripts/ios-sync-manifest")
# shellcheck disable=SC2086
dirty=$(git -C "$ios_root" status --porcelain --untracked-files=all -- $synced_paths)
if [[ -n "$dirty" ]]; then
  echo "refusing to sync: the iOS worktree has uncommitted changes in synced paths:" >&2
  echo "$dirty" | head -20 >&2
  exit 1
fi

while read -r ios_path android_path; do
  [[ -z "$ios_path" || "$ios_path" == \#* ]] && continue
  src="$ios_root/$ios_path"
  dst="$root/$android_path"
  if [[ "$ios_path" == */ ]]; then
    [[ -d "$src" ]] || { echo "missing directory: $src" >&2; exit 1; }
    mkdir -p "$dst"
    # A dry run finds obsolete destinations without permanently removing local files.
    preview=$(rsync -ani --delete --exclude '__pycache__' --exclude '.DS_Store' "$src" "$dst")
    if [[ "$preview" == *"*deleting "* ]]; then
      echo "move obsolete synchronized files to Trash, then run sync again:" >&2
      printf '%s\n' "$preview" >&2
      exit 1
    fi
    rsync -a --exclude '__pycache__' --exclude '.DS_Store' "$src" "$dst"
  else
    [[ -f "$src" ]] || { echo "missing file: $src" >&2; exit 1; }
    mkdir -p "$(dirname "$dst")"
    cp "$src" "$dst"
  fi
done < "$root/scripts/ios-sync-manifest"

git -C "$ios_root" rev-parse HEAD > "$root/native/UPSTREAM"
echo "synced from $ios_root at $(cat "$root/native/UPSTREAM")"
