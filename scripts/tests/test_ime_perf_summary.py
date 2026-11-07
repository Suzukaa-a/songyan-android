"""Tests for scripts/ime-perf-summary.py, the summary CLI behind collect-ime-perf.sh.

Run with: PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/tests

The regression that matters is the sample window: the first N log samples of show_us/key_us are the
measured sequence, and only those are sorted to compute the percentiles. Sorting the whole log and
keeping the N fastest values silently reports the best N of an unbounded run.
"""

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]
SUMMARY = SCRIPTS / "ime-perf-summary.py"


def load_helper():
    """Loads the CLI as a module; the file name has a hyphen, so this is not a plain import."""
    spec = importlib.util.spec_from_file_location("ime_perf_summary", SUMMARY)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def logcat(shows=(), keys=(), commits=()):
    lines = []
    for value in shows:
        lines.append(f"I/SongyanPerf( 4242): show_us={value}")
    for value in keys:
        lines.append(f"I/SongyanPerf( 4242): key_us={value} wait_us=10 engine_us={value - 10}")
    for value in commits:
        lines.append(f"I/SongyanPerf( 4242): commit_us={value} select_us=5")
    return "\n".join(lines) + "\n"


def run_cli(*args):
    return subprocess.run(
        [sys.executable, str(SUMMARY), *map(str, args)],
        capture_output=True,
        text=True,
        check=False,
    )


class SummaryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.helper = load_helper()

    def testFirstSamplesInLogOrderAreMeasuredNotTheFastest(self):
        # The first two presses are slow, the rest of the log is fast. The measured sequence is the
        # first 5 samples; reporting only the five fastest would hide both slow presses.
        text = logcat(
            shows=(500, 400),
            keys=(900_000, 800_000, 3_000, 2_000, 1_000, 1_000, 1_000),
        )
        summary = self.helper.summarize(text, shows=1, keys=5)
        keys = summary["metrics"]["key_us"]
        self.assertEqual(5, keys["count"])
        self.assertEqual(1_000, keys["min"])
        self.assertEqual(3_000, keys["p50"])
        self.assertEqual(900_000, keys["max"])
        shows = summary["metrics"]["show_us"]
        self.assertEqual(1, shows["count"])
        self.assertEqual(500, shows["max"])

    def testPercentilesUseNearestRankAndAddP95(self):
        values = [1_000 * n for n in range(1, 21)]
        summary = self.helper.summarize(logcat(keys=values), shows=5, keys=20)
        keys = summary["metrics"]["key_us"]
        self.assertEqual(10_000, keys["p50"])
        self.assertEqual(18_000, keys["p90"])
        self.assertEqual(19_000, keys["p95"])
        self.assertEqual(20_000, keys["p99"])
        self.assertEqual(20_000, keys["max"])

    def testCommitSamplesAreNotLimited(self):
        text = logcat(commits=(7_000, 6_000, 5_000))
        summary = self.helper.summarize(text, shows=1, keys=1)
        commits = summary["metrics"]["commit_us"]
        self.assertEqual(3, commits["count"])
        self.assertEqual(7_000, commits["max"])

    def testShortSamplesAreReportedAsIncomplete(self):
        summary = self.helper.summarize(logcat(keys=(1_000, 2_000)), shows=2, keys=5)
        self.assertEqual(
            ["show_us (0 < 2)", "key_us (2 < 5)", "commit_us"],
            summary["incomplete"],
        )

    def testTableKeepsTheOriginalColumnsPlusP95(self):
        text = logcat(shows=(100,), keys=(1_000, 2_000), commits=(3_000,))
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory, "songyanperf.log")
            log.write_text(text, encoding="utf-8")
            result = run_cli(log, "--shows", 1, "--keys", 2)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("p50", result.stdout)
        self.assertIn("p95", result.stdout)
        self.assertIn("p99", result.stdout)
        self.assertIn("show_us", result.stdout)
        self.assertIn("all values in microseconds", result.stdout)

    def testShortSamplesExitNonZero(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory, "songyanperf.log")
            log.write_text(logcat(keys=(1_000,)), encoding="utf-8")
            result = run_cli(log, "--shows", 1, "--keys", 5)
        self.assertEqual(1, result.returncode)
        self.assertIn("incomplete samples", result.stderr)

    def testJsonFileCarriesPercentiles(self):
        text = logcat(shows=(100, 200), keys=(1_000, 2_000, 3_000), commits=(4_000,))
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory, "songyanperf.log")
            log.write_text(text, encoding="utf-8")
            out = Path(directory, "summary.json")
            result = run_cli(log, "--shows", 2, "--keys", 3, "--json", out)
            self.assertEqual(0, result.returncode, result.stderr)
            data = json.loads(out.read_text(encoding="utf-8"))
        self.assertEqual(3, data["requested"]["keys"])
        self.assertEqual(2, data["metrics"]["show_us"]["count"])
        self.assertEqual(2_000, data["metrics"]["key_us"]["p50"])
        self.assertEqual(3_000, data["metrics"]["key_us"]["max"])

    def testPssLogReportsPeakInKiB(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory, "songyanperf.log")
            log.write_text(logcat(shows=(100,), keys=(1_000,), commits=(3_000,)), encoding="utf-8")
            pss = Path(directory, "pss.log")
            pss.write_text(
                "# elapsed_ms pss_kib\n250000\n0\n-5\n345678\nnot-a-number\n",
                encoding="utf-8",
            )
            out = Path(directory, "summary.json")
            result = run_cli(log, "--shows", 1, "--keys", 1, "--pss-log", pss, "--json", out)
            self.assertEqual(0, result.returncode, result.stderr)
            data = json.loads(out.read_text(encoding="utf-8"))
            self.assertIn("KiB", result.stdout)
        self.assertEqual(2, data["pss"]["count"])
        self.assertEqual(345_678, data["pss"]["peak_kib"])
        self.assertEqual(250_000, data["pss"]["p50_kib"])

    def testPssLogWithoutPositiveSamplesFails(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory, "songyanperf.log")
            log.write_text(logcat(shows=(100,), keys=(1_000,), commits=(3_000,)), encoding="utf-8")
            pss = Path(directory, "pss.log")
            pss.write_text("# elapsed_ms pss_kib\n0\n0\n", encoding="utf-8")
            result = run_cli(log, "--shows", 1, "--keys", 1, "--pss-log", pss)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("PSS", result.stderr)

    def testUsageNeededWhenNoLogIsGiven(self):
        result = run_cli()
        self.assertEqual(2, result.returncode)
        self.assertIn("usage", result.stderr.lower())


if __name__ == "__main__":
    unittest.main()
