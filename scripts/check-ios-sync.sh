#!/usr/bin/env bash
# Verifies that every file listed in scripts/ios-sync-manifest matches the content of the
# iOS commit recorded in native/UPSTREAM (read with git, never from the iOS working tree,
# so uncommitted edits there cannot mask or fake drift).
# Usage: scripts/check-ios-sync.sh /path/to/songyan-ios
# Paths within trees are compared as raw bytes so Git cannot quote non-ASCII names.
# Empty directories are ignored; manifest entries cannot contain spaces.
set -euo pipefail
export LC_ALL=C

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

excluded_path() {
  case "/$1/" in
    */__pycache__/*|*/.DS_Store/*) return 0 ;;
  esac
  return 1
}

blob_matches() { # commit path local file (a symlink is followed)
  git -C "$ios_root" show "$1:$2" 2>/dev/null | cmp -s - "$3"
}

blob_mode() { # commit path
  git -C "$ios_root" ls-tree "$1" -- "$2" 2>/dev/null | head -1 | cut -d' ' -f1
}

contains_path() { # needle, candidates...
  local needle=$1 candidate
  shift
  for candidate in "$@"; do
    [[ "$candidate" == "$needle" ]] && return 0
  done
  return 1
}

# Compares one manifest tree against the local directory. Prints one line per difference
# (local extra, local missing, unreadable symlink, content or symlink-target difference) and
# returns 1 when there is any.
check_tree() { # tree_path (no trailing slash), local directory
  local tree_path=$1 dst=$2
  local record meta path rel mode line
  local bad=0 count=0 index
  local -a expected=()
  local -a modes=()
  local -a local_files=()
  local -a details=()

  # -z keeps paths exactly as bytes: no quoting of non-ASCII names, no escaping of spaces.
  while IFS= read -r -d '' record; do
    meta=${record%%$'\t'*}
    path=${record#*$'\t'}
    case "$meta" in
      *" blob "*) ;;
      *) continue ;; # a submodule entry is not a file this check can compare
    esac
    rel=${path#"$tree_path"/}
    excluded_path "$rel" && continue
    expected+=("$rel")
    modes+=("${meta%% *}")
  done < <(git -C "$ios_root" ls-tree -r -z "$commit" -- "$tree_path")

  while IFS= read -r -d '' path; do
    rel=${path#./}
    local_files+=("$rel")
  done < <(cd "$dst" && find . \( -type f -o -type l \) -print0)

  for rel in ${local_files[@]+"${local_files[@]}"}; do
    excluded_path "$rel" && continue
    if [[ ${#expected[@]} -eq 0 ]] || ! contains_path "$rel" "${expected[@]}"; then
      details+=("Only in $dst: $rel")
      bad=1
    fi
  done

  for index in ${expected[@]+"${!expected[@]}"}; do
    rel=${expected[$index]}
    mode=${modes[$index]}
    if [[ "$mode" == "120000" ]]; then
      if [[ ! -L "$dst/$rel" ]]; then
        details+=("expected a symlink: $dst/$rel")
        bad=1
      elif [[ "$(readlink "$dst/$rel")" != "$(git -C "$ios_root" show "$commit:$tree_path/$rel" 2>/dev/null)" ]]; then
        details+=("symlink differs: $dst/$rel")
        bad=1
      fi
    elif [[ -L "$dst/$rel" && ! -e "$dst/$rel" ]]; then
      details+=("broken symlink: $dst/$rel")
      bad=1
    elif [[ ! -e "$dst/$rel" ]]; then
      details+=("missing: $dst/$rel")
      bad=1
    elif ! blob_matches "$commit" "$tree_path/$rel" "$dst/$rel"; then
      details+=("Files differ: $dst/$rel")
      bad=1
    fi
  done

  if (( bad )); then
    for line in ${details[@]+"${details[@]}"}; do
      count=$((count + 1))
      if (( count > 20 )); then
        echo " (more differences)"
        break
      fi
      printf '%s\n' "$line"
    done
    return 1
  fi
  return 0
}

check_file() { # ios_path, local file
  local ios_path=$1 dst=$2
  if [[ "$(blob_mode "$commit" "$ios_path")" == "120000" ]]; then
    if [[ ! -L "$dst" ]]; then
      echo "drift: $dst (expected a symlink)" >&2
      return 1
    fi
    if [[ "$(readlink "$dst")" != "$(git -C "$ios_root" show "$commit:$ios_path" 2>/dev/null)" ]]; then
      echo "drift: $dst (symlink target differs)" >&2
      return 1
    fi
    return 0
  fi
  if [[ -L "$dst" && ! -e "$dst" ]]; then
    echo "drift: $dst (broken symlink)" >&2
    return 1
  fi
  if ! blob_matches "$commit" "$ios_path" "$dst"; then
    echo "drift: $dst" >&2
    # The first lines of a unified diff, still streamed: nothing is written to disk.
    diff -u <(git -C "$ios_root" show "$commit:$ios_path" 2>/dev/null) "$dst" 2>/dev/null | head -20 >&2 || true
    return 1
  fi
  return 0
}

while read -r ios_path android_path; do
  [[ -z "$ios_path" || "$ios_path" == \#* ]] && continue
  dst="$root/$android_path"
  dst=${dst%/}
  if [[ "$ios_path" == */ ]]; then
    tree_path=${ios_path%/}
    if [[ ! -d "$dst" ]]; then
      echo "missing tree: $dst" >&2
      failures=$((failures + 1))
      continue
    fi
    if [[ "$(git -C "$ios_root" cat-file -t "$commit:$tree_path" 2>/dev/null || true)" != tree ]]; then
      echo "missing in $commit: $ios_path" >&2
      failures=$((failures + 1))
      continue
    fi
    if tree_diff=$(check_tree "$tree_path" "$dst"); then
      :
    else
      echo "drift: $dst" >&2
      [[ -n "$tree_diff" ]] && printf '%s\n' "$tree_diff" >&2
      failures=$((failures + 1))
    fi
  else
    if [[ ! -e "$dst" && ! -L "$dst" ]]; then
      echo "missing: $dst" >&2
      failures=$((failures + 1))
      continue
    fi
    if ! git -C "$ios_root" cat-file -e "$commit:$ios_path" 2>/dev/null; then
      echo "missing in $commit: $ios_path" >&2
      failures=$((failures + 1))
      continue
    fi
    if ! check_file "$ios_path" "$dst"; then
      failures=$((failures + 1))
    fi
  fi
done < "$root/scripts/ios-sync-manifest"

if (( failures > 0 )); then
  echo "iOS sync check failed ($failures differences)" >&2
  exit 1
fi
echo "iOS sync check passed against $commit"
