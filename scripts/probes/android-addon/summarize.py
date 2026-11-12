#!/usr/bin/env python3
"""Validates and summarizes one external-addon probe run.

Usage:
    scripts/probes/android-addon/summarize.py <run-directory> [--json]

The run directory is what run.sh creates under .local/addon-probe/: host/report-host-<case>.json,
host/editor.json and addon/report-addon-<case>.json. A missing or unparseable report fails the
summary: missing data is never treated as success. The platform expectations (a1 records, a2 and a3
do not) are printed as MATCH / MISMATCH against the decision the probe exists to verify; a mismatch
is a result for the plan, not a failed run.
"""

import json
import os
import sys

AUDIO_CASES = [
    ("a1", "BIND_INCLUDE_CAPABILITIES + RECORD_AUDIO", True),
    ("a2", "no capability flag + RECORD_AUDIO", False),
    ("a3", "BIND_INCLUDE_CAPABILITIES + permission revoked", False),
]

REQUIRED_HOST_KEYS = ["probe", "case", "sdkInt", "bindAccepted"]
REQUIRED_ADDON_KEYS = ["probe", "case", "packageName", "uid"]


def load(path):
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def main(argv):
    if len(argv) < 2:
        print("usage: summarize.py <run-directory> [--json]", file=sys.stderr)
        return 2
    run_dir = argv[1]
    as_json = "--json" in argv[2:]
    problems = []
    lines = []
    summary = {}

    def report(case, side):
        path = os.path.join(run_dir, side, f"report-{side}-{case}.json")
        if not os.path.isfile(path):
            problems.append(f"{side} report for {case} is missing: {path}")
            return None
        try:
            data = load(path)
        except (OSError, ValueError) as failure:
            problems.append(f"{side} report for {case} is unreadable: {failure}")
            return None
        required = REQUIRED_HOST_KEYS if side == "host" else REQUIRED_ADDON_KEYS
        for key in required:
            if key not in data:
                problems.append(f"{side} report for {case} lacks {key}")
                return None
        return data

    for case, description, expected_recording in AUDIO_CASES:
        host = report(case, "host")
        addon = report(case, "addon")
        entry = {"description": description, "host": host, "addon": addon}
        if host is not None and addon is not None:
            recorded = bool(addon.get("started"))
            entry["expectedRecording"] = expected_recording
            entry["recorded"] = recorded
            entry["matchesExpectation"] = recorded == expected_recording
            entry["permissionGranted"] = addon.get("permissionGranted")
            entry["audioRecordState"] = addon.get("audioRecordState")
            entry["errorClass"] = addon.get("errorClass")
            entry["samplesRead"] = addon.get("samplesRead")
            entry["includeCapabilities"] = host.get("includeCapabilities")
            entry["bindingFlags"] = host.get("bindingFlags")
            verdict = "MATCH" if entry["matchesExpectation"] else "MISMATCH"
            lines.append(
                f"{case}: {description}: includeCapabilities={entry['includeCapabilities']} "
                f"permission={entry['permissionGranted']} started={recorded} "
                f"samples={entry['samplesRead']} error={entry['errorClass']} -> {verdict}"
            )
        summary[case] = entry

    host_b = report("b", "host")
    addon_b = report("b", "addon")
    editor_path = os.path.join(run_dir, "host", "editor.json")
    editor = None
    if os.path.isfile(editor_path):
        try:
            editor = load(editor_path)
        except (OSError, ValueError) as failure:
            problems.append(f"editor report is unreadable: {failure}")
    else:
        problems.append(f"editor report is missing: {editor_path}")
    panel = {"host": host_b, "addon": addon_b, "editor": editor}
    if host_b is not None and addon_b is not None:
        panel.update(
            {
                "surfacePresent": bool(host_b.get("panelReady")),
                "requestedSize": [host_b.get("panelRequestedWidthPx"), host_b.get("panelRequestedHeightPx")],
                "addonViewSize": [addon_b.get("viewWidthPx"), addon_b.get("viewHeightPx")],
                "touchCount": addon_b.get("touchCount", 0),
                "insetsAvailable": bool((host_b.get("windowInsets") or {}).get("available")),
                "deathObserved": bool(host_b.get("addonDeathObserved")),
                "surfaceClearedAfterDeath": bool(host_b.get("surfaceClearedAfterDeath")),
                "keyboardStillShown": bool(host_b.get("keyboardStillShown")),
                "editorTextHasK": bool(editor and "K" in (editor.get("text") or "")),
            }
        )
        for key in ("surfacePresent", "deathObserved", "surfaceClearedAfterDeath", "editorTextHasK"):
            if not panel[key]:
                problems.append(f"panel case b: {key} is false")
        if panel["touchCount"] < 1:
            problems.append("panel case b: the addon panel received no touches")
        lines.append(
            "b: panel surface={} requested={} addonView={} insets={} touches={} "
            "deathObserved={} surfaceCleared={} keyboardShown={} editorHasK={}".format(
                panel["surfacePresent"],
                panel["requestedSize"],
                panel["addonViewSize"],
                panel["insetsAvailable"],
                panel["touchCount"],
                panel["deathObserved"],
                panel["surfaceClearedAfterDeath"],
                panel["keyboardStillShown"],
                panel["editorTextHasK"],
            )
        )
    summary["b"] = panel

    summary["problems"] = problems
    output = os.path.join(run_dir, "summary.json")
    try:
        with open(output, "w", encoding="utf-8") as handle:
            json.dump(summary, handle, ensure_ascii=False, indent=2)
    except OSError as failure:
        problems.append(f"cannot write {output}: {failure}")

    for line in lines:
        print(line)
    print(f"summary written to {output}")
    if problems:
        for problem in problems:
            print(f"PROBLEM: {problem}", file=sys.stderr)
        return 1
    if as_json:
        print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
