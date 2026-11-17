# Purpose: diff the canonical layout dumps written by scripts/check-layout-interop.sh (iOS: scripts/ios-interop
#          `dump`; Android: LayoutInteropCheckTest) and print every mismatch with file, leg, field and both values.
# Usage:   python3 -I scripts/check-layout-interop.py <work-dir>     (exit 1 when there is any mismatch)
import json
import os
import sys

work = sys.argv[1]


def load(path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return None


def norm(value):
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return float(value)
    if isinstance(value, dict):
        return {k: norm(v) for k, v in value.items()}
    if isinstance(value, list):
        return [norm(v) for v in value]
    return value


def short(value):
    text = json.dumps(value, ensure_ascii=False)
    return text if len(text) <= 120 else text[:117] + "..."


def diff(left, right, path, out):
    if isinstance(left, dict) and isinstance(right, dict):
        for key in sorted(set(left) | set(right)):
            if key not in left:
                out.append((f"{path}.{key}", "<absent>", short(right[key])))
            elif key not in right:
                out.append((f"{path}.{key}", short(left[key]), "<absent>"))
            else:
                diff(left[key], right[key], f"{path}.{key}", out)
    elif isinstance(left, list) and isinstance(right, list):
        if len(left) != len(right):
            out.append((f"{path}.length", len(left), len(right)))
        for i, (a, b) in enumerate(zip(left, right)):
            diff(a, b, f"{path}[{i}]", out)
    elif left != right:
        out.append((path, short(left), short(right)))


def label(value):
    """A short name for a dumped path target, e.g. the key's face, so a row diff reads without the file open."""
    return value


mismatches = []
notes = []


def compare(name, leg, left_name, left, right_name, right):
    if left is None or right is None:
        return
    if left["ok"] != right["ok"]:
        rejected, accepted = (left_name, right_name) if not left["ok"] else (right_name, left_name)
        error = (left if not left["ok"] else right)["error"]
        mismatches.append((name, leg, "decode", f"{accepted}: accepted", f"{rejected}: rejected ({error[:100]})"))
        return
    if not left["ok"]:
        return
    out = []
    diff(norm(left), norm(right), "", out)
    for path, a, b in out:
        mismatches.append((name, leg, path, f"{left_name}={a}", f"{right_name}={b}"))


inputs = sorted(f for f in os.listdir(os.path.join(work, "inputs")) if f.endswith(".json"))
for name in inputs:
    a1 = load(os.path.join(work, "a1", name + ".dump.json"))
    i1 = load(os.path.join(work, "i1", name + ".dump.json"))
    i2 = load(os.path.join(work, "i2", name + ".dump.json"))
    a2 = load(os.path.join(work, "a2", name + ".dump.json"))
    a3 = load(os.path.join(work, "a3", name + ".dump.json"))
    i3 = load(os.path.join(work, "i3", name + ".dump.json"))
    if a1 and i1 and not a1["ok"] and not i1["ok"]:
        notes.append(f"{name}: rejected by both platforms (android: {a1['error'][:80]}; ios: {i1['error'][:80]})")
    compare(name, "decode: android vs ios", "android", a1, "ios", i1)
    # Android encode -> iOS decode: iOS must read the same keyboard Android wrote.
    compare(name, "android->ios", "original", a1, "ios-of-android-encoding", i2)
    # iOS encode -> Android decode.
    compare(name, "ios->android", "original", i1, "android-of-ios-encoding", a2)
    # Android -> iOS -> iOS -> Android must equal the original.
    compare(name, "android->ios->android", "original", a1, "round-trip", a3)
    # iOS -> Android -> Android -> iOS must equal the original.
    compare(name, "ios->android->ios", "original", i1, "round-trip", i3)

# Built-ins with the same id on both platforms (compared from the files the platforms wrote themselves).
ios_ids = {f[len("ios-layout-"):-len(".json")] for f in inputs if f.startswith("ios-layout-") and not f.startswith("ios-layout-custom")}
android_ids = {f[len("android-"):-len(".json")] for f in inputs if f.startswith("android-") and not f.startswith("android-layout")}
for builtin in sorted(ios_ids | android_ids):
    if builtin not in android_ids:
        mismatches.append((builtin, "builtin", "set", "ios has it", "android has no built-in with this id"))
    elif builtin not in ios_ids:
        mismatches.append((builtin, "builtin", "set", "android has it", "ios has no built-in with this id"))
    else:
        compare(builtin, "builtin ios vs android", "ios", load(os.path.join(work, "i1", f"ios-layout-{builtin}.json.dump.json")),
                "android", load(os.path.join(work, "a1", f"android-{builtin}.json.dump.json")))

print(f"checked {len(inputs)} files")
for note in notes:
    print("note:", note)
by_file = {}
for m in mismatches:
    by_file.setdefault(m[0], []).append(m)
for name, items in by_file.items():
    print(f"\n{name}: {len(items)} mismatch(es)")
    shown = 0
    for _, leg, path, a, b in items:
        if shown == 12:
            print(f"  ... {len(items) - shown} more")
            break
        print(f"  [{leg}] {path}: {a} | {b}")
        shown += 1
print(f"\n{len(mismatches)} mismatch(es) in {len(by_file)} file(s)")
sys.exit(1 if mismatches else 0)
