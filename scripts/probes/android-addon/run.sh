#!/usr/bin/env bash
# Runs both external-addon platform probes on one private emulator and leaves the evidence under
# .local/addon-probe/<timestamp>/ (host/, addon/, summary.json), then restores the device state it
# changed.
#
#   source /path/to/songyan-android/.local/env.sh
#   scripts/probes/android-addon/run.sh -s emulator-5558 [--keep-installed]
#
# probe a  BIND_INCLUDE_CAPABILITIES: the probe input method binds to an external APK that tries to
#          record audio. Three cases — flag plus RECORD_AUDIO, no flag plus RECORD_AUDIO, flag plus
#          permission revoked — tell whether the input method's foreground capability (including
#          the microphone) reaches the addon process, and whether the permission itself is still
#          required.
# probe b  SurfaceControlViewHost: the addon draws a panel from the host's SurfaceView token; the
#          script taps the panel, measures size and insets, kills the addon process and then taps
#          the probe keyboard's own key to prove the keyboard still commits text afterwards.
#
# Emulator only: a physical device is refused. The script installs the two probe APKs, switches the
# default input method, wakes and unlocks the disposable emulator if needed, and force-stops only
# the two probe packages. On exit it restores the previous input method and removes the packages it
# installed (unless --keep-installed). It refuses to overwrite a probe installation that is already
# present, so a failed earlier run must be cleaned up first.
set -uo pipefail

DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$DIR/../../.." && pwd)

HOST_PKG=app.songyan.addonprobe.host
ADDON_PKG=app.songyan.addonprobe.addon
HOST_IME=$HOST_PKG/.ProbeIme
HOST_ACTIVITY=$HOST_PKG/.ProbeActivity
APK_DIR=${ADDON_PROBE_OUT:-$ROOT/.local/addon-probe/apks}
REPORTS_ROOT=${ADDON_PROBE_REPORTS:-$ROOT/.local/addon-probe}
HOST_FILES=/sdcard/Android/data/$HOST_PKG/files
ADDON_FILES=/sdcard/Android/data/$ADDON_PKG/files

SERIAL=${ANDROID_SERIAL:-}
KEEP_INSTALLED=0
ORIG_IME=""
INSTALLED_BY_RUN=0
FAILURES=0

usage() {
  printf 'usage: %s -s SERIAL [--keep-installed]\n' "$0" >&2
  exit 2
}

while [ $# -gt 0 ]; do
  case $1 in
    -s|--serial) SERIAL=$2; shift 2 ;;
    --keep-installed) KEEP_INSTALLED=1; shift ;;
    -h|--help) usage ;;
    *) echo "unknown argument: $1" >&2; usage ;;
  esac
done

[ -n "${ANDROID_HOME:-}" ] || { echo "ANDROID_HOME is unset; source the main checkout's .local/env.sh" >&2; exit 1; }
[ -n "${JAVA_HOME:-}" ] || { echo "JAVA_HOME is unset; source the main checkout's .local/env.sh" >&2; exit 1; }
command -v adb >/dev/null || { echo "adb is not on PATH" >&2; exit 1; }
[ -n "$SERIAL" ] || { echo "pass -s SERIAL (or set ANDROID_SERIAL); the probe never guesses a device" >&2; exit 1; }

say() { echo "[addon-probe] $*" >&2; }
dsh() { adb -s "$SERIAL" "$@"; }
shell() { dsh shell "$@" 2>/dev/null | tr -d '\r'; }
fail() { FAILURES=$((FAILURES + 1)); say "FAIL: $*"; }

SDK_INT=$(shell getprop ro.build.version.sdk)
[ -n "$SDK_INT" ] || { echo "$SERIAL is not reachable" >&2; exit 1; }
QEMU=$(shell getprop ro.kernel.qemu)
if [ "$QEMU" != 1 ]; then
  echo "refusing: $SERIAL is a physical device; this probe drives the input method and audio" >&2
  echo "state and only runs on a disposable private emulator." >&2
  exit 1
fi
[ "$SDK_INT" -ge 30 ] || { echo "probe needs API 30+, device is API $SDK_INT" >&2; exit 1; }
say "device $SERIAL api $SDK_INT model $(shell getprop ro.product.model)"

if shell pm list packages | grep -q "package:$HOST_PKG\$" || shell pm list packages | grep -q "package:$ADDON_PKG\$"; then
  echo "refusing: a probe package is already installed; remove it first:" >&2
  echo "  adb -s $SERIAL uninstall $HOST_PKG; adb -s $SERIAL uninstall $ADDON_PKG" >&2
  exit 1
fi

ORIG_IME=$(shell settings get secure default_input_method)
[ -n "$ORIG_IME" ] || ORIG_IME=com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME
say "previous input method: $ORIG_IME"

cleanup() {
  if [ -n "${RUN_DIR:-}" ] && [ -d "$RUN_DIR" ]; then
    dsh logcat -d > "$RUN_DIR/device-logcat.txt" 2>/dev/null || true
  fi
  shell ime set "$ORIG_IME" >/dev/null 2>&1 || true
  shell ime disable "$HOST_IME" >/dev/null 2>&1 || true
  shell am force-stop "$HOST_PKG" >/dev/null 2>&1 || true
  shell am force-stop "$ADDON_PKG" >/dev/null 2>&1 || true
  if [ "$KEEP_INSTALLED" -eq 0 ] && [ "$INSTALLED_BY_RUN" -eq 1 ]; then
    dsh uninstall "$HOST_PKG" >/dev/null 2>&1 || true
    dsh uninstall "$ADDON_PKG" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

if [ "$(shell dumpsys power | sed -n 's/.*mWakefulness=\([A-Za-z]*\).*/\1/p' | head -1)" != Awake ]; then
  say "waking the disposable emulator"
  shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
fi
if shell dumpsys window | grep -q 'isKeyguardShowing=true'; then
  say "dismissing the keyguard of the disposable emulator"
  shell wm dismiss-keyguard >/dev/null 2>&1 || true
fi
sleep 1

say "building the two probe APKs"
"$DIR/build.sh" >/dev/null || { echo "probe build failed" >&2; exit 1; }
HOST_APK=$APK_DIR/app.songyan.addonprobe.host.apk
ADDON_APK=$APK_DIR/app.songyan.addonprobe.addon.apk
for apk in "$HOST_APK" "$ADDON_APK"; do
  [ -f "$apk" ] || { echo "missing $apk" >&2; exit 1; }
done

INSTALLED_BY_RUN=1
dsh install "$HOST_APK" >/dev/null || { echo "cannot install $HOST_APK" >&2; exit 1; }
dsh install "$ADDON_APK" >/dev/null || { echo "cannot install $ADDON_APK" >&2; exit 1; }

RUN_DIR=$REPORTS_ROOT/$(date +%Y%m%d-%H%M%S)
mkdir -p "$RUN_DIR/host" "$RUN_DIR/addon"
say "reports go to $RUN_DIR"

shell pm grant "$ADDON_PKG" android.permission.RECORD_AUDIO >/dev/null 2>&1 || say "cannot grant RECORD_AUDIO"

# The current-input-method selection must be re-applied after force-stopping the IME package:
# stopping it makes the system fall back to the previous keyboard.
ensure_probe_ime() {
  shell ime enable "$HOST_IME" >/dev/null 2>&1 || true
  shell ime set "$HOST_IME" >/dev/null 2>&1 || true
  local attempt
  for attempt in $(seq 1 8); do
    if [ "$(shell settings get secure default_input_method)" = "$HOST_IME" ] &&
      shell dumpsys input_method | grep -q "mCurId=$HOST_IME"; then
      return 0
    fi
    sleep 0.5
  done
  return 1
}
if ! ensure_probe_ime; then
  say "WARNING: the probe input method is not reported as current yet; the case reports will tell"
fi

wait_for_key() { # remote key timeoutSeconds
  local remote=$1 key=$2 timeout=$3 start=$SECONDS content
  while :; do
    content=$(shell cat "$remote")
    if printf '%s' "$content" | grep -Fq -- "$key"; then
      return 0
    fi
    if [ $((SECONDS - start)) -gt "$timeout" ]; then
      return 1
    fi
    sleep 0.3
  done
}

pull_host() { dsh pull "$HOST_FILES/report-host-$1.json" "$RUN_DIR/host/" >/dev/null 2>&1 || true; }
pull_addon() { dsh pull "$ADDON_FILES/report-addon-$1.json" "$RUN_DIR/addon/" >/dev/null 2>&1 || true; }
pull_editor() { dsh pull "$HOST_FILES/editor.json" "$RUN_DIR/host/" >/dev/null 2>&1 || true; }
screenshot() { dsh exec-out screencap -p > "$RUN_DIR/host/$1.png" 2>/dev/null || true; }

reset_device_files() {
  local case_name=$1
  shell am force-stop "$HOST_PKG" >/dev/null 2>&1 || true
  shell am force-stop "$ADDON_PKG" >/dev/null 2>&1 || true
  shell rm -f "$HOST_FILES/report-host-$case_name.json" "$HOST_FILES/editor.json" \
    "$ADDON_FILES/report-addon-$case_name.json" >/dev/null 2>&1 || true
  sleep 0.4
}

run_audio_case() {
  local case_name=$1
  say "case $case_name"
  reset_device_files "$case_name"
  ensure_probe_ime || say "WARNING: case $case_name: the probe input method is not reported as current"
  if [ "$case_name" = a3 ]; then
    shell pm revoke "$ADDON_PKG" android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
  else
    shell pm grant "$ADDON_PKG" android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
  fi
  shell am force-stop "$ADDON_PKG" >/dev/null 2>&1 || true
  shell am start -W -n "$HOST_ACTIVITY" --es case "$case_name" >/dev/null 2>&1 || true
  ensure_probe_ime || say "WARNING: case $case_name: the probe input method is not reported as current"
  if wait_for_key "$HOST_FILES/report-host-$case_name.json" '"record.' 40; then
    sleep 0.4
    pull_host "$case_name"
    pull_addon "$case_name"
    say "case $case_name: $(shell cat "$HOST_FILES/report-host-$case_name.json" | head -c 200)"
  else
    pull_host "$case_name"
    pull_addon "$case_name"
    fail "case $case_name: no record result within 30 s"
  fi
}

run_panel_case() {
  say "case b"
  reset_device_files b
  ensure_probe_ime || say "WARNING: case b: the probe input method is not reported as current"
  shell pm grant "$ADDON_PKG" android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
  shell am start -W -n "$HOST_ACTIVITY" --es case b >/dev/null 2>&1 || true
  ensure_probe_ime || say "WARNING: case b: the probe input method is not reported as current"
  if ! wait_for_key "$HOST_FILES/report-host-b.json" '"panelReady":true' 40; then
    pull_host b
    pull_addon b
    fail "case b: the panel never became ready within 30 s"
    return
  fi
  sleep 0.6
  pull_host b
  local panel_x panel_y commit_x commit_y
  read -r panel_x panel_y commit_x commit_y <<< "$(python3 - "$RUN_DIR/host/report-host-b.json" <<'PY'
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
left, top = d["panelSurfaceLeft"], d["panelSurfaceTop"]
w, h = d["panelSurfaceWidthPx"], d["panelSurfaceHeightPx"]
print(left + w // 2, top + h // 2,
      d["commitLeft"] + d["commitWidthPx"] // 2,
      d["commitTop"] + d["commitHeightPx"] // 2)
PY
)"
  if [ -z "${panel_x:-}" ]; then
    fail "case b: cannot compute the tap coordinates"
    return
  fi
  say "tapping the addon panel at $panel_x,$panel_y"
  shell input tap "$panel_x" "$panel_y" >/dev/null 2>&1 || true
  sleep 0.4
  shell input tap "$((panel_x + 40))" "$((panel_y + 60))" >/dev/null 2>&1 || true
  sleep 0.5
  pull_addon b
  if grep -Fq '"touchCount":0' "$RUN_DIR/addon/report-addon-b.json" 2>/dev/null; then
    fail "case b: the addon panel received no touches"
  fi
  screenshot panel-before-kill

  say "killing the addon process"
  local kill_start kill_end
  kill_start=$(python3 -c 'import time; print(time.time())')
  shell am force-stop "$ADDON_PKG" >/dev/null 2>&1 || true
  if wait_for_key "$HOST_FILES/report-host-b.json" '"addonDeathObserved":true' 10; then
    kill_end=$(python3 -c 'import time; print(time.time())')
    python3 -c "import sys; print('addon death observed after %.0f ms' % (($kill_end - $kill_start) * 1000))" >&2
  else
    fail "case b: the host never observed the addon death"
  fi
  pull_host b
  screenshot panel-after-kill

  say "tapping the probe keyboard key after the death"
  shell input tap "$commit_x" "$commit_y" >/dev/null 2>&1 || true
  sleep 1
  pull_editor
  if grep -Fq '"text":"K' "$RUN_DIR/host/editor.json" 2>/dev/null; then
    say "the keyboard still commits text after the addon died"
  else
    fail "case b: the keyboard did not commit text after the addon died"
  fi
  pull_host b
  screenshot panel-after-commit
}

run_audio_case a1
run_audio_case a2
run_audio_case a3
run_panel_case

shell pm grant "$ADDON_PKG" android.permission.RECORD_AUDIO >/dev/null 2>&1 || true

say "summarizing $RUN_DIR"
if python3 "$DIR/summarize.py" "$RUN_DIR"; then
  say "probe run finished with no validation problems"
else
  fail "summarize.py reported problems"
fi

if [ "$FAILURES" -gt 0 ]; then
  say "$FAILURES probe case(s) failed; see $RUN_DIR"
  exit 1
fi
say "all probe cases produced valid data"
