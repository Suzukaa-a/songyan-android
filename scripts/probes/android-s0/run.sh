#!/usr/bin/env bash
# Builds, installs and runs the Android S0 probes automatically on one connected emulator, then
# puts the emulator's input method, screen and keyguard state back. Every probe writes a JSON
# report; the script pulls them under .local/s0-probe/<run>/reports and prints one summary line
# per probe. A probe whose report never appears counts as a failure: missing data is never success.
#
#   # a git worktree has no .local/env.sh of its own; point at the main checkout's:
#   source /path/to/songyan-android/.local/env.sh
#   scripts/probes/android-s0/run.sh [-s SERIAL] [--keep-installed] [probe...]
#
# probes (default: all)
#   isolated  binder round trip to an android:isolatedProcess service: bind to first reply,
#             300 warm calls, then a second cold start after the isolated process is killed
#   context   getTextBeforeCursor after committing a known marker into the probe's own empty
#             EditText and WebView. Measuring another app's editor is read-only: it never writes
#             and never deletes text. A report that is missing or invalid fails the run, and is
#             never counted as a measurement
#   record    microphone in the visible input method; a recording that is still running when the
#             keyboard is hidden 1.2 s in; recordings with the screen off while the activity is
#             backgrounded, with and without a microphone foreground service; and a recording
#             that starts 2.5 s after launch with the screen off, the foreground service having
#             been started while the app was still foreground. Every second records whether the
#             screen was interactive, whether the keyguard was locked and whether the device has a
#             credential, so a lock-screen run can be told from a run that never locked
#
# This script drives the device automatically and therefore runs on an emulator only; a physical
# device is refused and pointed at manual.sh. It requires the emulator to be awake and unlocked
# and refuses to wake or unlock it; on the way out it restores the screen and keyguard state and
# reports a failure when it cannot.
#
# It refuses to install when app.songyan.s0probe is already present: an existing package is never
# overwritten; remove it yourself first. It never removes a package across runs: an interrupted
# run may leave the probe installed, and the next run then refuses and asks you to remove it.
# Within one run it uninstalls only the APK it installed itself, unless --keep-installed.
# POST_NOTIFICATIONS is requested only from API 33, where it exists. It verifies RECORD_AUDIO is
# granted and fails the record probe instead of reporting audio from an unpermissioned app.
#
# A physical device is measured with scripts/probes/android-s0/manual.sh: it never changes the
# screen, keyguard, input method, permissions or packages and measures the input method, the lock
# screen and another app's editor through actions you perform yourself. Reports from either script
# are validated by summarize.py next to this one.
set -eo pipefail

DIR=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$DIR/../../.." && pwd)
PKG=app.songyan.s0probe
IME=$PKG/.ProbeIme
SERIAL=$ANDROID_SERIAL
[ -n "$SERIAL" ] || SERIAL=$DEVICE
PROBES=""
PROBE_COUNT=0
KEEP_INSTALLED=0
FAILURES=0
ORIG_AWAKE=unknown
ORIG_KEYGUARD=unknown
PROBE_IME_ENABLED=0
INSTALLED_BY_RUN=0
MUTATED=0

usage() {
  printf 'usage: %s [-s SERIAL] [--keep-installed] [all|isolated|context|record...]\n' "$0" >&2
  exit 2
}
while [ $# -gt 0 ]; do
  case $1 in
    -s|--serial) SERIAL=$2; shift 2 ;;
    --keep-installed) KEEP_INSTALLED=1; shift ;;
    -h|--help) usage ;;
    all|isolated|context|record) PROBES="$PROBES $1"; PROBE_COUNT=$((PROBE_COUNT + 1)); shift ;;
    *) echo "unknown argument: $1" >&2; usage ;;
  esac
done
[ "$PROBE_COUNT" -gt 0 ] || PROBES="all"

[ -n "$ANDROID_HOME" ] || { echo "ANDROID_HOME is unset; source the main checkout's .local/env.sh" >&2; exit 1; }
[ -n "$JAVA_HOME" ] || { echo "JAVA_HOME is unset; source the main checkout's .local/env.sh" >&2; exit 1; }
command -v adb >/dev/null || { echo "adb is not on PATH" >&2; exit 1; }

if [ -z "$SERIAL" ]; then
  SERIAL=$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')
fi
[ -n "$SERIAL" ] || { echo "no device; pass -s SERIAL" >&2; exit 1; }

say() { echo "[s0] $*" >&2; }
shell() { adb -s "$SERIAL" shell "$@" 2>/dev/null | tr -d '\r'; }
wakefulness() { shell dumpsys power | sed -n 's/.*mWakefulness=\([A-Za-z]*\).*/\1/p' | head -1; }
keyguard_state() { shell dumpsys window | sed -n 's/.*isKeyguardShowing=\([a-z]*\).*/\1/p' | head -1; }

SDK_INT=$(shell getprop ro.build.version.sdk)
[ -n "$SDK_INT" ] || { echo "$SERIAL is not reachable" >&2; exit 1; }
[ "$SDK_INT" -ge 30 ] || { echo "probe needs API 30+, device is API $SDK_INT" >&2; exit 1; }
MODEL=$(shell getprop ro.product.model)
HARDWARE=$(shell getprop ro.hardware)
QEMU=$(shell getprop ro.kernel.qemu)

# This probe turns the screen off, changes the input method and records audio. That is only
# acceptable on a disposable emulator; a physical device is measured through user actions with
# manual.sh, so this script refuses one instead of driving it.
if [ "$QEMU" != 1 ]; then
  echo "refusing: $SERIAL is a physical device, and this script drives the device automatically." >&2
  echo "Measure a physical device with scripts/probes/android-s0/manual.sh: it changes no device" >&2
  echo "state, installs and uninstalls nothing, and asks you to perform each step by hand." >&2
  exit 1
fi

ORIG_AWAKE=$(wakefulness)
ORIG_KEYGUARD=$(keyguard_state)
ORIG_IME=$(shell settings get secure default_input_method)

# Never wake, unlock or lock the device on the user's behalf: the probe needs an awake, unlocked
# test device, and refuses rather than changing that state.
if [ "$ORIG_AWAKE" != Awake ]; then
  echo "refusing: $SERIAL is not awake (mWakefulness=$ORIG_AWAKE); wake and unlock it, then run again." >&2
  exit 1
fi
if [ "$ORIG_KEYGUARD" != false ]; then
  echo "refusing: the keyguard is showing (isKeyguardShowing=$ORIG_KEYGUARD); unlock the device first." >&2
  echo "This probe turns the screen off and on and must not decide whether to unlock your device." >&2
  exit 1
fi
if shell ime list -s | grep -qx "$IME"; then PROBE_IME_ENABLED=1; fi

RUN_ID=$(date +%Y%m%d-%H%M%S)
OUT=$ROOT/.local/s0-probe/$RUN_ID
REPORTS=$OUT/reports
mkdir -p "$REPORTS"

restore() {
  local rc=$? exit_code now_awake now_keyguard
  set +e
  if [ "$MUTATED" = 0 ]; then
    say "nothing to restore: the run did not change device state"
    exit "$rc"
  fi
  say "restoring device state"
  shell am force-stop "$PKG" >/dev/null
  now_awake=$(wakefulness)
  if [ "$ORIG_AWAKE" = Awake ]; then
    if [ "$now_awake" != Awake ]; then
      shell input keyevent 224 >/dev/null
      sleep 1
    fi
  elif [ "$ORIG_AWAKE" = Asleep ]; then
    if [ "$now_awake" = Awake ]; then shell input keyevent 223 >/dev/null; fi
  fi
  now_keyguard=$(keyguard_state)
  if [ "$ORIG_KEYGUARD" = false ] && [ "$now_keyguard" = true ]; then
    shell wm dismiss-keyguard >/dev/null
    sleep 1
    now_keyguard=$(keyguard_state)
    if [ "$now_keyguard" = true ]; then
      say "WARNING: the keyguard was not showing before the run and could not be dismissed; the device is left locked"
      FAILURES=$((FAILURES + 1))
    fi
  elif [ "$ORIG_KEYGUARD" = true ] && [ "$now_keyguard" = false ] && [ "$ORIG_AWAKE" = Awake ]; then
    say "restoring the keyguard: cycling the screen off and on"
    shell input keyevent 223 >/dev/null
    sleep 1
    shell input keyevent 224 >/dev/null
    sleep 2
    now_keyguard=$(keyguard_state)
    if [ "$now_keyguard" != true ]; then
      say "WARNING: the keyguard was showing before the run and could not be restored"
      FAILURES=$((FAILURES + 1))
    fi
  fi
  if [ -n "$ORIG_IME" ] && [ "$ORIG_IME" != "$IME" ]; then
    shell ime set "$ORIG_IME" >/dev/null || { say "WARNING: could not restore input method $ORIG_IME"; FAILURES=$((FAILURES + 1)); }
  fi
  if [ "$PROBE_IME_ENABLED" = 0 ]; then shell ime disable "$IME" >/dev/null; fi
  if [ "$INSTALLED_BY_RUN" = 1 ] && [ "$KEEP_INSTALLED" = 0 ]; then
    shell pm uninstall "$PKG" >/dev/null || { say "WARNING: could not uninstall $PKG"; FAILURES=$((FAILURES + 1)); }
  fi
  say "reports: $REPORTS"
  exit_code=$rc
  [ "$FAILURES" -eq 0 ] || exit_code=1
  exit "$exit_code"
}
trap restore EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

command -v python3 >/dev/null || { echo "python3 is needed for the summary" >&2; exit 1; }

if shell pm list packages "$PKG" | grep -qx "package:$PKG"; then
  echo "refusing: $PKG is already installed on $SERIAL; an existing package is never overwritten or removed." >&2
  echo "Remove it yourself before running again: adb -s $SERIAL shell pm uninstall $PKG" >&2
  echo "This script never removes a package across runs, including one left by an interrupted run." >&2
  exit 1
fi

APK=$("$DIR/build.sh")
say "device=$SERIAL sdk=$SDK_INT model=$MODEL hardware=$HARDWARE"
say "screen=$ORIG_AWAKE keyguard=$ORIG_KEYGUARD ime=$ORIG_IME"
say "apk=$APK out=$OUT"
STARTED=$(date +%s)
if ! adb -s "$SERIAL" install -t "$APK" >/dev/null; then
  echo "install failed; if $PKG now appears on $SERIAL the interrupted install left it there." >&2
  echo "Check the device and remove it yourself; this script never removes it automatically." >&2
  exit 1
fi
INSTALLED_BY_RUN=1
MUTATED=1
# Incremental installs register the package a moment after adb returns.
for i in $(seq 1 30); do
  shell ime list -a -s | grep -qx "$IME" && break
  sleep 1
done
if [ "$SDK_INT" -ge 33 ]; then
  shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null || say "WARNING: POST_NOTIFICATIONS not granted; the probe notification may not show"
fi
RECORD_OK=0
shell pm grant "$PKG" android.permission.RECORD_AUDIO >/dev/null || true
if shell dumpsys package "$PKG" | grep 'android.permission.RECORD_AUDIO' | grep -q 'granted=true'; then
  RECORD_OK=1
else
  say "WARNING: RECORD_AUDIO could not be granted; the record probe will fail instead of reporting unpermissioned audio"
fi
shell ime enable "$IME" >/dev/null || { echo "cannot enable $IME" >&2; exit 1; }
shell ime set "$IME" >/dev/null || { echo "cannot select $IME" >&2; exit 1; }

start_probe() {
  local component=$1
  shift
  shell am start -n "$component" "$@" >/dev/null
}
home() { shell input keyevent 3 >/dev/null; sleep 1; }
screen_off() { shell input keyevent 223 >/dev/null; }
screen_on() { shell input keyevent 224 >/dev/null; sleep 1; shell wm dismiss-keyguard >/dev/null; }

wait_report() {
  local name=$1
  local tries=30
  [ $# -ge 2 ] && tries=$2
  local i out found=0
  for i in $(seq 1 "$tries"); do
    sleep 2
    out=$(shell run-as "$PKG" cat "files/reports/$name.json" || true)
    case "$out" in
      \{*) printf '%s' "$out" > "$REPORTS/$name.json"; found=1; break ;;
    esac
  done
  if [ "$found" = 1 ]; then
    say "report $name"
  else
    say "MISSING report $name"
    FAILURES=$((FAILURES + 1))
  fi
}

apply_control() {
  start_probe "$PKG/.ControlActivity" --es run "$1" --ei marker "$2" --ez measure "$3" \
    --ez record "$4" --es editor "$PKG"
  sleep 1
}

run_isolated() {
  say "isolated: binder round trip"
  start_probe "$PKG/.ProbeActivity" --es mode isolated --es run iso
  wait_report isolated-iso
}

run_context() {
  say "context: probe EditText"
  home
  start_probe "$PKG/.HostActivity" --es editor edittext --es run ctx-edittext --ei marker 16000 --ez measure true --ez record false
  wait_report context-ctx-edittext
  say "context: platform WebView"
  home
  start_probe "$PKG/.HostActivity" --es editor webview --es run ctx-webview --ei marker 16000 --ez measure true --ez record false
  wait_report context-ctx-webview
}

run_record() {
  if [ "$RECORD_OK" != 1 ]; then
    say "record: RECORD_AUDIO is not granted; skipping instead of reporting unpermissioned audio"
    FAILURES=$((FAILURES + 1))
    return
  fi
  say "record: inside the visible input method"
  home
  apply_control rec-ime 4000 false true
  start_probe "$PKG/.HostActivity" --es editor edittext --es run rec-ime --ez measure false --ez record true
  wait_report record-ime-rec-ime

  say "record: recording continues after the keyboard is hidden at 1.2 s"
  home
  start_probe "$PKG/.HostActivity" --es editor edittext --es run rec-hide --ez measure false --ez record true --el hideAfterMs 1200
  wait_report record-ime-rec-hide

  say "record: screen off while the activity is backgrounded, no foreground service"
  home
  start_probe "$PKG/.ProbeActivity" --es mode record --es plan screenoff --es run rec-off --ei seconds 6
  sleep 1.5; screen_off; sleep 2.5; screen_on
  wait_report record-activity-rec-off

  say "record: screen off while the activity is backgrounded, microphone foreground service"
  home
  start_probe "$PKG/.ProbeActivity" --es mode record --es plan screenoff-fgs --es run rec-off-fgs --ei seconds 6 --ez fgs true
  sleep 1.5; screen_off; sleep 2.5; screen_on
  wait_report record-fgs-rec-off-fgs

  say "record: recording starts 2.5 s after launch with the screen off, no foreground service"
  home
  start_probe "$PKG/.ProbeActivity" --es mode record --es plan background --es run rec-bg --ei seconds 4 --ei delayMs 2500
  sleep 0.5; screen_off; sleep 4; screen_on
  wait_report record-activity-rec-bg

  say "record: foreground service started while foreground, recording starts 2.5 s later with the screen off"
  home
  start_probe "$PKG/.ProbeActivity" --es mode record --es plan background-fgs --es run rec-bg-fgs --ei seconds 4 --ei delayMs 2500 --ez fgs true
  sleep 0.5; screen_off; sleep 4; screen_on
  wait_report record-fgs-rec-bg-fgs
}

for probe in $PROBES; do
  case $probe in
    all) run_isolated; run_context; run_record ;;
    isolated) run_isolated ;;
    context) run_context ;;
    record) run_record ;;
  esac
done

# Validates every report before it may be counted: a missing required field, an "error" field or
# a value that contradicts the probe's contract (for example an isolated process that can read
# the host's private file, a write-mode measurement that did not clean up, or a recording that
# stopped before its last second) exits non-zero.
SUMMARY_RC=0
python3 "$DIR/summarize.py" "$REPORTS" > "$OUT/summary.txt" || SUMMARY_RC=$?
cat "$OUT/summary.txt"
if [ "$SUMMARY_RC" -ne 0 ]; then
  say "report validation failed: a report is missing required data or carries an error"
  FAILURES=$((FAILURES + 1))
fi

ENDED=$(date +%s)
say "done in $((ENDED - STARTED))s failures=$FAILURES"
if [ "$FAILURES" -gt 0 ]; then exit 1; fi