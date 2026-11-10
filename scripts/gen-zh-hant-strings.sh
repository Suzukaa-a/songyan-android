#!/usr/bin/env bash
# Generates the Traditional Chinese (Taiwan) resources for app, ime and settings from the
# Simplified Chinese resources in values-zh. Text is converted with OpenCC s2twp and then
# corrected with Taiwan wording that the converter misses. Resource names, attributes,
# comments, order, indentation and formatting are preserved byte for byte; only human-readable
# text changes.
# Usage: scripts/gen-zh-hant-strings.sh [--check]
#   no argument  write <module>/src/main/res/values-b+zh+Hant/
#   --check      regenerate into a fresh temporary directory, print its path, compare it with
#                the committed resources and fail on missing, extra or different files or on a
#                banned term. The temporary directory is kept for the caller to inspect and
#                move to the Trash.
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

check=false
case "${1:-}" in
  "")
    ;;
  --check)
    check=true
    ;;
  -h | --help)
    sed -n '2,11p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 0
    ;;
  *)
    echo "unknown argument: $1 (usage: scripts/gen-zh-hant-strings.sh [--check])" >&2
    exit 2
    ;;
esac

if ! command -v opencc >/dev/null 2>&1; then
  echo "OpenCC is missing (brew install opencc); generation requires the pinned engine build's compatible OpenCC 1.1.9 CLI on PATH" >&2
  exit 1
fi
opencc_bin=$(command -v opencc)
# Both the CLI schema and phrase dictionaries affect the generated wording.
opencc_version=$("$opencc_bin" --version 2>&1)
if [[ "$opencc_version" != *"Version: 1.1.9"* ]]; then
  echo "OpenCC 1.1.9 is required; add the CLI from the pinned engine build to PATH" >&2
  exit 1
fi
opencc_config="${OPENCC_DATA_DIR:-$root/core/rime/src/main/assets/rime/opencc}/s2twp.json"
if [[ ! -f "$opencc_config" ]]; then
  echo "bundled OpenCC s2twp data not found; sync the iOS engine resources and retry" >&2
  exit 1
fi

out_root=$root
if $check; then
  out_root=$(mktemp -d "${TMPDIR:-/tmp}/songyan-zh-hant-check.XXXXXX")
  echo "check output: $out_root"
fi

python3 - "$root" "$out_root" "$opencc_bin" "$opencc_config" <<'PY'
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

root, out_root, opencc_bin, opencc_config = sys.argv[1:5]
modules = ("app", "ime", "settings")
source_dir = "values-zh"
target_dir = "values-b+zh+Hant"
# Shared Taiwan wording is owned by the iOS manifest source, not copied into this script.
with open(os.path.join(root, "scripts", "zh-Hant-overrides.json"), encoding="utf-8") as stream:
    shared_wording = json.load(stream)
replacements = shared_wording["replacements"]
replacements.sort(key=lambda pair: -len(pair[0]))
banned = sorted({left for left, _ in replacements}, key=len, reverse=True)

# Wording that depends on the resource: the same source term is a verb elsewhere (for example a
# keyboard that is using a page), so these apply only to the named resource, never globally.
key_replacements = {}
for key in (
    "learning_error_no_source",
    "learning_error_nothing",
    "learning_error_other_source",
    "learning_files_skip_not_installed",
    "rime_config_folder_active",
    "rime_config_folder_active_none",
    "rime_config_skipped",
    "learning_loading",
    "learning_no_source",
    "learning_current",
    "home_summary_keyboard",
    "schemes_status_in_use",
    "ime_item_selected",
    "pages_delete_in_use_title",
    "layout_editor_action_jump_hint",
    "keyboard_menu_body",
    "keyboard_menu_in_use",
    "shuangpin_entry_on",
    "shuangpin_error_missing_selection",
):
    key_replacements[key] = [("正在使用", "使用中")]

for key in (
    "ime_panel_emoji_title",
    "ime_toolbar_emoji",
    "ime_emoji_search_empty",
    "ime_emoji_recent_empty",
    "ime_emoji_loading",
    "candidate_bar_hide_toolbar_body",
):
    key_replacements[key] = [("表情", "表情符號")]

for key in (
    "rime_config_new_script",
    "rime_config_script_create",
    "shuangpin_new",
    "shuangpin_editor_new_title",
):
    key_replacements[key] = [("新建", "新增")]

for key in (
    "options_wide_position_section",
    "options_split_layout_body",
    "options_split_on",
    "layout_editor_split_section",
    "layout_editor_split_automatic",
):
    key_replacements[key] = [("大屏", "大螢幕")]

key_replacements["options_wide_note"] = [
    ("摺疊屏", "摺疊螢幕"),
    ("折叠屏", "摺疊螢幕"),
    ("大屏", "大螢幕"),
]

for key in ("shuangpin_error_id", "rime_config_script_name_rule"):
    key_replacements[key] = [("下劃線", "底線"), ("下划线", "底線")]

comment_replacements = [("表情面板", "表情符號面板")]

string_re = re.compile(r"(<string\b([^>]*)>)(.*?)(</string>)", re.S)
item_re = re.compile(r"(<item\b([^>]*)>)(.*?)(</item>)", re.S)
comment_re = re.compile(r"(<!--)(.*?)(-->)", re.S)
plurals_re = re.compile(r"<plurals\b([^>]*)>.*?</plurals>", re.S)
name_attr_re = re.compile(r'\bname="([^"]*)"')
ascii_re = re.compile(r"[\x00-\x7f]")


def fail(message):
    print(message, file=sys.stderr)
    sys.exit(1)


def ascii_only(text):
    return "".join(ascii_re.findall(text))


def convert(texts):
    pending = [text for text in texts if text.strip()]
    if not pending:
        return list(texts)
    result = subprocess.run(
        [opencc_bin, "-c", opencc_config],
        input="\n".join(pending),
        text=True,
        capture_output=True,
    )
    if result.returncode != 0:
        fail("opencc failed: " + result.stderr.strip())
    if result.stderr.strip():
        fail("opencc reported an error: " + result.stderr.strip())
    lines = result.stdout.split("\n")
    if lines and lines[-1] == "":
        lines.pop()
    if len(lines) != len(pending):
        fail("opencc returned %d lines for %d inputs" % (len(lines), len(pending)))
    converted = iter(lines)
    return [next(converted) if text.strip() else text for text in texts]


def apply_replacements(text, rules, source_path):
    for left, right in rules:
        text = text.replace(left, right)
    for left, right in rules:
        if left not in right and left in text:
            fail("%s: wording %r was not converted" % (source_path, left))
    return text


def to_taiwan(text):
    for left, right in replacements:
        text = text.replace(left, right)
    return text


def process_file(source_path, module):
    with open(source_path, encoding="utf-8", newline="") as handle:
        source = handle.read()
    plurals_ranges = []
    for match in plurals_re.finditer(source):
        name_match = name_attr_re.search(match.group(1))
        plurals_ranges.append(
            (match.start(), match.end(), name_match.group(1) if name_match else None)
        )
    spans = []
    for match in string_re.finditer(source):
        name_match = name_attr_re.search(match.group(2))
        spans.append(
            (
                match.start(3),
                match.end(3),
                match.group(3),
                name_match.group(1) if name_match else None,
                "string",
            )
        )
    for match in item_re.finditer(source):
        key = None
        for start, end, plurals_name in plurals_ranges:
            if start < match.start(3) < end:
                key = plurals_name
                break
        spans.append((match.start(3), match.end(3), match.group(3), key, "item"))
    for match in comment_re.finditer(source):
        spans.append((match.start(2), match.end(2), match.group(2), None, "comment"))
    spans.sort()
    for earlier, later in zip(spans, spans[1:]):
        if earlier[1] > later[0]:
            fail("%s: overlapping translatable regions" % source_path)
    texts = [span[2] for span in spans]
    for text in texts:
        if "\r" in text:
            fail("%s: carriage return inside a translatable value" % source_path)
    converted = convert(texts)
    output = []
    cursor = 0
    for (start, end, original, key, kind), translated in zip(spans, converted):
        translated = to_taiwan(translated)
        if kind == "comment":
            translated = apply_replacements(translated, comment_replacements, source_path)
        elif key in key_replacements:
            translated = apply_replacements(translated, key_replacements[key], source_path)
        if ascii_only(original) != ascii_only(translated):
            fail("%s: placeholders or escapes changed in %r" % (source_path, original))
        output.append(source[cursor:start])
        output.append(translated)
        cursor = end
    output.append(source[cursor:])
    text = "".join(output)
    for term in banned:
        if term in text:
            fail("%s: banned wording left in the generated file: %s" % (source_path, term))
    try:
        ET.fromstring(text)
    except ET.ParseError as error:
        fail("%s: generated XML is not well-formed: %s" % (source_path, error))
    target_path = os.path.join(
        out_root, module, "src", "main", "res", target_dir, os.path.basename(source_path)
    )
    os.makedirs(os.path.dirname(target_path), exist_ok=True)
    with open(target_path, "w", encoding="utf-8", newline="") as handle:
        handle.write(text)
    return os.path.basename(source_path), text


def main():
    generated = 0
    failures = 0
    for module in modules:
        source_path_dir = os.path.join(root, module, "src", "main", "res", source_dir)
        if not os.path.isdir(source_path_dir):
            fail("missing Simplified resources: " + source_path_dir)
        names = sorted(
            name
            for name in os.listdir(source_path_dir)
            if name.startswith("strings") and name.endswith(".xml")
        )
        if not names:
            fail("no strings*.xml files in " + source_path_dir)
        for name in names:
            process_file(os.path.join(source_path_dir, name), module)
            generated += 1
            if out_root != root:
                committed_path = os.path.join(
                    root, module, "src", "main", "res", target_dir, name
                )
                regenerated_path = os.path.join(
                    out_root, module, "src", "main", "res", target_dir, name
                )
                if not os.path.isfile(committed_path):
                    print("missing: " + committed_path, file=sys.stderr)
                    failures += 1
                    continue
                with open(committed_path, "rb") as handle:
                    committed = handle.read()
                with open(regenerated_path, "rb") as handle:
                    regenerated = handle.read()
                if committed != regenerated:
                    print("differs: " + committed_path, file=sys.stderr)
                    failures += 1
        if out_root != root:
            committed_dir = os.path.join(root, module, "src", "main", "res", target_dir)
            existing = (
                sorted(
                    name
                    for name in os.listdir(committed_dir)
                    if name.startswith("strings") and name.endswith(".xml")
                )
                if os.path.isdir(committed_dir)
                else []
            )
            for name in existing:
                if name not in names:
                    print("extra: " + os.path.join(committed_dir, name), file=sys.stderr)
                    failures += 1
    if out_root == root:
        print("generated %d files" % generated)
    else:
        if failures:
            fail(
                "%d Traditional Chinese resource file(s) differ from the committed ones"
                % failures
            )
        print("check passed: %d files match" % generated)


main()
PY
