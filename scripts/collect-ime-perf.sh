#!/usr/bin/env bash
# Drives the IME on a device/emulator and reports the SongyanPerf latency baseline.
#
# Builds (unless --no-build) and installs the debug APK, enables the Songyan input method, turns on
# the SongyanPerf log tag, then runs a deterministic scenario in the settings 试打 screen:
#   * N keyboard shows (BACK hides, the text field re-focuses) -> show_us
#   * M letter taps on the drawn keyboard                     -> key_us
#   * one first-candidate tap per round                       -> commit_us
# Key and candidate coordinates come from the debug-gated key_bound/cand0 lines the IME emits when
# the tag is on; uiautomator only locates the 试打 row and the text field, it does not expose the
# IME window. The raw logcat stays on disk for inspection. Needs no root.
#
# Usage: scripts/collect-ime-perf.sh [options]
#   --serial <serial>  adb serial (default: $ANDROID_SERIAL, else emulator-5554)
#   --shows <n>        keyboard show/dismiss cycles (default 10)
#   --keys <n>         letter taps in total (default 20, split evenly over rounds)
#   --rounds <n>       rounds of letter taps; a candidate tap closes each round (default 2)
#   --no-build         reuse app/build/outputs/apk/debug/app-debug.apk
#   --no-install       do not install the APK
#   --keep-app         keep the running IME process (default: force-stop it first)
#   --keep-log <path>  raw logcat copy (default .local/perf/songyanperf-<serial>.log)
#   --help             show this usage
# Prints count/min/p50/p90/p99/max in microseconds for show_us, key_us and commit_us, and exits
# non-zero when the scenario collected fewer samples than requested.
set -uo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root" || exit 1

serial=${ANDROID_SERIAL:-emulator-5554}
shows=10
keys=20
rounds=2
build=true
install=true
restart_app=true
raw_log=""

# The debug build's package carries a .debug suffix; the classes keep the app.songyan.ime namespace.
pkg=app.songyan.ime.debug
ime_id=$pkg/app.songyan.ime.SongyanInputMethodService
activity=$pkg/app.songyan.ime.SongyanSettingsActivity
tag=SongyanPerf

usage() {
  sed -n '2,22p' "${BASH_SOURCE[0]}" | sed -E 's/^# ?//'
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial) serial="${2:?--serial needs a value}"; shift 2 ;;
    --shows) shows="${2:?--shows needs a value}"; shift 2 ;;
    --keys) keys="${2:?--keys needs a value}"; shift 2 ;;
    --rounds) rounds="${2:?--rounds needs a value}"; shift 2 ;;
    --no-build) build=false; shift ;;
    --no-install) install=false; shift ;;
    --keep-app) restart_app=false; shift ;;
    --keep-log) raw_log="${2:?--keep-log needs a value}"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) echo "unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done
: "${raw_log:=$root/.local/perf/songyanperf-$serial.log}"
mkdir -p "$(dirname "$raw_log")" || exit 1

if [[ -n "${ADB:-}" ]]; then
  adb_bin=$ADB
elif command -v adb >/dev/null 2>&1; then
  adb_bin=adb
elif [[ -n "${ANDROID_HOME:-}" && -x "$ANDROID_HOME/platform-tools/adb" ]]; then
  adb_bin=$ANDROID_HOME/platform-tools/adb
else
  echo "adb not found; put it on PATH or set ADB or ANDROID_HOME" >&2
  exit 2
fi
command -v python3 >/dev/null 2>&1 || { echo "python3 is required" >&2; exit 2; }

step() { printf '\n==> %s\n' "$*"; }
# Failing mid-run still leaves the partially collected samples on disk for triage.
die() {
  perf_lines > "$raw_log" 2>/dev/null
  echo "error: $*" >&2
  [[ -s "$raw_log" ]] && echo "partial logcat: $raw_log" >&2
  exit 1
}
dev() { "$adb_bin" -s "$serial" "$@"; }

tmp=$(mktemp -d)
cleanup() {
  dev shell rm -f /sdcard/songyan-perf.xml >/dev/null 2>&1
  rm -rf "$tmp"
}
trap cleanup EXIT

# node_center <xml> <text|class|desc> <needle> -> "x y" of the first matching node centre
node_center() {
  python3 - "$1" "$2" "$3" <<'PY'
import sys
import xml.etree.ElementTree as ET

path, kind, needle = sys.argv[1], sys.argv[2], sys.argv[3]
key = {"text": "text", "class": "class", "desc": "content-desc"}[kind]
try:
    root = ET.parse(path).getroot()
except Exception:
    sys.exit(1)
for node in root.iter("node"):
    value = node.get(key) or ""
    if needle in value:
        x1, y1, x2, y2 = (int(v) for v in node.get("bounds").strip("[]").replace("][", ",").split(","))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        sys.exit(0)
sys.exit(1)
PY
}

dump_ui() {
  dev shell uiautomator dump /sdcard/songyan-perf.xml >/dev/null 2>&1
  dev pull /sdcard/songyan-perf.xml "$1" >/dev/null 2>&1
  [[ -s "$1" ]]
}

# Makes sure the settings 试打 screen with its text field is in front.
open_tryout() {
  local xml="$tmp/ui.xml" attempt xy
  for attempt in $(seq 1 30); do
    if dump_ui "$xml"; then
      if xy=$(node_center "$xml" class android.widget.EditText 2>/dev/null); then
        return 0
      fi
      if xy=$(node_center "$xml" text 试打 2>/dev/null); then
        dev shell input tap $xy >/dev/null 2>&1
        sleep 1
        continue
      fi
    fi
    if (( attempt % 3 == 0 )); then
      dev shell am start -n "$activity" >/dev/null 2>&1
    fi
    sleep 1
  done
  return 1
}

# Taps the try-out text field so the IME window is requested. The screen is re-opened when a
# navigation animation or a stray system UI change hides it between the dump and the tap.
tap_field() {
  local xml="$tmp/ui.xml" attempt xy
  for attempt in 1 2 3 4 5; do
    if dump_ui "$xml"; then
      if xy=$(node_center "$xml" class android.widget.EditText 2>/dev/null); then
        dev shell input tap $xy >/dev/null 2>&1
        return 0
      fi
    fi
    open_tryout >/dev/null 2>&1
    sleep 0.5
  done
  return 1
}

perf_lines() { dev logcat -d -v brief -s "$tag" 2>/dev/null; }

# One logcat dump per step instead of one per tap; adb round trips dominate otherwise.
perf_snapshot=""
refresh_perf() { perf_snapshot=$(perf_lines); }

hide_keyboard() {
  dev shell input keyevent KEYCODE_BACK >/dev/null 2>&1
  sleep 0.6
}

# True when the IME process sees a published generation. Only works for debuggable APKs.
engine_published() {
  local out
  out=$(dev shell run-as "$pkg" cat files/rime/active.json 2>/dev/null | tr -d '\r')
  [[ "$out" == *'"generation"'* ]]
}

# Waits until the keyboard has rendered at least one layout and logged its key boxes.
wait_for_keyboard() {
  local deadline=$((SECONDS + ${1:-60}))
  while (( SECONDS < deadline )); do
    if perf_lines | grep -q 'key_bound i='; then return 0; fi
    sleep 1
  done
  return 1
}

ready_count() { perf_lines | grep -c ' ready$'; }

# Waits for the ready counter to grow past <baseline>. The baseline MUST be sampled before the show
# is requested: the marker is logged while the show runs, so sampling afterwards misses it and only
# produces a false warning.
# The session then accepted the editor and taps are not dropped. A build without the marker only
# warns: key boxes already proved the keyboard is usable.
wait_for_ready() {
  local baseline=${1:-0} window=${2:-10} deadline count
  deadline=$((SECONDS + window))
  while (( SECONDS < deadline )); do
    count=$(ready_count)
    if (( count > baseline )); then return 0; fi
    sleep 0.3
  done
  echo "warning: no fresh ready marker within ${window}s; taps may be dropped" >&2
}

# Types one letter and checks the engine composes pinyin instead of inserting ASCII. A keyboard
# left in English mode by an earlier run would report key taps but never log candidates.
ensure_chinese_mode() {
  local attempt first second lang before after
  for attempt in 1 2 3; do
    refresh_perf
    first=$(key_center n)
    second=$(key_center i)
    [[ -n "$first" && -n "$second" ]] || return 1
    before=$(printf '%s\n' "$perf_snapshot" | grep -c ' cand0 ')
    # Two letters: "n" alone is not a complete syllable and yields no candidates even in pinyin mode.
    dev shell input tap $first >/dev/null 2>&1
    sleep 0.25
    dev shell input tap $second >/dev/null 2>&1
    sleep 0.7
    refresh_perf
    after=$(printf '%s\n' "$perf_snapshot" | grep -c ' cand0 ')
    if (( after > before )); then
      dev shell input keyevent KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL >/dev/null 2>&1
      sleep 0.2
      return 0
    fi
    lang=$(printf '%s\n' "$perf_snapshot" | grep ' kind=LANGUAGE_SWITCH ' | tail -1 |
      sed -E 's/.* cx=([0-9]+) cy=([0-9]+).*/\1 \2/')
    [[ -n "$lang" ]] || return 1
    echo "not composing pinyin yet; switching the language key (attempt $attempt)" >&2
    dev shell input tap $lang >/dev/null 2>&1
    sleep 0.5
  done
  return 1
}

key_center() {
  printf '%s\n' "$perf_snapshot" | grep ' key_bound ' | grep " value=$1 " | tail -1 |
    sed -E 's/.* cx=([0-9]+) cy=([0-9]+).*/\1 \2/'
}

candidate_center() {
  printf '%s\n' "$perf_snapshot" | grep ' cand0 ' | tail -1 | sed -E 's/.* cx=([0-9]+) cy=([0-9]+).*/\1 \2/'
}

step "device $serial"
dev wait-for-device || die "no device $serial"
for _ in $(seq 1 30); do
  [[ "$(dev shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]] && break
  sleep 1
done
printf 'model %s, API %s, build %s\n' \
  "$(dev shell getprop ro.product.model | tr -d '\r')" \
  "$(dev shell getprop ro.build.version.sdk | tr -d '\r')" \
  "$(dev shell getprop ro.build.version.incremental | tr -d '\r')"

if $build; then
  step "building :app:assembleDebug"
  ./gradlew --console=plain :app:assembleDebug || die "build failed"
fi
apk=app/build/outputs/apk/debug/app-debug.apk
[[ -f "$apk" ]] || die "missing $apk (run without --no-build)"

if $install; then
  step "installing $apk"
  dev install -r "$apk" >/dev/null || die "install failed"
  # The script drives the settings UI by its Chinese labels; the emulator's system locale may
  # differ, so pin the app language and pre-grant the notification prompt that would cover it.
  dev shell cmd locale set-app-locales "$pkg" --user 0 --locales zh-CN >/dev/null 2>&1 || true
  dev shell pm grant "$pkg" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
fi

if $restart_app; then
  # A cold process makes runs comparable: no leftover session state and no leftover input mode.
  # force-stop also drops the selection, so the IME is selected again afterwards.
  step "restarting $pkg"
  dev shell am force-stop "$pkg" >/dev/null 2>&1
  sleep 1
fi

step "enabling the input method"
dev shell ime enable "$ime_id" >/dev/null 2>&1
dev shell ime set "$ime_id" >/dev/null 2>&1
sleep 0.5
current=$(dev shell settings get secure default_input_method | tr -d '\r')
[[ "$current" == "$ime_id" ]] || die "could not select $ime_id (current: $current)"

step "turning on log.tag.$tag"
dev shell setprop "log.tag.$tag" DEBUG
[[ "$(dev shell getprop "log.tag.$tag" | tr -d '\r')" == DEBUG ]] || die "could not set log.tag.$tag"
dev shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
dev shell wm dismiss-keyguard >/dev/null 2>&1

step "opening settings"
dev shell am start -n "$activity" >/dev/null 2>&1
sleep 2

# The IME only loads published generations; on a fresh install the settings screen asks the deploy
# service to run. run-as needs a debuggable APK, so when it is unavailable this check is skipped and
# the key-box wait below still gates on real readiness.
step "checking the published engine"
if engine_published; then
  echo "published engine present"
else
  echo "no published engine yet; waiting for the deploy service (a first deployment takes a while)"
  for _ in $(seq 1 60); do
    engine_published && break
    sleep 5
  done
  engine_published || die "engine not published after 5 minutes: open the settings app once and retry"
fi

step "opening 试打"
open_tryout || die "could not reach the 试打 screen"
ready_before=$(ready_count)
tap_field
# The engine deployment on a fresh install can take a while; the first keyboard layout is the signal.
step "waiting for the keyboard and engine"
if ! wait_for_keyboard 120; then
  refresh_perf
  if printf '%s\n' "$perf_snapshot" | grep -q 'show_us='; then
    die "the keyboard shows but logs no key boxes: the ImePerf hooks in ime/ were dropped (see ImePerf.kt)"
  fi
  die "no SongyanPerf lines: check log.tag.SongyanPerf, the ImePerf hooks and the engine deployment"
fi

# Taps landing while the session is still preparing are dropped; wait for the ready marker of this
# show (its counter was captured before the show was requested) before typing anything.
if ! wait_for_ready "$ready_before" 20; then
  die "the session never became ready: ImePerf.ready() missing or the engine failed to load"
fi

# The IME may come back in English mode; the letter metric only means anything while composing pinyin.
# The probe needs the key boxes of the current show, so the log is cleared only afterwards.
step "checking the input mode"
ensure_chinese_mode || die "the keyboard is not composing pinyin; check the input mode on the device"

# One throwaway show, word and candidate commit warm the IME process, the engine and the caches.
# Cold-start samples would otherwise land in the first measured cycle and dominate p99.
step "warm-up"
wait_for_keyboard 30 || die "keyboard did not stay up for the warm-up"
refresh_perf
for letter in h a o; do
  xy=$(key_center "$letter")
  [[ -n "$xy" ]] && dev shell input tap $xy >/dev/null 2>&1
  sleep 0.15
done
refresh_perf
xy=$(candidate_center)
[[ -n "$xy" ]] && dev shell input tap $xy >/dev/null 2>&1
sleep 0.5
# Delete what the warm-up left so every measured long-press-free tap starts from a short line.
dev shell input keyevent KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL >/dev/null 2>&1
hide_keyboard
dev logcat -c >/dev/null 2>&1

step "keyboard shows: $shows cycles"
for ((i = 1; i <= shows; i++)); do
  hide_keyboard
  open_tryout || die "lost the 试打 screen before show $i"
  tap_field || die "no text field before show $i"
  sleep 1.0
done

step "key taps: $keys letters in $rounds rounds, one candidate tap per round"
per_round=$(( (keys + rounds - 1) / rounds ))
# "ni hao" cycles: every prefix is a valid pinyin spelling, so candidates exist at every step.
letters=(n i h a o)
for ((r = 1; r <= rounds; r++)); do
  hide_keyboard
  open_tryout || die "lost the 试打 screen before round $r"
  ready_before=$(ready_count)
  tap_field || die "no text field before round $r"
  wait_for_keyboard 30 || die "keyboard did not appear in round $r"
  wait_for_ready "$ready_before" 10
  # The first injected tap can land before the IME window accepts touches; losing the first
  # letter turns the rest of the cycle into an invalid spelling with no candidates.
  sleep 0.4
  refresh_perf
  for ((k = 1; k <= per_round; k++)); do
    letter=${letters[$(( (k - 1) % ${#letters[@]} ))]}
    xy=$(key_center "$letter")
    [[ -n "$xy" ]] || die "no key box for '$letter'"
    dev shell input tap $xy >/dev/null 2>&1
    sleep 0.12
  done
  refresh_perf
  xy=$(candidate_center)
  # The last key's log line can lag one flush; a missing sample must not be mistaken for a
  # missing candidate page.
  for _ in 1 2 3 4 5; do
    [[ -n "$xy" ]] && break
    sleep 0.25
    refresh_perf
    xy=$(candidate_center)
  done
  if [[ -n "$xy" ]]; then
    dev shell input tap $xy >/dev/null 2>&1
    sleep 0.4
  else
    # No candidate page: flush the composition with space so the next round starts clean.
    echo "warning: no candidate in round $r, committing with space" >&2
    space=$(printf '%s\n' "$perf_snapshot" | grep ' kind=SPACE ' | tail -1 |
      sed -E 's/.* cx=([0-9]+) cy=([0-9]+).*/\1 \2/')
    [[ -n "$space" ]] && dev shell input tap $space >/dev/null 2>&1
    sleep 0.3
  fi
done

step "collecting logcat"
perf_lines > "$raw_log"
echo "raw log: $raw_log"

python3 - "$raw_log" "$shows" "$keys" <<'PY'
import math
import re
import sys

path, want_shows, want_keys = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
text = open(path, encoding="utf-8", errors="replace").read()

metrics = [
    ("show_us", "onStartInputView -> first keyboard frame", want_shows),
    ("key_us", "key ACTION_DOWN -> preedit applied", want_keys),
    ("commit_us", "candidate tap -> commit applied", None),
]

def samples(field, limit=None):
    values = sorted(int(v) for v in re.findall(r"\b" + field + r"=(\d+)", text))
    return values if limit is None else values[:limit]

def pct(values, p):
    # Nearest-rank percentile: the smallest value whose rank covers p.
    return values[max(0, math.ceil(p * len(values)) - 1)]

print()
header = f'{"metric":<10} {"count":>5} {"min":>9} {"p50":>9} {"p90":>9} {"p99":>9} {"max":>9}'
print(header)
print("-" * len(header))
short = []
for field, what, want in metrics:
    # The rounds re-show the keyboard, so extra show samples may trail the measured cycles; the
    # first N (N = --shows) ones are the counted sequence.
    values = samples(field, want)
    if not values:
        print(f"{field:<10} {0:>5} {'-':>9} {'-':>9} {'-':>9} {'-':>9} {'-':>9}")
        short.append(field)
        continue
    print(
        f"{field:<10} {len(values):>5} {values[0]:>9} {pct(values, .50):>9} "
        f"{pct(values, .90):>9} {pct(values, .99):>9} {values[-1]:>9}"
    )
    if want is not None and len(values) < want:
        short.append(f"{field} ({len(values)} < {want})")

print()
print("all values in microseconds; measured on the debug build with debug=true")
print("key_us: finger press to preedit; commit_us: candidate tap to committed text")
for field, what, _ in metrics:
    print(f"  {field:<10} {what}")

if short:
    print()
    print("incomplete samples: " + ", ".join(short), file=sys.stderr)
    sys.exit(1)
PY
