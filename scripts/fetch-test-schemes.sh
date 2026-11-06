#!/usr/bin/env bash
# Downloads the pinned upstream archives of the recommended traditional input schemes (bopomofo,
# cangjie, quick, cantonese) into the main checkout's .local/test-schemes/ so the device test
# TraditionalCatalogInstallDeviceTest can install them without any network, and optionally pushes
# them to a device. The links, digests and sizes are read from the catalogue in settings/, so this
# script cannot drift from what the app itself downloads; every file is checked against its SHA-256.
# Usage: scripts/fetch-test-schemes.sh [--check] [--push [serial]]
#   no argument   download every archive that is missing or different, verify every digest
#   --check       verify the archives already there; downloads nothing
#   --push [serial]
#                 copy the archives into the installed debug app's fixture cache
#                 (serial defaults to ANDROID_SERIAL, then adb's only device)
# Environment: SONGYAN_TEST_SCHEMES_DIR overrides the download directory.
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
catalogue="$root/settings/src/main/kotlin/app/songyan/ime/settings/state/RecommendedSchemes.kt"

mode=download
serial=
case "${1:-}" in
  "")
    ;;
  --check)
    mode=check
    ;;
  --push)
    mode=push
    serial="${2:-}"
    ;;
  -h | --help)
    sed -n '2,/^set /p' "${BASH_SOURCE[0]}" | sed '/^#/!d; s/^# \{0,1\}//'
    exit 0
    ;;
  *)
    echo "unknown argument: $1 (usage: scripts/fetch-test-schemes.sh [--check] [--push [serial]])" >&2
    exit 2
    ;;
esac

if [[ ! -f "$catalogue" ]]; then
  echo "the recommended catalogue is missing: $catalogue" >&2
  exit 1
fi

# The main checkout, where .local/ lives; this worktree keeps its own .local/.local-test files.
main=$(git -C "$root" worktree list --porcelain | awk 'NR == 1 { print $2 }')
out="${SONGYAN_TEST_SCHEMES_DIR:-$main/.local/test-schemes}"

if [[ "$mode" == push ]]; then
  if [[ ! -d "$out" ]]; then
    echo "nothing to push: $out does not exist" >&2
    exit 1
  fi
  "$0" --check
  python3 - "$out" "$serial" <<'PY'
from pathlib import Path
import shlex
import subprocess
import sys

source = Path(sys.argv[1])
adb = ["adb"] + (["-s", sys.argv[2]] if sys.argv[2] else [])
package = "app.songyan.ime.debug"
directory = "cache/songyan-test-schemes"
subprocess.run(adb + ["shell", "run-as", package, "mkdir", "-p", directory], check=True)
for archive in sorted(source.glob("*.zip")):
    # shell_data_file is unreadable to the app; run-as creates the copy with its own UID and label.
    command = "cat > " + shlex.quote(directory + "/" + archive.name)
    remote = "run-as " + package + " sh -c " + shlex.quote(command)
    with archive.open("rb") as stream:
        subprocess.run(adb + ["shell", "-T", remote], stdin=stream, check=True)
print("copied pinned archives into the debug app fixture cache")
PY
  echo "run TraditionalCatalogInstallDeviceTest; its default fixture directory is the app cache"
  exit 0
fi

# URL, SHA-256 and size of every archive the catalogue pins, one archive per line.
archives=$(
  python3 - "$catalogue" <<'PY'
import re
import sys

text = open(sys.argv[1], encoding="utf-8").read()
archive = re.compile(
    r'RecommendedArchive\(\s*url = "([^"]+)",\s*sha256 = "([0-9a-f]{64})",\s*bytes = ([0-9_]+),'
)
seen = set()
for match in archive.finditer(text):
    url, digest, size = match.groups()
    if url in seen:
        continue
    seen.add(url)
    print(url, digest, size.replace("_", ""))
PY
)

if [[ -z "$archives" ]]; then
  echo "cannot read the pinned archives out of $catalogue" >&2
  exit 1
fi

mkdir -p "$out"

# The name of a file in the download directory whose bytes are [digest], if any: a release asset
# may have arrived under the file name its download page uses.
file_with_digest() {
  local wanted=$1 candidate
  for candidate in "$out"/*.zip; do
    [[ -f "$candidate" ]] || continue
    if [[ "$(shasum -a 256 "$candidate" | awk '{ print $1 }')" == "$wanted" ]]; then
      basename "$candidate"
      return
    fi
  done
}

failed=0
downloaded=0
while read -r url digest bytes; do
  name=${url%/}
  name=${name##*/}
  if [[ "$name" != *.zip ]]; then
    rest=${url%/}
    repository=${rest%/*/*}
    repository=${repository##*/}
    name="$repository-$name.zip"
  fi
  file="$out/$name"
  if [[ -f "$file" ]]; then
    actual=$(shasum -a 256 "$file" | awk '{ print $1 }')
    if [[ "$actual" == "$digest" ]]; then
      echo "ok       $name ($bytes bytes)"
      continue
    fi
    if [[ "$mode" == check ]]; then
      echo "mismatch $name: expected $digest, found $actual" >&2
      failed=1
      continue
    fi
    echo "replace  $name: digest differs"
  else
    # A release asset may already be here under the name the download page gives it.
    present=$(file_with_digest "$digest")
    if [[ -n "$present" ]]; then
      echo "ok       $name (already here as $present)"
      continue
    fi
    if [[ "$mode" == check ]]; then
      echo "missing  $name" >&2
      failed=1
      continue
    fi
  fi
  temporary="$out/.$name.part"
  if ! curl -fsSL --retry 3 --proto '=https' -o "$temporary" "$url"; then
    rm -f "$temporary"
    echo "download failed: $url" >&2
    failed=1
    continue
  fi
  actual=$(shasum -a 256 "$temporary" | awk '{ print $1 }')
  if [[ "$actual" != "$digest" ]]; then
    rm -f "$temporary"
    echo "download does not match its pin: $name (expected $digest, found $actual)" >&2
    failed=1
    continue
  fi
  mv "$temporary" "$file"
  downloaded=$((downloaded + 1))
  echo "fetched  $name ($bytes bytes)"
done <<<"$archives"

if ((failed)); then
  echo "some archives are missing or wrong; nothing was installed" >&2
  exit 1
fi
if [[ "$mode" == check ]]; then
  echo "check passed: every pinned archive is present in $out"
else
  echo "fetched $downloaded archive(s) into $out"
fi
