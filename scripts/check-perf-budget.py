#!/usr/bin/env python3
"""Fails when a Macrobenchmark run exceeds the keyboard's latency budgets.

Usage: scripts/check-perf-budget.py <benchmarkData.json>...

Reads the JSON that `./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest` writes under
benchmark/build/outputs/connected_android_test_additional_output/ and compares each metric's
median with the budget below. The budgets are about ten times the medians measured on an arm64
emulator, so a shared CI emulator stays under them while a regression that blocks the main thread
(disk access, a synchronous engine call) does not.
"""

import json
import sys

BUDGETS_MS = {
    "songyan.ime.showFirstMs": 150.0,
    "songyan.ime.keyAverageMs": 50.0,
    "songyan.ime.commitFirstMs": 50.0,
}


def main(paths):
    if not paths:
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        return 2
    seen = set()
    failed = False
    for path in paths:
        with open(path, encoding="utf-8") as stream:
            data = json.load(stream)
        for benchmark in data.get("benchmarks", []):
            for metric, values in benchmark.get("metrics", {}).items():
                budget = BUDGETS_MS.get(metric)
                if budget is None:
                    continue
                seen.add(metric)
                median = values["median"]
                verdict = "ok" if median <= budget else "OVER BUDGET"
                failed |= median > budget
                print(f"{benchmark['name']} {metric}: median {median:.2f} ms, budget {budget:.0f} ms, {verdict}")
    missing = sorted(set(BUDGETS_MS) - seen)
    if missing:
        print("missing metrics: " + ", ".join(missing), file=sys.stderr)
        return 1
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
