#!/usr/bin/env bash
# Records, on a test emulator, how Android Settings names its on-screen-keyboard pages and
# how the Songyan input method is labelled, once per requested system locale.
#
# Usage (needs a userdebug emulator, because the system locale is changed with adb root):
#   DEVICE=emulator-5554 [ADB=/path/to/platform-tools/adb] \
#       /path/to/songyan-android/.scratch/ondevice.sh \
#       bash scripts/observe-ime-system-locales.sh [--locales ja-JP,zh-TW,zh-HK] [--restore en-US]
#
# Run it through the main repo's .scratch/ondevice.sh so the emulator stays locked for the
# whole run. The script reboots the device for every locale and always restores the system
# locale that was effective when it started (also when it fails part-way); --restore only
# overrides that target. ADB must resolve to an executable: pass ADB=... or have adb on PATH.
# Screenshots go to this worktree's .scratch/system-locales and JSON to .local/system-locales
# (override with SYSTEM_LOCALES_SHOTS / SYSTEM_LOCALES_LOGS).
#
# Per locale it: (1) asserts the current effective system locale is NOT the target (red),
# (2) sets persist.sys.locale + Settings.System.system_locales, reboots, and asserts that
# adb shell am get-config reports the target (green), (3) opens the system pages through
# their real Settings intent actions, screenshots them and records the uiautomator texts,
# (4) records the IME subtype data and the app locale override.
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ADB=${ADB:-$(command -v adb || true)}
if [ -z "$ADB" ] || [ ! -x "$ADB" ]; then
  echo "error: adb not found; pass ADB=/path/to/platform-tools/adb or put adb on PATH" >&2
  exit 2
fi
SHOTS=${SYSTEM_LOCALES_SHOTS:-$ROOT/.scratch/system-locales}
LOGS=${SYSTEM_LOCALES_LOGS:-$ROOT/.local/system-locales}
IME_PKG=app.songyan.ime.debug
IME_ID=$IME_PKG/app.songyan.ime.SongyanInputMethodService
LOCALES=ja-JP,zh-TW,zh-HK
RESTORE=

while [ $# -gt 0 ]; do
  case "$1" in
    --locales) LOCALES=$2; shift 2 ;;
    --restore) RESTORE=$2; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
IFS=, read -r -a TARGETS <<<"$LOCALES"

mkdir -p "$SHOTS" "$LOGS"
say() { echo "[observe] $*" >&2; }
fail() { echo "FAIL: $*" >&2; exit 1; }

adb_sh() { "$ADB" shell "$@" 2>/dev/null | tr -d '\r'; }

config_line() {
  local out
  out=$("$ADB" shell am get-config 2>/dev/null | sed -n 1p | tr -d '\r')
  [ -n "$out" ] || fail "adb shell am get-config returned nothing"
  printf '%s\n' "$out"
}

# Effective system locale in the configuration, in Android's legacy form, e.g. "ja-rJP".
config_locale() {
  local token
  token=$(config_line | grep -oE '[a-z]{2,3}(-r[A-Z]{2})?-ldltr' | sed 's/-ldltr$//')
  [ -n "$token" ] || fail "cannot parse a locale out of am get-config"
  printf '%s\n' "$token"
}

# Legacy configuration form "en-rUS" -> BCP-47 form "en-US" that setprop and Settings expect.
bcp_locale() { printf '%s\n' "$1" | sed -E 's/^([A-Za-z]{2,3})-r([A-Za-z]{2})$/\1-\2/'; }

# "ja-JP" -> "ja-rjp", comparable with config_locale lowercased. Also normalises case.
expected_token() {
  local lang region
  if ! [[ $1 =~ ^([A-Za-z]{2,3})-([A-Za-z]{2})$ ]]; then
    fail "locale must look like ja-JP, got: $1"
  fi
  lang=$(printf '%s' "${BASH_REMATCH[1]}" | tr '[:upper:]' '[:lower:]')
  region=$(printf '%s' "${BASH_REMATCH[2]}" | tr '[:lower:]' '[:upper:]')
  printf '%s-r%s\n' "$lang" "$(printf '%s' "$region" | tr '[:upper:]' '[:lower:]')"
}

current_token() { config_locale | tr '[:upper:]' '[:lower:]'; }

wait_boot() {
  local tries=0 boot=""
  "$ADB" wait-for-device
  while [ "$tries" -lt 120 ]; do
    tries=$((tries + 1))
    boot=$(adb_sh getprop sys.boot_completed || true)
    if [ "$boot" = 1 ]; then
      break
    fi
    sleep 2
  done
  [ "$boot" = 1 ] || fail "device did not finish booting within 240 s"
  sleep 4
  "$ADB" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  "$ADB" shell wm dismiss-keyguard >/dev/null 2>&1 || true
}

set_system_locale() {
  local loc=$1 uid="" attempt=0
  "$ADB" root >/dev/null 2>&1 || true
  sleep 2
  "$ADB" wait-for-device
  # adb root restarts adbd; a shell that arrives too early silently does nothing, so verify a
  # root shell and read both values back before rebooting.
  while [ "$attempt" -lt 3 ]; do
    attempt=$((attempt + 1))
    uid=$(adb_sh id -u || true)
    if [ "$uid" = 0 ]; then
      break
    fi
    sleep 2
    "$ADB" root >/dev/null 2>&1 || true
    sleep 2
    "$ADB" wait-for-device
  done
  [ "$uid" = 0 ] || fail "adb root did not take effect"
  attempt=0
  while [ "$attempt" -lt 3 ]; do
    attempt=$((attempt + 1))
    "$ADB" shell setprop persist.sys.locale "$loc" >/dev/null
    "$ADB" shell settings put system system_locales "$loc" >/dev/null
    if [ "$(adb_sh settings get system system_locales || true)" = "$loc" ] &&
       [ "$(adb_sh getprop persist.sys.locale || true)" = "$loc" ]; then
      break
    fi
    sleep 2
  done
  [ "$(adb_sh settings get system system_locales || true)" = "$loc" ] ||
    fail "cannot persist system_locales=$loc"
  [ "$(adb_sh getprop persist.sys.locale || true)" = "$loc" ] ||
    fail "cannot persist persist.sys.locale=$loc"
  sleep 1
  "$ADB" reboot >/dev/null
  wait_boot
}

# capture_page <tag> <am start args...>: force-stop Settings, open the page, dump UI + screenshot.
capture_page() {
  local tag=$1 start_out dump_out magic
  shift
  "$ADB" shell am force-stop com.android.settings >/dev/null 2>&1 || true
  start_out=$("$ADB" shell am start -W "$@" 2>&1 || true)
  printf '%s\n' "$start_out" > "$LOGS/$tag.start.txt"
  case "$start_out" in
    *"Status: ok"*) ;;
    *) fail "am start failed for $tag: $start_out" ;;
  esac
  sleep 2
  dump_out=$("$ADB" shell uiautomator dump /sdcard/observe-ui.xml 2>&1 || true)
  case "$dump_out" in
    *"dumped to"*) ;;
    *) fail "uiautomator dump failed for $tag: $dump_out" ;;
  esac
  "$ADB" exec-out cat /sdcard/observe-ui.xml > "$LOGS/$tag.ui.xml"
  [ -s "$LOGS/$tag.ui.xml" ] || fail "empty UI dump for $tag"
  "$ADB" exec-out screencap -p > "$SHOTS/$tag.png"
  [ -s "$SHOTS/$tag.png" ] || fail "empty screenshot for $tag"
  magic=$(od -An -tx1 -N4 "$SHOTS/$tag.png" | tr -d ' \n')
  [ "$magic" = 89504e47 ] || fail "screenshot for $tag is not a PNG"
}

ime_subtype_facts() {
  local raw count locale tag mode
  raw=$("$ADB" shell ime list -a 2>/dev/null | tr -d '\r' |
    sed -n "/^$IME_PKG\//,/^[a-z]/p")
  [ -n "$raw" ] || fail "ime list -a has no entry for $IME_PKG"
  printf '%s\n' "$raw" > "$LOGS/ime-list.txt"
  count=$(grep -m1 -o 'InputMethodSubtype array: count=[0-9]*' "$LOGS/ime-list.txt" || true)
  locale=$(grep -m1 -o 'mSubtypeLocale=[^ ]*' "$LOGS/ime-list.txt" || true)
  tag=$(grep -m1 -o 'mSubtypeLanguageTag=[^ ]*' "$LOGS/ime-list.txt" || true)
  mode=$(grep -m1 -o 'mSubtypeMode=[^ ]*' "$LOGS/ime-list.txt" || true)
  [ -n "$locale" ] && [ -n "$count" ] || fail "cannot read the IME subtype data"
  printf '%s %s %s %s\n' "$locale" "$tag" "$mode" "$count"
}

# assemble <tag> <locale>: write the per-locale JSON from the captured files.
assemble() {
  local tag=$1 loc=$2
  python3 - "$LOGS/$tag.facts" "$LOGS" "$SHOTS" > "$LOGS/$loc.json" <<'PY'
import json, os, re, sys
import xml.etree.ElementTree as ET

facts_path, logs, shots = sys.argv[1], sys.argv[2], sys.argv[3]
facts = {}
for line in open(facts_path, encoding="utf-8"):
    line = line.rstrip("\n")
    if line and "\t" in line:
        k, v = line.split("\t", 1)
        facts[k] = v

def bounds(node):
    m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.get("bounds", ""))
    return tuple(int(x) for x in m.groups()) if m else None

def page(xml_path):
    root = ET.parse(xml_path).getroot()
    nodes = list(root.iter())
    title = None
    for n in nodes:
        if n.get("resource-id", "").endswith("collapsing_toolbar") and n.get("content-desc"):
            title = n.get("content-desc")
            break
    titles, summaries = [], []
    for n in nodes:
        rid = n.get("resource-id", "")
        if rid == "android:id/title" and n.get("text"):
            titles.append((bounds(n), n.get("text")))
        elif rid == "android:id/summary" and n.get("text"):
            summaries.append((bounds(n), n.get("text")))
    rows = []
    for tb, text in titles:
        if not tb:
            continue
        summary = None
        for sb, stext in summaries:
            if not sb:
                continue
            if sb[0] >= tb[0] - 40 and sb[2] <= tb[2] + 40 and 0 <= sb[1] - tb[3] <= 140:
                summary = stext
                break
        rows.append({"title": text, "summary": summary})
    return {"title": title, "rows": rows,
            "all_texts": [n.get("text") for n in nodes if n.get("text")]}

pages = {}
for name, tag in (("on_screen_keyboard", facts["tag.avail"]),
                  ("languages", facts["tag.locale"]),
                  ("ime_subtype", facts["tag.subtype"])):
    xml = os.path.join(logs, tag + ".ui.xml")
    entry = {"dump": tag + ".ui.xml", "screenshot": tag + ".png"}
    if os.path.exists(xml) and os.path.getsize(xml) > 0:
        p = page(xml)
        entry.update(p)
        entry["screenshot"] = tag + ".png"
    else:
        entry["error"] = "no uiautomator dump"
    pages[name] = entry

songyan = None
for row in pages["on_screen_keyboard"].get("rows", []):
    if "Songyan" in (row["title"] or ""):
        songyan = row
        break

out = {
    "locale": facts["locale"],
    "device": facts["device"],
    "android": {"sdk": facts["sdk"], "release": facts["release"], "build_type": facts["build_type"]},
    "system_locale_change": "adb root; setprop persist.sys.locale <locale>; settings put system system_locales <locale>; reboot",
    "red_assertion": {
        "config_before": facts["config_before"],
        "target_token": facts["target_token"],
        "target_absent_before": facts["target_absent"].strip() == "true",
    },
    "green_assertion": {
        "config_after": facts["config_after"],
        "persist_sys_locale": facts["persist"],
        "settings_system_locales": facts["system_locales"],
        "config_has_target": facts["config_has_target"].strip() == "true",
        "settings_match": facts["settings_match"].strip() == "true",
    },
    "ipc": {
        "default_input_method": facts["default_ime"],
        "songyan_packages": facts["songyan_packages"].split(",") if facts["songyan_packages"] else [],
        "app_locale_override": facts["app_locales"],
        "ime_subtype": facts["ime_subtype"],
    },
    "pages": pages,
    "songyan_row": songyan,
    "commands": facts["commands"].split(" ; "),
}
print(json.dumps(out, ensure_ascii=False, indent=2))
PY
}

restore_original() {
  local loc=$1 cfg
  say "restoring system locale to $loc"
  set_system_locale "$loc"
  cfg=$(config_line)
  [ "$(current_token)" = "$(expected_token "$loc")" ] || fail "restore: config is $cfg"
  capture_page restore-languages -a android.settings.LOCALE_SETTINGS
  {
    printf 'locale\t%s\n' "$loc"
    printf 'config_after\t%s\n' "$cfg"
    printf 'persist\t%s\n' "$(adb_sh getprop persist.sys.locale || true)"
    printf 'system_locales\t%s\n' "$(adb_sh settings get system system_locales || true)"
    printf 'app_locales\t%s\n' "$(adb_sh cmd locale get-app-locales "$IME_PKG" || true)"
    printf 'default_ime\t%s\n' "$(adb_sh settings get secure default_input_method || true)"
    printf 'enabled_imes\t%s\n' "$(adb_sh ime list -s | paste -sd, - || true)"
  } > "$LOGS/restore.facts"
  python3 - "$LOGS/restore.facts" > "$LOGS/restore.json" <<'PY'
import json, sys
f = {}
for line in open(sys.argv[1], encoding="utf-8"):
    if "\t" in line:
        k, v = line.rstrip("\n").split("\t", 1)
        f[k] = v
f["screenshot"] = "restore-languages.png"
f["ui_xml"] = "restore-languages.ui.xml"
print(json.dumps({"restored_system_locale": f}, ensure_ascii=False, indent=2))
PY
}

# --- baseline -------------------------------------------------------------
ORIG_CONFIG=$(config_line)
ORIG_PROP=$(adb_sh getprop persist.sys.locale || true)
ORIG_SETTINGS=$(adb_sh settings get system system_locales || true)
ORIG_BCP=$(bcp_locale "$(config_locale)")
say "baseline: config=$ORIG_CONFIG persist.sys.locale=$ORIG_PROP system_locales=$ORIG_SETTINGS"
if [ -z "$RESTORE" ]; then
  RESTORE=$ORIG_BCP
fi
expected_token "$RESTORE" >/dev/null

CHANGED=0
RESTORED=0
cleanup() {
  local rc=$?
  trap - EXIT
  set +e
  if [ "$CHANGED" = 1 ] && [ "$RESTORED" != 1 ]; then
    echo "[observe] failure (exit $rc): restoring captured system locale $ORIG_BCP" >&2
    if ( set_system_locale "$ORIG_BCP" ); then
      RESTORED=1
    else
      echo "[observe] restoring $ORIG_BCP also failed" >&2
    fi
  fi
  exit "$rc"
}
trap cleanup EXIT

CMDS="adb shell am get-config ; adb root ; adb shell setprop persist.sys.locale <locale> ; adb shell settings put system system_locales <locale> ; adb reboot ; adb shell am start -W -a android.settings.INPUT_METHOD_SETTINGS ; adb shell am start -W -a android.settings.LOCALE_SETTINGS ; adb shell am start -W -a android.settings.INPUT_METHOD_SUBTYPE_SETTINGS -e input_method_id $IME_ID ; adb shell uiautomator dump /sdcard/observe-ui.xml ; adb exec-out screencap -p ; adb shell ime list -a ; adb shell cmd locale get-app-locales $IME_PKG"

for LOC in "${TARGETS[@]}"; do
  TOK=$(expected_token "$LOC")
  BEFORE=$(config_line)
  BEFORE_TOK=$(current_token)
  say "$LOC: red assertion, effective config is $BEFORE"
  [ "$BEFORE_TOK" != "$TOK" ] || fail "$LOC: current system locale already $TOK, nothing to observe"
  CHANGED=1
  set_system_locale "$LOC"
  AFTER=$(config_line)
  say "$LOC: green assertion, effective config is $AFTER"
  [ "$(current_token)" = "$TOK" ] || fail "$LOC: system locale did not take effect (config $AFTER)"
  SET=$(adb_sh settings get system system_locales)
  [ "$SET" = "$LOC" ] || fail "$LOC: Settings.System.system_locales is $SET"

  capture_page "$LOC-on-screen-keyboard" -a android.settings.INPUT_METHOD_SETTINGS
  capture_page "$LOC-languages" -a android.settings.LOCALE_SETTINGS
  capture_page "$LOC-ime-subtype" -a android.settings.INPUT_METHOD_SUBTYPE_SETTINGS -e input_method_id "$IME_ID"

  {
    printf 'locale\t%s\n' "$LOC"
    printf 'device\t%s\n' "${ANDROID_SERIAL:-unknown}"
    printf 'sdk\t%s\n' "$(adb_sh getprop ro.build.version.sdk)"
    printf 'release\t%s\n' "$(adb_sh getprop ro.build.version.release)"
    printf 'build_type\t%s\n' "$(adb_sh getprop ro.build.type)"
    printf 'config_before\t%s\n' "$BEFORE"
    printf 'target_token\t%s\n' "$TOK"
    printf 'target_absent\ttrue\n'
    printf 'config_after\t%s\n' "$AFTER"
    printf 'persist\t%s\n' "$(adb_sh getprop persist.sys.locale)"
    printf 'system_locales\t%s\n' "$SET"
    printf 'config_has_target\ttrue\n'
    printf 'settings_match\ttrue\n'
    printf 'default_ime\t%s\n' "$(adb_sh settings get secure default_input_method)"
    printf 'songyan_packages\t%s\n' "$(adb_sh pm list packages | grep -i songyan | sed 's/^package://' | paste -sd, - || true)"
    printf 'app_locales\t%s\n' "$(adb_sh cmd locale get-app-locales "$IME_PKG")"
    printf 'ime_subtype\t%s\n' "$(ime_subtype_facts)"
    printf 'tag.avail\t%s\n' "$LOC-on-screen-keyboard"
    printf 'tag.locale\t%s\n' "$LOC-languages"
    printf 'tag.subtype\t%s\n' "$LOC-ime-subtype"
    printf 'commands\t%s\n' "$CMDS"
  } > "$LOGS/$LOC.facts"
  assemble "$LOC" "$LOC"
  say "$LOC: wrote $LOGS/$LOC.json"
done

restore_original "$RESTORE"
RESTORED=1
say "done: JSON in $LOGS, screenshots in $SHOTS; system locale back to $RESTORE"
