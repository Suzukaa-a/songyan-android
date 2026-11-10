#!/usr/bin/env bash
# Builds a throwaway APK with two resource layouts and drives a real Activity on one
# emulator to record which resource directory each app-locale tag resolves to.
#
#   DEVICE=emulator-5560 <main-repo>/.scratch/ondevice.sh \
#     scripts/probe-resource-fallback.sh [red|a|b|all]
#
# red = layout A without values-b+zh+Hant. The four Hant tags are expected to miss
#       their expected mapping, which is what proves the assertions can fail; the
#       actual markers are reported exactly as the platform resolved them.
# a   = layout A (values, values-ja, values-zh, values-b+zh+Hant).
# b   = layout B (values, values-ja, values-b+zh+Hans, values-b+zh+Hant).
# all = red, then a, then b (default).
#
# Requires the environment exported by the repository's .local/env.sh.
set -u
set -o pipefail

MODE=${1:-all}
case $MODE in red|a|b|all) ;; *) echo "usage: $0 [red|a|b|all]" >&2; exit 2 ;; esac

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
: "${ANDROID_HOME:?ANDROID_HOME is unset; source .local/env.sh first}"
: "${JAVA_HOME:?JAVA_HOME is unset; source .local/env.sh first}"
: "${ANDROID_USER_HOME:?ANDROID_USER_HOME is unset; source .local/env.sh first}"
: "${ANDROID_SERIAL:?ANDROID_SERIAL is unset; run under ondevice.sh DEVICE=...}"

BT=$(printf '%s\n' "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)
BT=${BT%/}
AJAR=$ANDROID_HOME/platforms/android-37.0/android.jar
KS=$ANDROID_USER_HOME/debug.keystore
ADB=(adb -s "$ANDROID_SERIAL")
PKG=app.songyan.resourcesprobe
OUT=$ROOT/.local/resource-fallback
HANT_TAGS=" zh-TW zh-HK zh-MO zh-Hant "

for need in "$BT/aapt2" "$BT/d8" "$BT/apksigner" "$AJAR" "$KS"; do
  [ -e "$need" ] || { echo "missing: $need" >&2; exit 1; }
done
command -v adb >/dev/null || { echo "missing: adb on PATH" >&2; exit 1; }

TAGS=(zh-CN zh-SG zh-TW zh-HK zh-MO zh-Hant ja-JP en-US fr-FR zh)
expect() {
  case $1 in
    zh-CN|zh-SG|zh) echo HANS ;;
    zh-TW|zh-HK|zh-MO|zh-Hant) echo HANT ;;
    ja-JP) echo JA ;;
    en-US|fr-FR) echo EN ;;
    *) echo UNKNOWN ;;
  esac
}

mkdir -p "$OUT" "$ROOT/.scratch" || exit 1
WK=$(mktemp -d "$ROOT/.scratch/resource-fallback.XXXXXX") || exit 1
INSTALLED=0
# shellcheck disable=SC2329 # invoked by the EXIT trap below
cleanup() {
  local rc=$?
  if [ "$INSTALLED" = 1 ]; then
    "${ADB[@]}" shell am force-stop "$PKG" >/dev/null 2>&1 || true
    "${ADB[@]}" uninstall "$PKG" >/dev/null 2>&1 || echo "cleanup: uninstall $PKG failed" >&2
  fi
  rm -rf "$WK"
  exit "$rc"
}
trap cleanup EXIT

build_apk() { # build_apk <name> <resdir:marker ...>
  local name d spec dir marker
  name=$1
  shift
  d=$WK/$name
  mkdir -p "$d/res" "$d/gen" "$d/classes" "$d/dex" "$d/src/app/songyan/resourcesprobe"
  for spec in "$@"; do
    dir=${spec%%:*} marker=${spec##*:}
    mkdir -p "$d/res/$dir"
    printf '<?xml version="1.0" encoding="utf-8"?>\n<resources><string name="marker">%s</string></resources>\n' \
      "$marker" > "$d/res/$dir/strings.xml"
  done
  cat > "$d/AndroidManifest.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="app.songyan.resourcesprobe">
  <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="37" />
  <application android:label="ResourceFallbackProbe">
    <activity android:name=".ProbeActivity" android:exported="true" />
  </application>
</manifest>
XML
  cat > "$d/src/app/songyan/resourcesprobe/ProbeActivity.java" <<'JAVA'
package app.songyan.resourcesprobe;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.util.Log;

public final class ProbeActivity extends Activity {
    private static final String TAG = "ResourceFallbackProbe";
    private static final String[] TAGS = {
        "zh-CN", "zh-SG", "zh-TW", "zh-HK", "zh-MO", "zh-Hant",
        "ja-JP", "en-US", "fr-FR", "zh",
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String run = getIntent().getStringExtra("run");
        Log.i(TAG, "BEGIN run=" + run + " sdk=" + Build.VERSION.SDK_INT
                + " release=" + Build.VERSION.RELEASE);
        for (String tag : TAGS) {
            Configuration config = new Configuration(getResources().getConfiguration());
            config.setLocales(LocaleList.forLanguageTags(tag));
            Context localized = createConfigurationContext(config);
            String marker = localized.getString(R.string.marker);
            Configuration applied = localized.getResources().getConfiguration();
            Log.i(TAG, "RESULT run=" + run + " tag=" + tag + " marker=" + marker
                    + " locales=" + applied.getLocales().toLanguageTags());
        }
        Log.i(TAG, "END run=" + run);
        finish();
    }
}
JAVA
  "$BT/aapt2" compile --dir "$d/res" -o "$d/res.zip" || return 1
  "$BT/aapt2" link -o "$d/unsigned.apk" -I "$AJAR" --manifest "$d/AndroidManifest.xml" \
    --java "$d/gen" --min-sdk-version 26 --target-sdk-version 37 "$d/res.zip" || return 1
  "$JAVA_HOME/bin/javac" -source 8 -target 8 -nowarn -bootclasspath "$AJAR" -classpath "$AJAR" \
    -d "$d/classes" "$d/gen/app/songyan/resourcesprobe/R.java" \
    "$d/src/app/songyan/resourcesprobe/ProbeActivity.java" || return 1
  local class_files=("$d"/classes/app/songyan/resourcesprobe/*.class)
  [ -e "${class_files[0]}" ] || { echo "no compiled classes for $name" >&2; return 1; }
  "$BT/d8" --release --min-api 26 --lib "$AJAR" --output "$d/dex" "${class_files[@]}" || return 1
  cp "$d/unsigned.apk" "$d/base.apk"
  ( cd "$d/dex" && zip -q "$d/base.apk" classes.dex ) || return 1
  "$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias androiddebugkey --min-sdk-version 26 "$d/base.apk" || return 1
}

run_phase() { # run_phase <variant> <phase>; fills FAILS/TOTAL and verdict file
  local variant phase apk run tag actual want ok finished logf count fails total
  variant=$1
  phase=$2
  apk=$WK/$variant/base.apk
  run="rf-$phase-$$"
  logf=$OUT/$phase.logcat.txt
  fails=0
  total=0
  "${ADB[@]}" shell am force-stop "$PKG" >/dev/null 2>&1 || true
  "${ADB[@]}" install -r -t "$apk" >/dev/null || { echo "phase $phase: install failed" >&2; return 1; }
  INSTALLED=1
  "${ADB[@]}" shell am force-stop "$PKG" >/dev/null 2>&1 || true
  "${ADB[@]}" logcat -c 2>/dev/null || true
  "${ADB[@]}" shell am start -W -n "$PKG/.ProbeActivity" --es run "$run" >/dev/null || return 1
  finished=0
  for _ in $(seq 1 40); do
    sleep 0.5
    "${ADB[@]}" logcat -d -v brief > "$logf" 2>/dev/null || true
    if grep -q "END run=$run" "$logf" 2>/dev/null; then finished=1; break; fi
  done
  if [ "$finished" != 1 ]; then
    echo "phase $phase: no END marker for run=$run within 20s" >&2
    return 1
  fi
  grep "RESULT run=$run" "$logf" \
    | sed -E 's/.*tag=([^ ]+) marker=([A-Z]+).*/\1 \2/' > "$OUT/$phase.results.txt"
  count=$(wc -l < "$OUT/$phase.results.txt" | tr -d ' ')
  if [ "$count" != "${#TAGS[@]}" ]; then
    echo "phase $phase: got $count of ${#TAGS[@]} markers" >&2
    return 1
  fi
  : > "$OUT/$phase.verdict.txt"
  for tag in "${TAGS[@]}"; do
    want=$(expect "$tag")
    actual=$(awk -v t="$tag" '$1==t{print $2; exit}' "$OUT/$phase.results.txt")
    total=$((total + 1))
    if [ -z "$actual" ] || [ "$actual" = UNKNOWN ]; then
      echo "phase $phase: no marker for $tag" >&2
      return 1
    fi
    if [ "$actual" = "$want" ]; then ok=pass; else ok=FAIL; fails=$((fails + 1)); fi
    printf '%s %s expected=%s actual=%s\n' "$ok" "$tag" "$want" "$actual"
    printf '%s %s %s\n' "$tag" "$want" "$actual" >> "$OUT/$phase.verdict.txt"
  done
  echo "PHASE $phase matched=$((total - fails))/$total"
  FAILS=$fails
  TOTAL=$total
}

red_as_expected() { # exactly the four Hant tags missed, the other six matched
  local tag want actual hant_miss=0 others_fail=0
  while read -r tag want actual; do
    if [ "$actual" != "$want" ]; then
      case "$HANT_TAGS" in
        *" $tag "*) hant_miss=$((hant_miss + 1)) ;;
        *) others_fail=$((others_fail + 1)) ;;
      esac
    fi
  done < "$OUT/red.verdict.txt"
  [ "$hant_miss" -eq 4 ] && [ "$others_fail" -eq 0 ]
}

json() {
  local phase tag want actual first sep
  printf '{\n  "probe": "resource-fallback",\n'
  printf '  "package": "%s",\n  "device": "%s",\n' "$PKG" "$ANDROID_SERIAL"
  printf '  "sdk_int": "%s",\n  "sdk_full": "%s",\n  "release": "%s",\n' "$SDK_INT" "$SDK_FULL" "$RELEASE"
  printf '  "expected": {'
  first=1
  for tag in "${TAGS[@]}"; do
    [ $first -eq 1 ] || printf ', '
    printf '"%s": "%s"' "$tag" "$(expect "$tag")"
    first=0
  done
  printf '},\n  "phases": [\n'
  sep=""
  for phase in "${PHASES[@]}"; do
    printf '%s    {"name": "%s", "matched": %s, "total": %s, "results": [' \
      "$sep" "$phase" "$(cat "$OUT/$phase.summary.txt")" "$TOTAL"
    sep=$',
'
    first=1
    while read -r tag want actual; do
      [ $first -eq 1 ] || printf ', '
      printf '{"tag": "%s", "expected": "%s", "actual": "%s"}' "$tag" "$want" "$actual"
      first=0
    done < "$OUT/$phase.verdict.txt"
    printf ']}'
  done
  printf '\n  ]\n}\n'
}

SDK_INT=$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r')
SDK_FULL=$("${ADB[@]}" shell getprop ro.build.version.sdk_full | tr -d '\r')
RELEASE=$("${ADB[@]}" shell getprop ro.build.version.release | tr -d '\r')
echo "device=$ANDROID_SERIAL sdk_int=$SDK_INT sdk_full=$SDK_FULL release=$RELEASE"
if [ "$SDK_INT" != 37 ] || [ "$SDK_FULL" != 37.0 ]; then
  echo "device is not API 37/37.0 (sdk_int=$SDK_INT sdk_full=$SDK_FULL)" >&2
  exit 1
fi

if [ "$MODE" = all ]; then PHASES=(red a b); else PHASES=("$MODE"); fi

for phase in "${PHASES[@]}"; do
  case $phase in
    red) build_apk a-no-hant values:EN values-ja:JA values-zh:HANS || exit 1
         run_phase a-no-hant red || exit 1 ;;
    a)   build_apk a values:EN values-ja:JA values-zh:HANS values-b+zh+Hant:HANT || exit 1
         run_phase a a || exit 1 ;;
    b)   build_apk b values:EN values-ja:JA values-b+zh+Hans:HANS values-b+zh+Hant:HANT || exit 1
         run_phase b b || exit 1 ;;
  esac
  echo "$((TOTAL - FAILS))" > "$OUT/$phase.summary.txt"
  echo "$FAILS" > "$OUT/$phase.fails.txt"
done

json > "$OUT/resource-fallback.json"
echo "json=$OUT/resource-fallback.json raw=$OUT/<phase>.logcat.txt"

rc=0
if [ "$MODE" = all ] || [ "$MODE" = red ]; then
  if red_as_expected; then
    echo "RED as expected: exactly the 4 Hant tags missed"
  else
    echo "red phase: expected exactly the 4 Hant tags to miss and the other 6 to match" >&2
    rc=1
  fi
fi
for phase in a b; do
  if [ "$MODE" = all ] || [ "$MODE" = "$phase" ]; then
    passed=$(cat "$OUT/$phase.summary.txt" 2>/dev/null || echo 0)
    if [ "$passed" = "${#TAGS[@]}" ]; then
      echo "GREEN $phase: ${#TAGS[@]}/${#TAGS[@]}"
    else
      echo "phase $phase: only $passed of ${#TAGS[@]} matched" >&2
      rc=1
    fi
  fi
done
echo "RESULT: $([ $rc = 0 ] && echo OK || echo FAILED)"
exit $rc
