#!/usr/bin/env python3
"""Summarizes the SongyanPerf logcat that scripts/collect-ime-perf.sh leaves on disk.

Usage: scripts/ime-perf-summary.py <logcat> [--shows N] [--keys N] [--pss-log PATH] [--json PATH]

Prints count/min/p50/p90/p95/p99/max in microseconds for show_us (keyboard show), key_us (key
ACTION_DOWN to preedit applied) and commit_us (candidate tap to committed text). --shows/--keys cap
the measured window: the first N samples in log order are the measured sequence, later samples (a
trailing show from the same run) are ignored. The cap is applied before sorting; sorting first and
keeping the N fastest values would hide the slow presses that matter.

Exit status is 1 when fewer samples were collected than requested (the run is incomplete), 2 on a
usage or file error. --json PATH writes the same numbers as JSON. --pss-log PATH adds the IME
process's peak memory from the collected dumpsys meminfo TOTAL PSS samples (KiB) and exits 1 when the
log holds no positive sample, so a broken sampler can never look like 0 KiB.
"""

import argparse
import json
import math
import re
import sys

METRICS = (
    ("show_us", "onStartInputView -> first keyboard frame"),
    ("key_us", "key ACTION_DOWN -> preedit applied"),
    ("commit_us", "candidate tap -> commit applied"),
)


def collect(text, field, limit=None):
    """Values of one metric in log order, cut to the first [limit] samples."""
    values = [int(value) for value in re.findall(r"\b" + re.escape(field) + r"=(\d+)", text)]
    return values if limit is None else values[:limit]


def percentile(values, p):
    """Nearest-rank percentile of ascending [values]: the smallest value whose rank covers p."""
    return values[max(0, math.ceil(p * len(values)) - 1)]


def summarize(text, shows=None, keys=None):
    """Per-metric statistics; [shows] and [keys] are the sample counts the run asked for."""
    limits = {"show_us": shows, "key_us": keys, "commit_us": None}
    metrics = {}
    incomplete = []
    for field, _ in METRICS:
        values = collect(text, field, limits[field])
        if not values:
            metrics[field] = {"count": 0, "requested": limits[field]}
            incomplete.append(field if limits[field] is None else f"{field} (0 < {limits[field]})")
            continue
        values.sort()
        metrics[field] = {
            "count": len(values),
            "min": values[0],
            "p50": percentile(values, 0.50),
            "p90": percentile(values, 0.90),
            "p95": percentile(values, 0.95),
            "p99": percentile(values, 0.99),
            "max": values[-1],
            "requested": limits[field],
        }
        wanted = limits[field]
        if wanted is not None and len(values) < wanted:
            incomplete.append(f"{field} ({len(values)} < {wanted})")
    return {"requested": {"shows": shows, "keys": keys}, "metrics": metrics, "incomplete": incomplete}


def read_pss(path):
    """Peak and median of the TOTAL PSS samples; raises ValueError when none is positive."""
    samples = []
    try:
        with open(path, encoding="utf-8", errors="replace") as stream:
            for line in stream:
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                token = line.split()[0]
                if not token.lstrip("-").isdigit():
                    continue
                value = int(token)
                if value > 0:
                    samples.append(value)
    except OSError as error:
        raise ValueError(f"cannot read PSS log {path}: {error}") from error
    if not samples:
        raise ValueError(
            f"no positive PSS sample in {path}; dumpsys meminfo printed no TOTAL PSS, "
            "so the peak memory of the IME process was not measured"
        )
    samples.sort()
    return {"count": len(samples), "peak_kib": samples[-1], "p50_kib": percentile(samples, 0.50)}


def render(summary, pss=None):
    """The table collect-ime-perf.sh has always printed, plus p95 and the optional PSS line."""
    lines = [
        "",
        f'{"metric":<10} {"count":>5} {"min":>9} {"p50":>9} {"p90":>9} {"p95":>9} {"p99":>9} {"max":>9}',
    ]
    lines.append("-" * len(lines[-1]))
    for field, _ in METRICS:
        stats = summary["metrics"][field]
        if stats["count"] == 0:
            lines.append(f"{field:<10} {0:>5} {'-':>9} {'-':>9} {'-':>9} {'-':>9} {'-':>9} {'-':>9}")
            continue
        lines.append(
            f"{field:<10} {stats['count']:>5} {stats['min']:>9} {stats['p50']:>9} "
            f"{stats['p90']:>9} {stats['p95']:>9} {stats['p99']:>9} {stats['max']:>9}"
        )
    lines += [
        "",
        "all values in microseconds; measured on the debug build with debug=true",
        "key_us: finger press to preedit; commit_us: candidate tap to committed text",
    ]
    for field, what in METRICS:
        lines.append(f"  {field:<10} {what}")
    if pss is not None:
        lines += [
            "",
            (
                f"PSS (dumpsys meminfo TOTAL PSS, KiB): count={pss['count']} "
                f"peak={pss['peak_kib']} p50={pss['p50_kib']}"
            ),
        ]
    return "\n".join(lines) + "\n"


def usage(stream):
    print(__doc__.strip().splitlines()[2], file=stream)


def main(argv):
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("log", nargs="?")
    parser.add_argument("--shows", type=int, default=None)
    parser.add_argument("--keys", type=int, default=None)
    parser.add_argument("--pss-log", dest="pss_log", default=None)
    parser.add_argument("--json", dest="json_path", default=None)
    try:
        args = parser.parse_args(argv)
    except SystemExit:
        usage(sys.stderr)
        return 2
    if not args.log:
        usage(sys.stderr)
        return 2
    try:
        with open(args.log, encoding="utf-8", errors="replace") as stream:
            text = stream.read()
    except OSError as error:
        print(f"cannot read {args.log}: {error}", file=sys.stderr)
        return 2

    summary = summarize(text, shows=args.shows, keys=args.keys)
    pss = None
    if args.pss_log:
        try:
            pss = read_pss(args.pss_log)
        except ValueError as error:
            print(f"error: {error}", file=sys.stderr)
            return 1
    sys.stdout.write(render(summary, pss))
    if args.json_path:
        data = dict(summary)
        if pss is not None:
            data["pss"] = pss
        try:
            with open(args.json_path, "w", encoding="utf-8") as stream:
                json.dump(data, stream, indent=2, ensure_ascii=False)
                stream.write("\n")
        except OSError as error:
            print(f"cannot write {args.json_path}: {error}", file=sys.stderr)
            return 2
    if summary["incomplete"]:
        print("incomplete samples: " + ", ".join(summary["incomplete"]), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
