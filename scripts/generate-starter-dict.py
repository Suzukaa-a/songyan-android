#!/usr/bin/env python3
"""Builds the Android starter dictionary (app/src/main/assets/rime/starter/songyan_starter.dict.yaml).

Usage:
  scripts/generate-starter-dict.py CEDICT_GZ ESSAY UNIHAN_READINGS OPENCC_DICTIONARY_DIR OUTPUT

Inputs (licences and upstream URLs in app/src/main/assets/rime/starter/NOTICE.md):
  CEDICT_GZ              https://www.mdbg.net/chinese/export/cedict/cedict_1_0_ts_utf-8_mdbg.txt.gz
  ESSAY                  https://github.com/rime/rime-essay (essay.txt)
  UNIHAN_READINGS        Unihan_Readings.txt from https://www.unicode.org/Public/UCD/latest/ucd/Unihan.zip
  OPENCC_DICTIONARY_DIR  OpenCC's data/dictionary (TSCharacters.txt, TSPhrases.txt), the version the
                         engine build already fetches

Output: one "word TAB toneless pinyin TAB weight" line per word reading, simplified characters,
heaviest first. Frequencies come from rime-essay (traditional forms, converted with OpenCC and
summed per simplified word); readings come from CC-CEDICT. A word with several readings splits
its frequency by how often each character takes each reading (Unihan kHanyuPinlu), so 了 liao
and 都 du no longer tie with 了 le and 都 dou. A frequent word CC-CEDICT lacks is kept only when
every character has one dominant reading.
"""

import gzip
import re
import sys
import unicodedata
from collections import defaultdict

# Words of two or more characters at least this frequent in rime-essay are kept.
MIN_WORD_WEIGHT = 1000
# A character's reading counts as dominant above this share of its uses.
DOMINANT_SHARE = 0.9
# The share left for a reading the usage counts never saw.
UNSEEN_SHARE = 0.005
# Unihan readings the schema cannot spell (interjections written as bare consonants).
UNSPELLABLE = {"m", "n", "ng", "hm", "hng", "ê"}
# Standard pinyin structure, the same rule the app uses to decide whether a dictionary is plain
# pinyin (fuzzy pinyin and the folded layouts depend on it): one stray reading such as erhua "r"
# or the two-syllable 瓲 "tunwa" turns those features off for the whole input method.
INITIALS = "zh ch sh b p m f d t n l g k h j q x r z c s".split()
FINALS = set(
    "a o e ai ei ao ou an en ang eng ong er i ia ie iao iu ian in iang ing iong "
    "u ua uo uai ui uan un uang ueng v ve van vn ue".split()
)
JQX_FINALS = set("i ia ie iao iu ian in iang ing iong u ue uan un v ve van vn".split())
ZERO_INITIAL = set(
    "a o e ai ei ao ou an en ang eng er yi ya ye yo yao you yan yin yang ying yong "
    "wu wa wo wai wei wan wen wang weng yu yue yuan yun".split()
)


def is_pinyin(syllable):
    if syllable in ZERO_INITIAL:
        return True
    for initial in INITIALS:
        final = syllable[len(initial) :]
        if syllable.startswith(initial) and final:
            return final in (JQX_FINALS if initial in "jqx" else FINALS)
    return False


def toneless(marked):
    text = unicodedata.normalize("NFD", marked.lower()).replace("ü", "v")
    return "".join(ch for ch in text if not unicodedata.combining(ch))


def load_unihan(path):
    usage = defaultdict(dict)
    mandarin = {}
    for line in open(path, encoding="utf-8"):
        if line.startswith("#") or "\t" not in line:
            continue
        code, field, value = line.rstrip("\n").split("\t", 2)
        char = chr(int(code[2:], 16))
        if field == "kHanyuPinlu":
            for item in value.split():
                reading, count = re.fullmatch(r"(.+)\((\d+)\)", item).groups()
                syllable = toneless(reading)
                usage[char][syllable] = usage[char].get(syllable, 0) + int(count)
        elif field == "kMandarin":
            mandarin[char] = [toneless(reading) for reading in value.split()]
    syllables = {s for readings in mandarin.values() for s in readings}
    syllables |= {s for counts in usage.values() for s in counts}
    syllables = {s for s in syllables if is_pinyin(s)} - UNSPELLABLE
    return usage, mandarin, syllables


def cedict_reading(pinyin, syllables):
    out = []
    for item in pinyin.split():
        item = item.lower().replace("u:", "v")
        item = re.sub(r"[1-5]$", "", item)
        if item == "r":
            item = "er"
        if item not in syllables:
            return None
        out.append(item)
    return " ".join(out)


def load_cedict(path, syllables):
    readings = defaultdict(list)
    simplified_of = {}
    pattern = re.compile(r"^(\S+) (\S+) \[([^\]]*)\] /")
    with gzip.open(path, "rt", encoding="utf-8") as source:
        for line in source:
            match = pattern.match(line)
            if not match:
                continue
            traditional, simplified, pinyin = match.groups()
            reading = cedict_reading(pinyin, syllables)
            if reading is None or len(reading.split()) != len(simplified):
                continue
            if reading not in readings[simplified]:
                readings[simplified].append(reading)
            simplified_of.setdefault(traditional, simplified)
    return readings, simplified_of


def load_opencc(directory):
    table = {}
    for name in ("TSCharacters.txt", "TSPhrases.txt"):
        for line in open(f"{directory}/{name}", encoding="utf-8"):
            if "\t" in line:
                source, targets = line.rstrip("\n").split("\t", 1)
                table[source] = targets.split()[0]
    return table, max(len(key) for key in table)


def to_simplified(word, simplified_of, opencc):
    if word in simplified_of:
        return simplified_of[word]
    table, longest = opencc
    out, i = [], 0
    while i < len(word):
        for size in range(min(longest, len(word) - i), 0, -1):
            piece = word[i : i + size]
            if piece in table:
                out.append(table[piece])
                i += size
                break
        else:
            out.append(word[i])
            i += 1
    return "".join(out)


def is_han(char):
    return "㐀" <= char <= "鿿" or "\U00020000" <= char <= "\U0003134f"


def main(cedict_path, essay_path, unihan_path, opencc_dir, output_path):
    usage, mandarin, syllables = load_unihan(unihan_path)
    readings, simplified_of = load_cedict(cedict_path, syllables)
    opencc = load_opencc(opencc_dir)

    weights = defaultdict(int)
    for line in open(essay_path, encoding="utf-8"):
        parts = line.rstrip("\n").split("\t")
        if len(parts) < 2 or not parts[1].isdigit():
            continue
        word = to_simplified(parts[0], simplified_of, opencc)
        if all(is_han(char) for char in word):
            weights[word] += int(parts[1])

    def char_readings(char):
        known = list(readings.get(char, []))
        for syllable in mandarin.get(char, []) + list(usage.get(char, {})):
            if syllable in syllables and syllable not in known:
                known.append(syllable)
        return known

    def shares(char):
        """How often [char] takes each of its readings, summing to one."""
        known = char_readings(char)
        counts = usage.get(char)
        if counts:
            total = sum(counts.values())
            raw = {s: counts.get(s, 0) / total or UNSEEN_SHARE for s in known}
        else:
            primary = mandarin.get(char, known[:1])[:1]
            raw = {s: 1.0 if s in primary else UNSEEN_SHARE * 10 for s in known}
        scale = sum(raw.values())
        return {s: value / scale for s, value in raw.items()}

    rows = {}

    def add(word, reading, weight):
        rows[(word, reading)] = max(1, round(weight))

    for char in {char for word in weights for char in word} | set(usage):
        if not is_han(char) or not char_readings(char):
            continue
        for syllable, share in shares(char).items():
            add(char, syllable, weights.get(char, 0) * share)

    for word, weight in weights.items():
        if len(word) < 2 or weight < MIN_WORD_WEIGHT:
            continue
        options = readings.get(word)
        if not options:
            picked = []
            for char in word:
                ranked = sorted(shares(char).items(), key=lambda item: -item[1]) if char_readings(char) else []
                if not ranked or ranked[0][1] < DOMINANT_SHARE:
                    break
                picked.append(ranked[0][0])
            else:
                add(word, " ".join(picked), weight)
            continue
        scores = {}
        for reading in options:
            score = 1.0
            for char, syllable in zip(word, reading.split()):
                score *= shares(char).get(syllable, UNSEEN_SHARE) if char_readings(char) else 1.0
            scores[reading] = score
        total = sum(scores.values())
        for reading, score in scores.items():
            add(word, reading, weight * score / total)

    ordered = sorted(rows.items(), key=lambda item: (-item[1], item[0][0], item[0][1]))
    with open(output_path, "w", encoding="utf-8") as out:
        out.write("# Generated by scripts/generate-starter-dict.py; do not edit.\n")
        for (word, reading), weight in ordered:
            out.write(f"{word}\t{reading}\t{weight}\n")


if __name__ == "__main__":
    if len(sys.argv) != 6:
        sys.exit(__doc__)
    main(*sys.argv[1:])
