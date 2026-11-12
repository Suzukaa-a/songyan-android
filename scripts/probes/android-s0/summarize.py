#!/usr/bin/env python3
"""Validates the JSON reports the Android S0 probe wrote and prints one summary line each.

usage: summarize.py REPORTS_DIR

Shared by run.sh (emulator, every report must be complete) and manual.sh (physical device, a
report that is still partial is evidence instead of a failure). A report that is missing required
data, carries an error, or contradicts its probe's contract is invalid; a missing report is
counted by the caller, which knows which reports it asked for. Exit code 1 means at least one
report is invalid.
"""
import glob
import json
import os
import sys


# Evidence that was observed but proves nothing (for example a private file that was not readable
# because the path did not exist); printed next to its report instead of being claimed as proof.
NOTES = []


def is_number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def check(problems, data, keys):
    for key in keys:
        if key not in data:
            problems.append("missing " + key)


def check_isolated(data):
    problems = []
    check(problems, data, ["device", "hostPid", "hostUid", "canaryCreated", "canaryLength",
                           "cold", "secondCold", "warm", "capabilities",
                           "coldPid", "secondColdPid", "pidChanged", "secondColdAgeMs"])
    check(problems, data.get("capabilities") or {},
          ["appPrivateReadVerdict", "appPrivateWriteVerdict", "socketConnectVerdict"])
    # The host is authoritative for the canary. An isolated process can be denied a stat on the
    # parent directory, so canaryExists=false must not be read as "the host never wrote it";
    # only the absence of content is evidence of the permission boundary.
    if data.get("canaryCreated") is not True:
        problems.append("host did not create the private canary file")
    if not is_number(data.get("canaryLength")) or data.get("canaryLength", -1) <= 0:
        problems.append("host canary file is empty")
    cold = data.get("cold") or {}
    second = data.get("secondCold") or {}
    warm = data.get("warm") or {}
    caps = data.get("capabilities") or {}
    if not is_number(cold.get("bindToFirstReplyMs")) or cold.get("bindToFirstReplyMs", -1) <= 0:
        problems.append("cold bindToFirstReplyMs")
    if not is_number(second.get("bindToFirstReplyMs")) or second.get("bindToFirstReplyMs", -1) <= 0:
        problems.append("secondCold bindToFirstReplyMs")
    if not is_number(warm.get("p50Us")) or warm.get("p50Us", -1) <= 0:
        problems.append("warm p50Us")
    if warm.get("replies") != warm.get("calls"):
        problems.append("warm replies")
    # A failure that does not say "permission denied" (a missing path, an unreachable network)
    # proves nothing about the boundary, so it is reported as unknown and never counted as proof.
    if caps.get("appPrivateContent") is not None:
        problems.append("isolated process returned the host's private file content")
    read_verdict = caps.get("appPrivateReadVerdict")
    if read_verdict == "readable":
        problems.append("isolated process could read the host's private file")
    elif read_verdict != "denied":
        NOTES.append("private file access is unknown, not a proven denial: %s"
                     % (caps.get("appPrivateReadError") or "no error reported"))
    if caps.get("appPrivateWriteVerdict") == "writable":
        problems.append("isolated process could write into the host's private directory")
    elif caps.get("appPrivateWriteVerdict") != "denied":
        NOTES.append("private file write access is unknown, so it is not proof: %s"
                     % (caps.get("appPrivateWrite") or "no result reported"))
    if caps.get("checkInternet") != "denied":
        problems.append("isolated INTERNET permission not denied")
    if caps.get("checkRecordAudio") != "denied":
        problems.append("isolated RECORD_AUDIO permission not denied")
    if caps.get("socketConnectVerdict") == "connected":
        problems.append("isolated process connected a socket")
    elif caps.get("socketConnectVerdict") != "denied":
        NOTES.append("socket connect is unknown or was not refused, so not proof: %s"
                     % (caps.get("socketConnect") or "no result"))
    if not NOTES:
        NOTES.append("boundary proven by the permission checks, the uid and the denial verdicts")
    if is_number(cold.get("uid")) and is_number(data.get("hostUid")) and cold.get("uid") == data.get("hostUid"):
        problems.append("isolated service runs with the host uid")
    if data.get("pidChanged") is not True:
        problems.append("second cold start reused pid %s" % data.get("coldPid"))
    age = data.get("secondColdAgeMs")
    if not is_number(age) or age <= 0 or age > 2500:
        problems.append("second cold start process age %s ms is not a fresh start" % age)
    if not is_number(data.get("coldPid")) or not is_number(data.get("secondColdPid")):
        problems.append("cold pids missing")
    summary = "cold=%.1fms pid=%s secondCold=%.1fms pid=%s age=%sms pidChanged=%s warm p50=%.0fus p95=%.0fus max=%.0fus isolatedUid=%s hostUid=%s canaryHost=%s read=%s write=%s net=%s" % (
        cold.get("bindToFirstReplyMs", -1), data.get("coldPid"),
        second.get("bindToFirstReplyMs", -1), data.get("secondColdPid"),
        data.get("secondColdAgeMs"), data.get("pidChanged"),
        warm.get("p50Us", -1), warm.get("p95Us", -1), warm.get("maxUs", -1),
        cold.get("uid"), data.get("hostUid"), data.get("canaryCreated"),
        caps.get("appPrivateReadVerdict"), caps.get("appPrivateWriteVerdict"),
        caps.get("socketConnectVerdict"))
    return problems, summary


def check_context(data):
    problems = []
    check(problems, data, ["device", "editor", "mode", "inputType", "initial", "availableLength"])
    mode = data.get("mode")
    if mode not in ("read-only", "write"):
        problems.append("mode")
    if mode == "write":
        check(problems, data, ["sentLength", "wrote", "commitErrors", "completeMatch", "measuredLength",
                               "truncated", "deleted", "cleanup", "lengthAfterCleanup", "after"])
        if data.get("wrote") is not True:
            problems.append("write mode reported wrote=%s" % data.get("wrote"))
        if data.get("commitErrors") != 0:
            problems.append("commitErrors=%s" % data.get("commitErrors"))
        measured = data.get("measuredLength")
        sent = data.get("sentLength")
        if data.get("truncated") is True:
            problems.append("marker truncated: measured %s of %s characters" % (measured, sent))
        if data.get("completeMatch") is not True:
            problems.append("marker was not proven complete; nothing may be deleted (measured %s of %s)" % (measured, sent))
            if data.get("deleted") is not False or data.get("cleanup") == "deleted":
                problems.append("text was deleted even though the marker was incomplete")
        else:
            if data.get("deleted") is not True or data.get("cleanup") != "deleted":
                problems.append("own editor was not cleaned up")
            if data.get("lengthAfterCleanup") != 0:
                problems.append("own editor is not empty after cleanup")
            if data.get("availableLength") != sent:
                problems.append("availableLength differs from sentLength")
        for entry in data.get("after") or []:
            if not is_number(entry.get("length")) or entry.get("length", -1) < 0:
                problems.append("after entry n=%s has no length" % entry.get("n"))
    else:
        if data.get("wrote") is not False or data.get("deleted") is not False:
            problems.append("read-only measurement wrote or deleted text")
        if data.get("cleanup") != "not-attempted":
            problems.append("read-only measurement attempted cleanup")
        if not is_number(data.get("availableLength")):
            problems.append("read-only availableLength")
    after = dict((entry.get("n"), entry.get("length")) for entry in data.get("after") or [])
    summary = "editor=%s mode=%s available=%s measured=%s truncated=%s after=%s cleanup=%s" % (
        data.get("editor"), mode, data.get("availableLength"), data.get("measuredLength"),
        data.get("truncated"), after, data.get("cleanup"))
    return problems, summary


def check_record(data, partial):
    problems = []
    check(problems, data, ["device", "where", "recording"])
    where = data.get("where")
    if where not in ("ime", "activity", "foreground-service"):
        problems.append("where=%s" % where)
    recording = data.get("recording") or {}
    check(problems, recording, ["permission", "perSecond", "totalFrames", "readErrors",
                               "screenInteractiveAtStart", "keyguardLockedAtStart", "deviceSecure"])
    if recording.get("permission") != "granted":
        problems.append("RECORD_AUDIO was not granted")
    if recording.get("error"):
        problems.append("recording error: %s" % recording.get("error"))
    # A partial report is what a run interrupted by the screen turning off leaves behind; the
    # seconds it did capture still say when the stream stopped.
    if partial and recording.get("complete") is True:
        problems.append("partial report claims to be complete")
    if not partial and recording.get("complete") is not True:
        problems.append("the report is not marked complete")
    seconds = recording.get("perSecond") or []
    if len(seconds) == 0:
        problems.append("no per-second samples")
    for entry in seconds:
        if not is_number(entry.get("frames")) or entry.get("frames", 0) < 0:
            problems.append("sample second %s has no frame count" % entry.get("second"))
        if entry.get("recordingState") != 3:
            problems.append("sample second %s is not recording" % entry.get("second"))
        # Screen and keyguard per sample are what separates "the device locked" from "the app
        # stopped": a physical-device lock test cannot be read without them.
        if not isinstance(entry.get("atMs"), (int, float)) or isinstance(entry.get("atMs"), bool):
            problems.append("sample second %s has no timestamp" % entry.get("second"))
        if not isinstance(entry.get("screenInteractive"), bool):
            problems.append("sample second %s has no screen state" % entry.get("second"))
        if not isinstance(entry.get("keyguardLocked"), bool):
            problems.append("sample second %s has no keyguard state" % entry.get("second"))
    if not is_number(recording.get("totalFrames")) or recording.get("totalFrames", 0) <= 0:
        problems.append("no frames were ever read")
    summary = "where=%s foreground=%s complete=%s permission=%s perSecond=%s silenced=%s" % (
        data.get("where"), data.get("foreground"), recording.get("complete"),
        recording.get("permission"),
        [(entry.get("second"), entry.get("frames"), entry.get("peak"),
          entry.get("screenInteractive"), entry.get("keyguardLocked")) for entry in seconds],
        [entry.get("silenced") for entry in seconds])
    summary += "\n" + lock_evidence(seconds, recording.get("deviceSecure"))
    return problems, summary


def lock_evidence(seconds, device_secure):
    """Says whether the run actually reached the lock screen, so an untested lock stays untested."""
    off = [entry for entry in seconds if entry.get("screenInteractive") is False]
    locked = [entry for entry in seconds if entry.get("keyguardLocked") is True]
    if not off:
        if not locked:
            return "screen off was never sampled: the lock-screen part of this run is unverified"
        return "keyguard was locked while the screen stayed interactive: not a lock-screen run"
    first_index = seconds.index(off[0])
    first = off[0].get("second")
    frames_on = sum(entry.get("frames", 0) for entry in seconds[:first_index])
    frames_off = sum(entry.get("frames", 0) for entry in seconds[first_index:])
    detail = ("screen off from second %s (%s of %s samples); frames before screen off=%s after=%s"
              % (first, len(off), len(seconds), frames_on, frames_off))
    if locked:
        lock_first = locked[0].get("second")
        if device_secure is True:
            detail += ("; secure lock screen from second %s" % lock_first)
        elif device_secure is False:
            detail += ("; keyguard locked from second %s but the device has no credential"
                       % lock_first)
        else:
            detail += "; keyguard locked from second %s (credential state unknown)" % lock_first
    else:
        detail += "; the keyguard never showed locked: screen off, device not locked"
    return detail


def main():
    if len(sys.argv) != 2:
        print("usage: summarize.py REPORTS_DIR", file=sys.stderr)
        return 2
    reports = sys.argv[1]
    lines = []
    invalid = []
    partials = []
    for path in sorted(glob.glob(os.path.join(reports, "*.json"))):
        name = os.path.basename(path)[:-5]
        partial = name.endswith(".partial")
        last_second = None
        if partial:
            try:
                seconds = (data.get("recording") or {}).get("perSecond") or []
                if seconds:
                    last_second = seconds[-1].get("second")
            except Exception:
                last_second = None
            partials.append((name[:-len(".partial")], last_second))
        try:
            data = json.load(open(path))
        except Exception as error:
            invalid.append("%s: unreadable: %s" % (name, error))
            continue
        problems = []
        NOTES.clear()
        if data.get("error"):
            problems.append("error: %s" % data.get("error"))
        scenario = data.get("scenario")
        if scenario == "isolated":
            extra, summary = check_isolated(data)
        elif scenario == "context":
            extra, summary = check_context(data)
        elif scenario == "record":
            extra, summary = check_record(data, partial)
        else:
            extra, summary = ["unknown scenario"], json.dumps(data)[:200]
        problems.extend(extra)
        if problems:
            invalid.append("%s: %s" % (name, "; ".join(problems)))
            lines.append("%-28s INVALID %s" % (name, "; ".join(problems)))
        else:
            head, _, detail = ("%-28s %s%s" % (name, "PARTIAL " if partial else "", summary)).partition("\n")
            lines.append(head)
            for extra in detail.split("\n"):
                if extra:
                    lines.append("  " + extra)
            for extra in NOTES:
                lines.append("  note: " + extra)
    lines.append("raw reports: %s" % reports)
    for name, last_second in sorted(set(partials)):
        # Only what the samples show: the last complete second, not when or why it stopped.
        lines.append("partial only: %s (incomplete report; the last sample is second %s)"
                     % (name, last_second if last_second is not None else "?"))
    if invalid:
        lines.append("INVALID reports: %d" % len(invalid))
        for item in invalid:
            lines.append("  " + item)
        print("\n".join(lines))
        return 1
    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    sys.exit(main())