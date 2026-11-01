#!/usr/bin/env python3
"""Builds the emoji catalog the keyboard's emoji panel reads (ime/src/main/assets/emoji/emoji-data.tsv).

Usage:
  scripts/generate-emoji-data.py EMOJI_TEST ANNOTATIONS_ZH ANNOTATIONS_EN OUTPUT

Inputs (Unicode License V3, see ime/src/main/assets/emoji/NOTICE.md):
  EMOJI_TEST       https://unicode.org/Public/emoji/latest/emoji-test.txt
  ANNOTATIONS_ZH   https://raw.githubusercontent.com/unicode-org/cldr-json/49.0.0/cldr-json/cldr-annotations-full/annotations/zh/annotations.json
  ANNOTATIONS_EN   the same path with /en/ instead of /zh/

Output: one UTF-8 line per base emoji, grouped by "@Group" lines:
  emoji TAB zh keywords (|) TAB en keywords (|) TAB skin-tone variants (space separated, may be empty)
Only fully-qualified emoji are kept. A sequence with exactly one skin-tone modifier becomes a variant
of the sequence without it; sequences with two tones (couples) are left out.
Run with `python3 -I`; the inputs are downloaded data and are only read as text and JSON.
"""
import json
import re
import sys

TONES = {chr(c) for c in range(0x1F3FB, 0x1F400)}
VS16 = "️"
GROUPS = [
    "Smileys & Emotion",
    "People & Body",
    "Animals & Nature",
    "Food & Drink",
    "Travel & Places",
    "Activities",
    "Objects",
    "Symbols",
    "Flags",
]


def load_annotations(path):
    with open(path, encoding="utf-8") as handle:
        data = json.load(handle)
    return data["annotations"]["annotations"]


def lookup(table, emoji):
    for key in (emoji, emoji.replace(VS16, ""), emoji + VS16):
        if key in table:
            entry = table[key]
            words = list(entry.get("tts", [])) + list(entry.get("default", []))
            seen = []
            for word in words:
                word = word.replace("|", "/").replace("\t", " ").strip()
                if word and word not in seen:
                    seen.append(word)
            return seen
    return []


def parse_emoji_test(path):
    version = ""
    group = None
    entries = []  # (group, emoji)
    pattern = re.compile(r"^([0-9A-F ]+?)\s*;\s*fully-qualified\s*#\s*(\S+)\s")
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.rstrip("\n")
            if line.startswith("# Version:"):
                version = line.split(":", 1)[1].strip()
            elif line.startswith("# group:"):
                group = line.split(":", 1)[1].strip()
            else:
                match = pattern.match(line)
                if match and group in GROUPS:
                    codes = match.group(1).split()
                    emoji = "".join(chr(int(code, 16)) for code in codes)
                    entries.append((group, emoji))
    return version, entries


def main(argv):
    if len(argv) != 5:
        sys.exit(__doc__)
    version, entries = parse_emoji_test(argv[1])
    zh = load_annotations(argv[2])
    en = load_annotations(argv[3])
    bases = {}
    order = []
    variants = {}
    for group, emoji in entries:
        tones = [ch for ch in emoji if ch in TONES]
        if len(tones) == 0:
            bases[emoji] = group
            order.append(emoji)
        elif len(tones) == 1:
            stripped = "".join(ch for ch in emoji if ch not in TONES)
            variants.setdefault(stripped, []).append(emoji)
    with open(argv[4], "w", encoding="utf-8", newline="\n") as out:
        out.write(f"#emoji-data 1 emoji-test {version} cldr-annotations 49.0.0\n")
        for group in GROUPS:
            out.write(f"@{group}\n")
            for emoji in order:
                if bases[emoji] != group:
                    continue
                # Variants of a sequence that lost its VS16 when the tone was stripped match either form.
                found = variants.get(emoji, []) or variants.get(emoji.replace(VS16, ""), [])
                fields = [emoji, "|".join(lookup(zh, emoji)), "|".join(lookup(en, emoji)), " ".join(found)]
                out.write("\t".join(fields) + "\n")


if __name__ == "__main__":
    main(sys.argv)
