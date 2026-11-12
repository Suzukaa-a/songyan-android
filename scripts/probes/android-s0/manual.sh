#!/usr/bin/env bash
# Measures the Android S0 items on a physical device through actions you perform yourself: the
# input method recording ("record"), the same recording with the device locked by hand ("lock"),
# and how much text a real app's editor hands the input method ("context").
#
#   # a git worktree has no .local/env.sh of its own; point at the main checkout's:
#   source /path/to/songyan-android/.local/env.sh
#   scripts/probes/android-s0/manual.sh prepare            # build the probe APK, print what to run
#   scripts/probes/android-s0/manual.sh facts              # device facts, nothing changed
#   scripts/probes/android-s0/manual.sh package            # package of the app you have open
#   scripts/probes/android-s0/manual.sh record  --package P [--seconds N]
#   scripts/probes/android-s0/manual.sh lock    --package P [--seconds N]
#   scripts/probes/android-s0/manual.sh context --package P [--seconds N]
#   scripts/probes/android-s0/manual.sh pull                # pull and validate every report
#   scripts/probes/android-s0/manual.sh clear               # switch the probe's scenario flags off
#
# This script never installs, uninstalls, overwrites or grants anything, never changes the screen,
# keyguard, input method or system settings, never stops or launches another app, and never wakes,
# locks or unlocks the device. It only reads device state, starts its own probe app's control
# activity to hand the probe a scenario, and pulls the probe's own JSON reports with run-as. Each
# measurement step explains what to do on the device and waits for the report you produce.
#
# Text: the context step records character counts only, never characters, and never writes or
# deletes in another app's editor. Only the automated emulator flow writes, and only into the
# probe's own empty field.
#
# Every measurement step names the app it is for and the probe measures only that package, one
# editor per command: the scenario is claimed by the first matching field and cleared at once, so a
# later field, another app or a restarted process collects nothing. Without --package nothing is
# collected; use "manual.sh package" to read the package name of the app you have open.
#
# It changes none of the device's screen, keyguard, input method, permissions or packages; the only
# thing it starts is its own probe app's control entry, so it also runs on an emulator if you want
# to see the flow. Physical devices use it because the automated run.sh refuses to drive them.
#
# Reports land under .local/s0-probe/manual-<run>/reports. A run interrupted by the screen turning
# off leaves a ".partial" report; the lock step reports that as an incomplete measurement (never
# as a completed recording), and the summary says whether the screen was off and whether the
# device was actually locked.
set -eo pipefail

DIR=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$DIR/../../.." && pwd)
PKG=app.songyan.s0probe
IME=$PKG/.ProbeIme
SERIAL=$ANDROID_SERIAL
[ -n "$SERIAL" ] || SERIAL=$DEVICE
CMD=""
SECONDS_ARG=10
PACKAGE=""
OUT=""
REPORTS=""
PARTIAL_DIR=""

usage() {
  awk 'NR > 1 && /^set -eo pipefail/ {exit} NR > 1 {sub(/^# ?/, ""); print}' "$0"
  exit "${1:-0}"
}

while [ $# -gt 0 ]; do
  case $1 in
    -s|--serial) SERIAL=$2; shift 2 ;;
    --seconds) SECONDS_ARG=$2; shift 2 ;;
    --package) PACKAGE=$2; shift 2 ;;
    -h|--help|help) usage 0 ;;
    prepare|facts|package|record|lock|context|pull|clear) CMD=$1; shift ;;
    *) echo "unknown argument: $1" >&2; usage 2 ;;
  esac
done
[ -n "$CMD" ] || usage 2

case $SECONDS_ARG in
  ''|*[!0-9]*) echo "--seconds needs a whole number of seconds" >&2; exit 2 ;;
esac
if [ "$SECONDS_ARG" -lt 3 ] || [ "$SECONDS_ARG" -gt 60 ]; then
  echo "--seconds must be between 3 and 60" >&2
  exit 2
fi
case $PACKAGE in
  '') : ;;
  [!A-Za-z]*|*[!A-Za-z0-9_.]*|*.|*..*) echo "--package takes a package name like com.android.chrome" >&2; exit 2 ;;
esac

command -v adb >/dev/null || { echo "adb is not on PATH" >&2; exit 1; }

if [ -z "$SERIAL" ]; then
  DEVICES=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  COUNT=$(printf '%s\n' "$DEVICES" | grep -c . || true)
  case $COUNT in
    1) SERIAL=$DEVICES ;;
    0) echo "no device; connect one and pass -s SERIAL" >&2; exit 1 ;;
    *) echo "several devices are connected; pass -s SERIAL so the probe cannot land on the wrong one" >&2
       printf '  %s\n' $DEVICES >&2
       exit 1 ;;
  esac
fi
adb -s "$SERIAL" get-state >/dev/null 2>&1 || { echo "$SERIAL is not reachable" >&2; exit 1; }

# One command, one scenario, one report name: a report left by an earlier run can never be read
# back as this run's result.
RUN_ID=$(date +%Y%m%d-%H%M%S)-$$
SCENARIO_STARTED=0

say() { echo "[s0-manual] $*" >&2; }
shell() { adb -s "$SERIAL" shell "$@" 2>/dev/null | tr -d '\r'; }
prop() { shell getprop "$1"; }
reachable() { adb -s "$SERIAL" get-state >/dev/null 2>&1; }
screen_state() { shell dumpsys power | sed -n 's/.*mWakefulness=\([A-Za-z]*\).*/\1/p' | head -1; }
keyguard_showing() { shell dumpsys window | sed -n 's/.*isKeyguardShowing=\([a-z]*\).*/\1/p' | head -1; }
current_ime() { shell settings get secure default_input_method; }
probe_installed() { shell pm list packages "$PKG" | grep -qx "package:$PKG"; }
probe_ime_enabled() { shell ime list -s -a | grep -q 'ProbeIme'; }
probe_ime_selected() { case "$(current_ime)" in *ProbeIme) return 0 ;; *) return 1 ;; esac; }

new_run() {
  if [ -n "$OUT" ]; then return 0; fi
  OUT=$ROOT/.local/s0-probe/manual-$RUN_ID
  REPORTS=$OUT/reports
  PARTIAL_DIR=$OUT/partial
  mkdir -p "$REPORTS"
}

control() { # run measure record editor seconds delay; returns the start status
  local rc=0
  adb -s "$SERIAL" shell am start -W -n "$PKG/.ControlActivity" \
    --es mode control --es run "$1" --ez measure "$2" --ez record "$3" --es editor "$4" \
    --ei seconds "$5" --ei delayMs "$6" >/dev/null 2>&1 || rc=$?
  sleep 1
  return "$rc"
}

scenario_cleared() { # 0 explicitly cleared, 1 still armed, 2 cannot read or confirm
  local prefs rc=0
  prefs=$(shell run-as "$PKG" cat shared_prefs/control.xml 2>/dev/null) || rc=$?
  # A failed read, or no content at all, cannot confirm anything: a missing file after a clear
  # request may just mean the clear never took effect.
  if [ "$rc" -ne 0 ] || [ -z "$prefs" ]; then
    return 2
  fi
  case $prefs in
    *'name="measure" value="true"'*) return 1 ;;
    *'name="record" value="true"'*) return 1 ;;
    *'name="finished" value="true"'*) return 0 ;;
  esac
  return 2
}

clear_flags() { # starts the clear and confirms it took effect; non-zero means unconfirmed or armed
  local i state last=1
  if ! control cleared false false "" 3 0; then
    say "ERROR: could not start the probe's clear step; a pending scenario may still be armed"
    return 1
  fi
  for i in $(seq 1 10); do
    state=0
    scenario_cleared || state=$?
    last=$state
    if [ "$state" = 0 ]; then
      SCENARIO_STARTED=0
      return 0
    fi
    sleep 1
  done
  if [ "$last" = 2 ]; then
    say "ERROR: could not read back the probe's scenario state; cannot confirm it was cleared"
  else
    say "ERROR: the probe still has a pending scenario after clearing; a later field tap could still record or measure"
  fi
  return 1
}

# An interrupted manual command must not leave the probe armed: the scenario would otherwise
# record or measure the next field somebody taps.
cleanup() {
  local rc=$?
  set +e
  if [ "$SCENARIO_STARTED" = 1 ]; then
    if clear_flags; then
      say "cleared the pending scenario on $SERIAL"
    else
      say "a scenario may still be armed on $SERIAL; once the probe can start again, run: $0 -s $SERIAL clear"
      rc=1
    fi
  fi
  exit "$rc"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

pull_one() { # file name including .json
  local file=$1 out
  out=$(shell run-as "$PKG" cat "files/reports/$file" || true)
  case "$out" in
    \{*) printf '%s' "$out" > "$REPORTS/$file"; return 0 ;;
  esac
  return 1
}

pull_partial() { # file name including .json
  local file=$1 out
  out=$(shell run-as "$PKG" cat "files/reports/$file" || true)
  case "$out" in
    \{*) mkdir -p "$PARTIAL_DIR"; printf '%s' "$out" > "$PARTIAL_DIR/$file"; return 0 ;;
  esac
  return 1
}

validate() {
  local rc=0
  python3 "$DIR/summarize.py" "$REPORTS" > "$OUT/summary.txt" || rc=$?
  cat "$OUT/summary.txt"
  return "$rc"
}

require_probe() {
  if ! probe_installed; then
    cat >&2 <<EOF
The probe APK is not installed on $SERIAL. This script never installs a package; run:

  $DIR/manual.sh -s $SERIAL prepare

and install the APK it prints. If app.songyan.s0probe is already installed from an older probe,
remove it yourself first (adb -s $SERIAL uninstall $PKG); reports carrying unknown fields are
rejected, so a stale probe would be measured as a failure.
EOF
    exit 2
  fi
}

require_probe_ime() {
  if probe_ime_enabled && probe_ime_selected; then return 0; fi
  cat >&2 <<EOF
The probe input method is not enabled and selected on $SERIAL. This script never changes your
keyboard; do it yourself, either in Settings → System → Languages & input → On-screen keyboard →
Manage keyboards (enable "S0 Probe" and select it), or with:

  adb -s $SERIAL shell ime enable $IME
  adb -s $SERIAL shell ime set $IME

To restore your keyboard afterwards:

  adb -s $SERIAL shell ime set $(current_ime)
  adb -s $SERIAL shell ime disable $IME
EOF
  exit 2
}

require_mic() {
  if shell dumpsys package "$PKG" | grep 'android.permission.RECORD_AUDIO' | grep -q 'granted=true'; then
    return 0
  fi
  cat >&2 <<EOF
The probe app does not hold the microphone permission on $SERIAL, so a recording would measure an
unpermissioned app instead of the input method. This script never grants permissions; grant it
yourself, either in Settings → Apps → S0 Probe → Permissions → Microphone, or with:

  adb -s $SERIAL shell pm grant $PKG android.permission.RECORD_AUDIO
EOF
  exit 2
}

require_package() {
  if [ -n "$PACKAGE" ]; then return 0; fi
  cat >&2 <<EOF
This step needs the package name of the app you are measuring, so the probe can ignore every other
app. Open that app on the device, then run:

  $DIR/manual.sh -s $SERIAL package

and pass the name it prints as --package. Every step measures one editor in one named app.
EOF
  exit 2
}

foreground_package() {
  local line package
  line=$(shell dumpsys window | grep -m1 'mCurrentFocus=\|mFocusedApp=')
  package=$(printf '%s' "$line" | sed -n 's/.*[ {]u[0-9]* \([A-Za-z0-9_.]*\)\/.*/\1/p')
  if [ -z "$package" ]; then
    line=$(shell dumpsys activity activities | grep -m1 'mResumedActivity')
    package=$(printf '%s' "$line" | sed -n 's/.*[ {]u[0-9]* \([A-Za-z0-9_.]*\)\/.*/\1/p')
  fi
  printf '%s' "$package"
}

wait_report() { # report name without .json, timeout seconds
  local name=$1 limit=$2 waited=0
  while [ "$waited" -lt "$limit" ]; do
    if ! reachable; then
      say "the device stopped answering adb (a locked device can drop it); unlock it yourself and run: $0 -s $SERIAL pull"
      return 1
    fi
    if pull_one "$name.json"; then
      say "report $name.json arrived"
      return 0
    fi
    pull_partial "$name.partial.json" || true
    sleep 2
    waited=$((waited + 2))
    if [ $((waited % 30)) -eq 0 ]; then
      say "still waiting (${waited}s of ${limit}s): do the step on the device"
    fi
  done
  if [ -f "$PARTIAL_DIR/$name.partial.json" ]; then
    cp "$PARTIAL_DIR/$name.partial.json" "$REPORTS/$name.partial.json"
    say "no complete report; the partial one shows how far the run got"
  else
    say "no report arrived: the probe never started."
    say "Check that you tapped a field in $PACKAGE and that the probe keyboard appeared; if the"
    say "probe's process was restarted in between, nothing is collected on purpose: run the step again."
  fi
  return 1
}

finish_step() { # step name, result: complete | partial | missing
  local name=$1 result=$2 rc=0 clear_ok=1 detail
  if ! clear_flags; then clear_ok=0; fi
  validate || rc=$?
  if [ "$clear_ok" != 1 ]; then
    say "step $name could not clear its pending scenario; the run failed on that, not on the report"
    exit 1
  fi
  case $result in
    complete)
      say "reports: $OUT"
      [ "$rc" -eq 0 ] || exit 1
      ;;
    partial)
      # Only the lock step gets here: the recording was cut off (typically the probe was frozen
      # or killed while the screen was off), so the partial report is an incomplete measurement,
      # never a completed recording.
      if [ ! -f "$REPORTS/$name.partial.json" ]; then
        say "ERROR: neither a complete nor a partial report exists for $name"
        exit 1
      fi
      [ "$rc" -eq 0 ] || exit 1
      detail=$(python3 -B - "$REPORTS/$name.partial.json" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))
recording = data.get("recording") or {}
seconds = recording.get("perSecond") or []
last = seconds[-1] if seconds else {}
print("INCOMPLETE report, not a completed recording: the last sample is second %s"
      " with %s frames; screenInteractive=%s keyguardLocked=%s"
      % (last.get("second"), last.get("frames"),
         last.get("screenInteractive"), last.get("keyguardLocked")))
PY
)
      say "$detail"
      say "the partial report is kept as evidence: $REPORTS/$name.partial.json"
      say "reports: $OUT"
      exit 0
      ;;
    *)
      say "step $name incomplete: the summary above says how far it got; run the step again"
      exit 1
      ;;
  esac
}

facts_step() {
  new_run
  local reports=""
  if probe_installed; then reports=$(shell run-as "$PKG" ls files/reports 2>/dev/null || true); fi
  {
    echo "serial: $SERIAL"
    echo "manufacturer: $(prop ro.product.manufacturer)"
    echo "model: $(prop ro.product.model)"
    echo "device: $(prop ro.product.device)"
    echo "build-type: $(prop ro.build.type)"
    echo "sdk: $(prop ro.build.version.sdk) ($(prop ro.build.version.release))"
    echo "abis: $(prop ro.product.cpu.abilist)"
    echo "screen: $(screen_state)"
    echo "keyguard-showing: $(keyguard_showing)"
    echo "current-ime: $(current_ime)"
    if probe_installed; then echo "probe-installed: yes"; else echo "probe-installed: no"; fi
    if probe_ime_enabled; then echo "probe-ime-enabled: yes"; else echo "probe-ime-enabled: no"; fi
    echo "probe-reports-on-device: ${reports:-none}"
  } | tee "$OUT/facts.txt"
  say "written: $OUT/facts.txt"
}

prepare_step() {
  : "${ANDROID_HOME:?ANDROID_HOME is unset; source .local/env.sh in the main checkout first}"
  : "${JAVA_HOME:?JAVA_HOME is unset; source .local/env.sh in the main checkout first}"
  local apk
  apk=$("$DIR/build.sh")
  echo "built: $apk"
  echo
  echo "Install it yourself; this script never installs, overwrites or removes a package."
  if probe_installed; then
    echo "app.songyan.s0probe is already installed on $SERIAL. If it is an older probe, remove it"
    echo "yourself first, then install this build:"
    echo "  adb -s $SERIAL uninstall $PKG"
  fi
  echo "  adb -s $SERIAL install -t $apk"
  echo
  echo "Grant the microphone permission the recording steps need, if it is not granted yet:"
  echo "  adb -s $SERIAL shell pm grant $PKG android.permission.RECORD_AUDIO"
  echo
  echo "Then enable and select the probe keyboard; this script never changes your input method:"
  echo "  adb -s $SERIAL shell ime enable $IME"
  echo "  adb -s $SERIAL shell ime set $IME"
  echo
  echo "Current keyboard: $(current_ime)"
  echo "Restore it when you are done:"
  echo "  adb -s $SERIAL shell ime set $(current_ime)"
  echo "  adb -s $SERIAL shell ime disable $IME"
  echo
  echo "Then measure, one step per command. Each step takes the package of the app you will open;"
  echo "with that app in front, \"$0 -s $SERIAL package\" prints its name:"
  echo "  $0 -s $SERIAL record  --package com.example.notes"
  echo "  $0 -s $SERIAL lock    --package com.example.notes"
  echo "  $0 -s $SERIAL context --package com.android.chrome"
  echo "  $0 -s $SERIAL pull"
}

record_step() {
  local run name
  run="manual-ime-$RUN_ID"
  name="record-ime-$run"
  new_run
  require_probe
  require_probe_ime
  require_mic
  require_package
  clear_flags
  # Marked before the control call: a signal during the call must still clear what was armed.
  SCENARIO_STARTED=1
  if ! control "$run" false true "$PACKAGE" "$SECONDS_ARG" 2000; then
    say "could not start the probe control activity on $SERIAL"
    exit 1
  fi
  say "run $RUN_ID expects $name.json"
  cat >&2 <<EOF
Step: recording inside the visible input method (${SECONDS_ARG}s)
  1. On the device, open $PACKAGE and tap a text field in it. Fields in other apps collect
     nothing: the scenario is for $PACKAGE only.
  2. The probe keyboard appears and says when the microphone is live: 2 s after it shows, it
     records for ${SECONDS_ARG}s. This one command uses one field; repeat it for another run.
  3. Keep the screen on and do not switch apps during this run.
  4. This script collects the report by itself; there is nothing to type.
A control screen of the probe app flashes and closes first; that only tells the probe which
scenario to run. Your app, your text and the device state are untouched.
EOF
  if ! wait_report "$name" 180; then
    finish_step "$name" missing
  fi
  finish_step "$name" complete
  say "Recording ran in the probe input method; the summary lists the frames that arrived per second."
}

lock_step() {
  local run name
  run="manual-lock-$RUN_ID"
  name="record-ime-$run"
  new_run
  require_probe
  require_probe_ime
  require_mic
  require_package
  clear_flags
  # Marked before the control call: a signal during the call must still clear what was armed.
  SCENARIO_STARTED=1
  if ! control "$run" false true "$PACKAGE" "$SECONDS_ARG" 3000; then
    say "could not start the probe control activity on $SERIAL"
    exit 1
  fi
  say "run $RUN_ID expects $name.json"
  cat >&2 <<EOF
Step: recording across the lock screen (${SECONDS_ARG}s)
  1. On the device, open $PACKAGE and tap a text field in it. Fields in other apps collect
     nothing: the scenario is for $PACKAGE only.
  2. The probe keyboard appears; 3 s later it records for ${SECONDS_ARG}s and says so.
  3. While it says RECORDING, press the power button once to lock the screen.
  4. Leave it locked. This script does not turn the screen on, unlock or wake anything: it only
     reads the report over adb. Unlock the device yourself when the run is done.
If the system freezes or stops the probe while the screen is off, no completed recording exists;
the partial report is then reported as an incomplete measurement, not as a completed recording.
A device with a PIN, pattern or password gives the secure-lock-screen answer; without one the
summary reports a screen-off run that was never locked.
EOF
  if ! wait_report "$name" 240; then
    say "no completed report arrived; if the recording was cut off, the partial report is kept"
    finish_step "$name" partial
  fi
  finish_step "$name" complete
  say "Read the 'screen off from second ...' line above: that is the lock-screen evidence."
  say "If it says the screen off was never sampled, the lock part is unverified: repeat the step"
  say "and press the power button while the probe keyboard says RECORDING."
}

context_step() {
  local label run name
  label=$(printf '%s' "$PACKAGE" | tr -c 'A-Za-z0-9_-' '-')
  run="manual-$label-$RUN_ID"
  name="context-$run"
  new_run
  require_probe
  require_probe_ime
  require_package
  clear_flags
  # Marked before the control call: a signal during the call must still clear what was armed.
  SCENARIO_STARTED=1
  if ! control "$run" true false "$PACKAGE" 3 0; then
    say "could not start the probe control activity on $SERIAL"
    exit 1
  fi
  say "run $RUN_ID expects $name.json"
  cat >&2 <<EOF
Step: how much text a real app's editor hands the input method ($PACKAGE)
  1. With your own keyboard, prepare the text first: open $PACKAGE (Chrome, a WebView page or
     微信), tap the field and type or paste enough text to see where the app truncates (several
     thousand characters is useful), then dismiss the keyboard. The probe never writes into
     another app, so it cannot type this for you.
  2. Switch to the probe keyboard (this script never changes it), then start this step.
  3. Tap the same field once. The probe keyboard appears and records character counts only, never
     the characters. Fields in other apps collect nothing.
  4. Password fields, fields marked no-personalized-learning, and TYPE_NULL editors are skipped by
     design: nothing is measured there, so use another text field.
  5. Tapped the wrong field, or the field already had focus so the keyboard did not restart? Check
     the report above and run this command again: one command measures one field, once.
If the app asks you to accept terms, sign in or pick a service, close it and measure an app that
needs none of that; this measurement never requires an account.
This script does not open, stop or type into another app; you do it by hand.
EOF
  if ! wait_report "$name" 240; then
    finish_step "$name" missing
  fi
  finish_step "$name" complete
  say "The summary names the app that was measured; the numbers are getTextBeforeCursor lengths."
}

pull_step() {
  new_run
  require_probe
  local listing file count=0
  listing=$(shell run-as "$PKG" ls files/reports 2>/dev/null || true)
  if [ -z "$listing" ]; then
    say "no reports on $SERIAL yet; run record, lock or context first"
    exit 1
  fi
  # A word list, not a read loop: adb shell can consume a redirected stdin and skip entries.
  for file in $listing; do
    case $file in
      *.json) if pull_one "$file"; then count=$((count + 1)); fi ;;
    esac
  done
  if [ "$count" -eq 0 ]; then
    say "no readable report on $SERIAL"
    exit 1
  fi
  say "pulled $count report(s) into $REPORTS"
  if ! validate; then exit 1; fi
  say "reports: $OUT"
}

case $CMD in
  facts) facts_step ;;
  prepare) prepare_step ;;
  package)
    new_run
    package=$(foreground_package)
    if [ -z "$package" ]; then
      say "could not read the app in front; open the app on the device and run this again"
      exit 1
    fi
    printf '%s\n' "$package" | tee "$OUT/package.txt"
    say "pass it as --package: $0 -s $SERIAL record --package $package"
    ;;
  record) record_step ;;
  lock) lock_step ;;
  context) context_step ;;
  pull) pull_step ;;
  clear)
    if clear_flags; then
      say "probe scenario flags cleared; the probe will not record or measure until a step starts"
    else
      say "the probe's pending scenario was not cleared and may still be armed"
      exit 1
    fi
    ;;
esac