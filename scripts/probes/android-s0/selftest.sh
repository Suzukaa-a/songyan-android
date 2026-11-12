#!/usr/bin/env bash
# Behaviour tests for the Android S0 probe, on one emulator: the manual flow must only read the
# report of its own run, must not collect from password / TYPE_NULL / no-personalized-learning
# editors, and must leave no scenario armed when the manual command is interrupted. The control
# entry is checked from the host side; the refusal for an app without DUMP is reported as
# unverified because no second app can be driven on this device. Report validation is tested on
# fixtures as well, so an "unknown" denial is never counted as proof.
#
#   source /path/to/songyan-android/.local/env.sh
#   scripts/probes/android-s0/selftest.sh [-s SERIAL] [--keep-installed]
#
# Like run.sh this drives the device automatically (input method, screen), so it runs on an
# emulator only and installs its own probe APK, which it removes again unless --keep-installed.
set -eo pipefail

DIR=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$DIR/../../.." && pwd)
PKG=app.songyan.s0probe
OTHER_PKG=app.songyan.s0probe.other
OTHER_ACT=$OTHER_PKG/.RefusalActivity
IME=$PKG/.ProbeIme
SERIAL=$ANDROID_SERIAL
[ -n "$SERIAL" ] || SERIAL=$DEVICE
KEEP_INSTALLED=0
PASS=0
FAIL=0
UNVERIFIED=0
INSTALLED_BY_TEST=0
INSTALLED_OTHER=0
ORIG_IME=""

usage() {
  awk 'NR > 1 && /^set -eo pipefail/ {exit} NR > 1 {sub(/^# ?/, ""); print}' "$0"
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

[ -n "$ANDROID_HOME" ] || { echo "ANDROID_HOME is unset; source the main checkout .local/env.sh" >&2; exit 1; }
[ -n "$JAVA_HOME" ] || { echo "JAVA_HOME is unset; source the main checkout .local/env.sh" >&2; exit 1; }
command -v adb >/dev/null || { echo "adb is not on PATH" >&2; exit 1; }

if [ -z "$SERIAL" ]; then
  DEVICES=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  COUNT=$(printf '%s\n' "$DEVICES" | grep -c . || true)
  if [ "$COUNT" != 1 ]; then echo "pass -s SERIAL (one device only)" >&2; exit 1; fi
  SERIAL=$DEVICES
fi
say() { echo "[selftest] $*" >&2; }
shell() { adb -s "$SERIAL" shell "$@" 2>/dev/null | tr -d '\r'; }
pass() { PASS=$((PASS + 1)); echo "PASS $*"; }
fail() { FAIL=$((FAIL + 1)); echo "FAIL $*"; }

[ "$(shell getprop ro.kernel.qemu)" = 1 ] || { echo "this test drives the device; run it on an emulator" >&2; exit 1; }
OUT=$ROOT/.local/s0-probe/selftest-$(date +%Y%m%d-%H%M%S)
mkdir -p "$OUT"

restore() {
  local rc=$? trash
  set +e
  if [ -n "$ORIG_IME" ] && [ "$ORIG_IME" != "$IME" ]; then shell ime set "$ORIG_IME" >/dev/null; fi
  shell ime disable "$IME" >/dev/null
  if [ "$INSTALLED_OTHER" = 1 ]; then
    shell pm uninstall "$OTHER_PKG" >/dev/null ||
      { say "WARNING: could not uninstall $OTHER_PKG"; FAIL=$((FAIL + 1)); }
  fi
  if [ "$INSTALLED_BY_TEST" = 1 ] && [ "$KEEP_INSTALLED" = 0 ]; then
    shell am force-stop "$PKG" >/dev/null
    shell pm uninstall "$PKG" >/dev/null ||
      { say "WARNING: could not uninstall $PKG"; FAIL=$((FAIL + 1)); }
  fi
  [ "$FAIL" -eq 0 ] || rc=1
  say "done: $PASS passed, $FAIL failed, $UNVERIFIED unverified; logs $OUT"
  exit "$rc"
}
trap restore EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

APK=$("$DIR/build.sh")
OTHER_APK=$("$DIR/build.sh" other)
ORIG_IME=$(shell settings get secure default_input_method)
for pkg in "$PKG" "$OTHER_PKG"; do
  if shell pm list packages "$pkg" | grep -qx "package:$pkg"; then
    echo "refusing: $pkg is already installed; this test never overwrites or removes an" >&2
    echo "existing package. Remove it yourself first: adb -s $SERIAL uninstall $pkg" >&2
    exit 1
  fi
done
# 0. Clearing a scenario that cannot reach the probe must fail and must not claim success.
clear_log=$OUT/clear-failure.log
clear_rc=0
"$DIR/manual.sh" -s "$SERIAL" clear >"$clear_log" 2>&1 || clear_rc=$?
if [ "$clear_rc" -ne 0 ]; then
  pass "clear without a reachable probe exits non-zero"
else
  fail "clear without a reachable probe claimed success"
fi
if [ -s "$clear_log" ]; then
  pass "clear failure leaves a diagnostic"
else
  fail "clear failure printed nothing"
fi

adb -s "$SERIAL" install -t "$APK" >/dev/null && INSTALLED_BY_TEST=1
adb -s "$SERIAL" install -t "$OTHER_APK" >/dev/null && INSTALLED_OTHER=1
for i in $(seq 1 30); do shell ime list -a -s | grep -q "ProbeIme" && break; sleep 1; done
shell pm grant "$PKG" android.permission.RECORD_AUDIO >/dev/null || true
shell ime enable "$IME" >/dev/null
shell ime set "$IME" >/dev/null

# 0b. Clearing is confirmed across processes by reading the persisted scenario back.
adb -s "$SERIAL" shell am start -n "$PKG/.ControlActivity" --es run clear-confirm --ez measure true \
  --es editor "$PKG" >/dev/null 2>&1
armed=0
for i in $(seq 1 10); do
  armed_prefs=$(shell run-as "$PKG" cat shared_prefs/control.xml 2>/dev/null || true)
  case $armed_prefs in
    *'name="measure" value="true"'*) armed=1; break ;;
  esac
  sleep 1
done
if [ "$armed" = 1 ]; then
  pass "a scenario is armed in the persisted store"
else
  fail "could not arm a scenario for the clear test"
fi
clear_confirm_rc=0
"$DIR/manual.sh" -s "$SERIAL" clear >"$OUT/clear-confirm.log" 2>&1 || clear_confirm_rc=$?
cleared_prefs=$(shell run-as "$PKG" cat shared_prefs/control.xml 2>/dev/null || true)
if [ "$clear_confirm_rc" -eq 0 ] &&
   ! printf '%s' "$cleared_prefs" | grep -q 'name="measure" value="true"' &&
   ! printf '%s' "$cleared_prefs" | grep -q 'name="record" value="true"' &&
   printf '%s' "$cleared_prefs" | grep -q 'name="finished" value="true"'; then
  pass "clear confirms the persisted scenario is gone"
else
  fail "clear reported success but the persisted scenario is still armed"
fi

# 0c. A scenario state that cannot be read back must not be reported as cleared. The failure is
# simulated by making the persisted prefs unreadable to the probe, then restoring them.
echo "--- 0c read-back failure ---" >/dev/null
adb -s "$SERIAL" shell am start -n "$PKG/.ControlActivity" --es run clear-readback --ez measure true \
  --es editor "$PKG" >/dev/null 2>&1
armed=0
for i in $(seq 1 10); do
  armed_prefs=$(shell run-as "$PKG" cat shared_prefs/control.xml 2>/dev/null || true)
  case $armed_prefs in
    *'name="measure" value="true"'*) armed=1; break ;;
  esac
  sleep 1
done
if [ "$armed" = 1 ]; then
  shell run-as "$PKG" chmod 000 shared_prefs >/dev/null 2>&1
  unreadable_rc=0
  "$DIR/manual.sh" -s "$SERIAL" clear >"$OUT/clear-readback.log" 2>&1 || unreadable_rc=$?
  shell run-as "$PKG" chmod 700 shared_prefs >/dev/null 2>&1
  recovered_rc=0
  "$DIR/manual.sh" -s "$SERIAL" clear >"$OUT/clear-recovered.log" 2>&1 || recovered_rc=$?
  recovered_prefs=$(shell run-as "$PKG" cat shared_prefs/control.xml 2>/dev/null || true)
  if [ "$unreadable_rc" -ne 0 ]; then
    pass "clear fails when the scenario state cannot be read back"
  else
    fail "clear claimed success while the scenario state could not be read"
  fi
  if [ "$recovered_rc" -eq 0 ] &&
     ! printf '%s' "$recovered_prefs" | grep -q 'name="measure" value="true"' &&
     printf '%s' "$recovered_prefs" | grep -q 'name="finished" value="true"'; then
    pass "clear succeeds and is confirmed once the state is readable again"
  else
    fail "clear could not confirm after the state became readable again"
  fi
else
  fail "could not arm a scenario for the read-back test"
fi

reset_probe() {
  local i current
  shell am force-stop "$PKG" >/dev/null 2>&1
  sleep 1
  # force-stop makes the system fall back to another keyboard; re-selecting it can take a moment.
  for i in $(seq 1 20); do
    shell ime enable "$IME" >/dev/null 2>&1
    shell ime set "$IME" >/dev/null 2>&1
    current=$(shell settings get secure default_input_method)
    case $current in
      *ProbeIme*) sleep 1; return 0 ;;
    esac
    sleep 1
  done
  say "could not select the probe input method (current: $current)"
  return 0
}
reports() { shell run-as "$PKG" ls files/reports 2>/dev/null | sort || true; }
control() { # run measure record editor seconds delay; returns the start status
  local rc=0
  adb -s "$SERIAL" shell am start -n "$PKG/.ControlActivity" --es run "$1" --ez measure "$2" \
    --ez record "$3" --es editor "$4" --ei seconds "$5" --ei delayMs "$6" >/dev/null 2>&1 || rc=$?
  sleep 1
  return "$rc"
}
clear_control() {
  local rc=0
  adb -s "$SERIAL" shell am start -n "$PKG/.ControlActivity" --ez measure false --ez record false \
    >/dev/null 2>&1 || rc=$?
  sleep 1
  return "$rc"
}
launch_host() { # editor kind
  adb -s "$SERIAL" shell am start -n "$PKG/.HostActivity" --es editor "$1" >/dev/null 2>&1
}
log_has() { adb -s "$SERIAL" logcat -d -s S0Probe:I | grep -q "$1"; }
wait_reports_stay() { # expected listing, seconds
  local expected=$1 i
  for i in $(seq 1 "$2"); do
    if [ "$(reports)" != "$expected" ]; then return 1; fi
    sleep 1
  done
  return 0
}
wait_prefs_finished() { # seconds
  local i prefs
  for i in $(seq 1 "$2"); do
    prefs=$(shell run-as "$PKG" cat shared_prefs/control.xml 2>/dev/null || true)
    case $prefs in
      *'finished" value="true"'*) return 0 ;;
    esac
    sleep 1
  done
  return 1
}

# 1. A manual step reads only the report of its own run.
run_manual_record() { # label; prints the report name and run directory this run produced
  local label=$1 pid i name run_dir
  reset_probe
  adb -s "$SERIAL" logcat -c
  "$DIR/manual.sh" -s "$SERIAL" record --package "$PKG" --seconds 4 >"$OUT/$label.out" 2>"$OUT/$label.err" &
  pid=$!
  armed=0
  for i in $(seq 1 20); do
    if log_has "CONTROL run=manual-ime-"; then armed=1; break; fi
    sleep 1
  done
  if [ "$armed" = 1 ]; then
    launch_host edittext
  else
    say "the scenario never armed for $label"
  fi
  for i in $(seq 1 60); do
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
  done
  kill -0 "$pid" 2>/dev/null && kill -TERM "$pid"
  wait "$pid" || true
  name=$(sed -n 's/.*expects \(.*\)\.json$/\1/p' "$OUT/$label.err" | head -1)
  run_dir=$(sed -n 's/.*reports: \(.*\)$/\1/p' "$OUT/$label.err" | tail -1)
  printf '%s %s' "$name" "$run_dir"
}
reset_probe
adb -s "$SERIAL" logcat -c
read -r first first_dir <<EOF
$(run_manual_record record-1)
EOF
read -r second second_dir <<EOF
$(run_manual_record record-2)
EOF
if [ -n "$first" ] && [ -n "$second" ] && [ "$first" != "$second" ]; then
  pass "each manual run has its own report name ($first vs $second)"
else
  fail "manual runs reused a report name (first=$first second=$second)"
fi
case $first in
  record-ime-manual-ime-*) pass "report name carries the run id" ;;
  *) fail "report name does not carry a run id: $first" ;;
esac
if [ -f "$first_dir/reports/$first.json" ] && [ -f "$second_dir/reports/$second.json" ] &&
   [ ! -e "$second_dir/reports/$first.json" ]; then
  pass "each run pulled only its own report"
else
  fail "a run did not pull only its own report (first=$first_dir second=$second_dir)"
fi

# 2. Editors the product must not learn from are not measured either.
for kind in password nolearn null; do
  reset_probe
  adb -s "$SERIAL" logcat -c
  before=$(reports)
  case $kind in
    password) expect=ineligible=password ;;
    nolearn) expect=ineligible=no-personalized-learning ;;
    null) expect=ineligible=TYPE_NULL ;;
  esac
  control "ineligible-$kind" true false "$PKG" 3 0
  launch_host "$kind"
  # Poll for what the keyboard actually did instead of sleeping a fixed time.
  seen=""
  for i in $(seq 1 20); do
    if log_has "$expect"; then seen=skip; break; fi
    if log_has "IME-SHOW"; then seen=show; fi
    sleep 1
  done
  after=$(reports)
  clear_control
  if [ "$before" != "$after" ]; then
    fail "a $kind editor produced a report"
  else
    pass "no report from a $kind editor"
  fi
  if [ "$seen" = skip ]; then
    pass "$kind editor skipped as $expect"
  elif [ "$kind" = null ] && [ "$seen" != show ]; then
    # A TYPE_NULL field need not start an input session at all. With no session nothing can be
    # collected, but the ineligible branch itself was never exercised, so this is not a pass.
    UNVERIFIED=$((UNVERIFIED + 1))
    echo "UNVERIFIED null editor started no input session; nothing was collected, but the TYPE_NULL skip branch was not exercised"
  else
    fail "$kind editor was not skipped for the expected reason ($expect)"
  fi
done

# 3. Interrupting a manual command leaves no scenario armed.
reset_probe
adb -s "$SERIAL" logcat -c
before=$(reports)
"$DIR/manual.sh" -s "$SERIAL" record --package "$PKG" --seconds 6 >"$OUT/interrupt.out" 2>"$OUT/interrupt.err" &
pid=$!
armed=0
for i in $(seq 1 20); do
  if log_has "CONTROL run=manual-ime-"; then armed=1; break; fi
  sleep 1
done
if [ "$armed" = 1 ]; then
  kill -TERM "$pid"
  wait "$pid" || true
  if wait_prefs_finished 10; then
    pass "interrupted command cleared the scenario"
  else
    fail "interrupted command left the scenario armed"
  fi
  launch_host edittext
  if wait_reports_stay "$before" 10; then
    pass "no report after an interrupted command"
  else
    fail "a report appeared after an interrupted command"
  fi
else
  fail "the manual command never armed a scenario (nothing to interrupt)"
  kill -TERM "$pid" 2>/dev/null || true
fi

# 5. An app that holds no permissions cannot start the control entry; the host shell can. The
# ordinary app attempts the start itself and reports what the platform did, so nothing here reads
# the probe's source or its packaged manifest.
adb -s "$SERIAL" logcat -c
adb -s "$SERIAL" shell am start -n "$OTHER_ACT" >/dev/null 2>&1
refusal=""
for i in $(seq 1 20); do
  refusal=$(adb -s "$SERIAL" logcat -d -s S0Other:I | grep "REFUSAL" | tail -1 || true)
  [ -n "$refusal" ] && break
  sleep 1
done
case $refusal in
  *"REFUSAL DENIED"*"android.permission.DUMP"*)
    pass "an app without DUMP cannot start the control entry"
    echo "$refusal"
    ;;
  *"REFUSAL DENIED"*)
    fail "refused for a reason other than the DUMP permission: $refusal"
    ;;
  *"REFUSAL STARTED"*)
    fail "an app without DUMP started the control entry"
    ;;
  *)
    fail "the ordinary app reported nothing: $refusal"
    ;;
esac
if adb -s "$SERIAL" logcat -d -s S0Probe:I | grep -q "CONTROL run="; then
  fail "an app without DUMP armed the probe"
else
  pass "no scenario was armed by the ordinary app"
fi
if adb -s "$SERIAL" shell am start -n "$PKG/.ControlActivity" --ez measure false --ez record false >/dev/null 2>&1; then
  pass "the host shell (holds DUMP) can still start the control entry"
else
  fail "the host shell cannot start the control entry"
fi

# 6. A lock run that is cut off before its last second is kept as an incomplete measurement,
# never reported as a completed recording.
reset_probe
adb -s "$SERIAL" logcat -c
lock_err=$OUT/lock-partial.err
"$DIR/manual.sh" -s "$SERIAL" lock --package "$PKG" --seconds 5 >"$OUT/lock-partial.out" 2>"$lock_err" &
lock_pid=$!
lock_armed=0
for i in $(seq 1 20); do
  if log_has "CONTROL run=manual-lock-"; then lock_armed=1; break; fi
  sleep 1
done
partial_seen=0
if [ "$lock_armed" = 1 ]; then
  launch_host edittext
  for i in $(seq 1 30); do
    if shell run-as "$PKG" ls files/reports 2>/dev/null | grep -q "\.partial\.json"; then
      partial_seen=1
      break
    fi
    sleep 1
  done
fi
if [ "$partial_seen" = 1 ]; then
  shell am force-stop "$PKG" >/dev/null
else
  say "no partial report appeared; cannot cut the lock run short"
fi
lock_waited=0
while [ "$lock_waited" -lt 300 ]; do
  kill -0 "$lock_pid" 2>/dev/null || break
  sleep 5
  lock_waited=$((lock_waited + 5))
done
lock_rc=0
wait "$lock_pid" || lock_rc=$?
if [ "$partial_seen" = 1 ] && [ "$lock_rc" -eq 0 ]; then
  pass "a partial-only lock run exits 0 as an incomplete measurement"
else
  fail "a partial-only lock run did not report itself as an incomplete measurement (rc=$lock_rc)"
fi
lock_dir=$(sed -n 's/.*reports: \(.*\)$/\1/p' "$lock_err" | tail -1)
lock_partial=""
if [ -n "$lock_dir" ]; then
  for candidate in "$lock_dir"/reports/*.partial.json; do
    [ -f "$candidate" ] && lock_partial=$candidate
  done
fi
if [ -n "$lock_partial" ]; then
  pass "the partial report is kept as evidence"
else
  fail "the partial report was not kept as evidence (dir=$lock_dir)"
fi
if [ -n "$lock_partial" ]; then
  partial_state=$(python3 -B -c 'import json,sys; r=(json.load(open(sys.argv[1])).get("recording") or {}); print("complete=%s seconds=%s" % (r.get("complete"), len(r.get("perSecond") or [])))' "$lock_partial" 2>/dev/null || true)
  case $partial_state in
    complete=None*[!0-9]|[!c]*) fail "the partial report has no per-second samples: $partial_state" ;;
    complete=None*) pass "the partial report is kept as an incomplete recording (no complete flag)" ;;
    *) fail "the partial report was altered: $partial_state" ;;
  esac
fi

# 7. Report validation: unknown denial is not proof, partial is evidence, read-only never writes.
fixture_rc=0
python3 -B - "$DIR/summarize.py" "$OUT" <<'PY' || fixture_rc=$?
import json, os, sys
sys.path.insert(0, os.path.dirname(sys.argv[1]))
import summarize

out = sys.argv[2]
problems = []

def expect(label, cond):
    print(("PASS " if cond else "FAIL ") + label)
    if not cond:
        problems.append(label)

caps = {"appPrivateReadVerdict": "unknown", "appPrivateWriteVerdict": "unknown",
        "socketConnectVerdict": "unknown", "checkInternet": "denied",
        "checkRecordAudio": "denied", "appPrivateContent": None}
base = {"device": {"sdk": 36}, "hostPid": 1, "hostUid": 1, "canaryCreated": True,
        "canaryLength": 16, "coldPid": 2, "secondColdPid": 3, "pidChanged": True,
        "secondColdAgeMs": 10, "hostUid": 1,
        "cold": {"bindToFirstReplyMs": 5, "uid": 4}, "secondCold": {"bindToFirstReplyMs": 5},
        "warm": {"calls": 3, "replies": 3, "p50Us": 10}, "capabilities": caps}
unknown, _ = summarize.check_isolated(json.loads(json.dumps(base)))
expect("unknown file/network denial is recorded as unproven",
       len(unknown) == 0 and len(summarize.NOTES) >= 2)
expect("unknown is not claimed as proof", all("not a proven" in n or "not proof" in n
                                              for n in summarize.NOTES if "unknown" in n))
denied = json.loads(json.dumps(base))
denied["capabilities"].update({"appPrivateReadVerdict": "denied",
                               "appPrivateWriteVerdict": "denied",
                               "socketConnectVerdict": "denied"})
summarize.NOTES.clear()
expect("a real denial still validates", summarize.check_isolated(denied)[0] == [])
expect("a real denial leaves no unproven note",
       len(summarize.NOTES) == 1 and "boundary proven" in summarize.NOTES[0])

record = {"scenario": "record", "where": "ime", "device": {"sdk": 36},
          "recording": {"permission": "granted", "readErrors": 0, "totalFrames": 8000,
                        "deviceSecure": False, "screenInteractiveAtStart": True,
                        "keyguardLockedAtStart": False,
                        "perSecond": [{"second": 0, "frames": 8000, "peak": 0,
                                       "recordingState": 3, "atMs": 1,
                                       "screenInteractive": True, "keyguardLocked": False,
                                       "silenced": False}]}}
expect("partial report is evidence", summarize.check_record(record, True)[0] == [])
expect("an unfinished report is not complete", len(summarize.check_record(record, False)[0]) > 0)
readonly = {"scenario": "context", "device": {"sdk": 36}, "editor": "com.android.chrome",
            "mode": "read-only", "inputType": 1, "initial": [], "availableLength": 10,
            "wrote": False, "deleted": False, "cleanup": "not-attempted"}
expect("read-only measurement validates", summarize.check_context(readonly)[0] == [])
readonly["wrote"] = True
expect("read-only measurement that writes is rejected", len(summarize.check_context(readonly)[0]) > 0)
sys.exit(1 if problems else 0)
PY
if [ "$fixture_rc" = 0 ]; then pass "report validation fixtures"; else fail "report validation fixtures"; fi

say "result: $PASS passed, $FAIL failed, $UNVERIFIED unverified"