#!/usr/bin/env bash
# Prints `key=<prefix>-<digest>` for GitHub Actions. Entries in .local/native-cache that this
# build did not use (their .songyan-used marker, touched by native/android/CMakeLists.txt, is not
# newer than .local/native-build-start) are moved to .local/native-cache-stale, outside the cached
# paths, so the saved cache holds only current engines. The digest covers the remaining entry names,
# which already encode every engine key input, so an unchanged set maps to the restored key.
#
# Usage (CI only): scripts/ci-native-cache-key.sh <key-prefix> >>"$GITHUB_OUTPUT"
set -euo pipefail
[[ -n "${CI:-}" ]] || { echo "ci-native-cache-key.sh runs on CI only" >&2; exit 64; }
prefix="${1:?usage: ci-native-cache-key.sh <key-prefix>}"
cache=".local/native-cache"
start=".local/native-build-start"
stale=".local/native-cache-stale"
[[ -f "${start}" ]] || { echo "missing ${start}" >&2; exit 1; }
names=()
if [[ -d "${cache}" ]]; then
  for entry in "${cache}"/engine-*; do
    [[ -d "${entry}" ]] || continue
    name="${entry##*/}"
    if [[ "${name}" == *.partial || "${name}" == *.build || ! "${entry}/.songyan-used" -nt "${start}" ]]; then
      mkdir -p "${stale}"
      mv -- "${entry}" "${stale}/"
    else
      names+=("${name}")
    fi
  done
fi
digest="$(printf '%s\n' "${names[@]:-}" | sort | sha256sum | cut -c1-16)"
echo "key=${prefix}-${digest}"
