"""Fail when a connected-test run reported no tests (or any failure).

Gradle's `connectedDebugAndroidTest` can finish BUILD SUCCESSFUL without
running anything when the device is not visible to its own ADB client. The CI
runs therefore assert the results XML afterwards: the run must contain at least
the expected number of tests and no failures.
"""

import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def main() -> int:
    arguments = sys.argv[1:]
    strict_skips = bool(arguments) and arguments[0] == "--no-skips"
    if strict_skips:
        arguments = arguments[1:]
    if len(arguments) < 2:
        print("usage: check-instrumented-results.py [--no-skips] <minimum-tests> <result-dir> [...]", file=sys.stderr)
        return 64
    minimum = int(arguments[0])
    total = 0
    failures = 0
    skipped = 0
    files = 0
    for root in arguments[1:]:
        for path in sorted(Path(root).rglob("*.xml")):
            try:
                tree = ET.parse(path)
            except ET.ParseError:
                continue
            for suite in tree.iter("testsuite"):
                files += 1
                total += int(suite.get("tests", "0"))
                failures += int(suite.get("failures", "0")) + int(suite.get("errors", "0"))
                skipped += int(suite.get("skipped", "0"))
    if files == 0:
        print(f"no connected-test results under: {' '.join(arguments[1:])}", file=sys.stderr)
        return 1
    if failures:
        print(f"connected tests reported {failures} failure(s)", file=sys.stderr)
        return 1
    if total < minimum:
        print(f"connected tests ran {total}, expected at least {minimum}", file=sys.stderr)
        return 1
    if strict_skips and skipped:
        print(f"connected tests skipped {skipped}; this suite must run them all", file=sys.stderr)
        return 1
    print(f"connected tests: {total} passed, {skipped} skipped (minimum {minimum})")
    return 0


if __name__ == "__main__":
    sys.exit(main())