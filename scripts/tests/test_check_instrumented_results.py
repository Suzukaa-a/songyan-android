"""Behaviour tests for scripts/check-instrumented-results.py.

Run with: PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/tests
"""

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
CHECK = REPO / "scripts" / "check-instrumented-results.py"


def suite_xml(tests: int, failures: int = 0, skipped: int = 0) -> str:
    return (
        '<?xml version="1.0" encoding="UTF-8"?>'
        f'<testsuites><testsuite name="suite" tests="{tests}" failures="{failures}" errors="0" skipped="{skipped}"/></testsuites>'
    )


class InstrumentedResultsCheckTest(unittest.TestCase):
    def run_check(self, minimum, content, *flags):
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / "result.xml").write_text(content, encoding="utf-8")
            return subprocess.run(
                [sys.executable, str(CHECK), *flags, str(minimum), directory],
                capture_output=True,
                text=True,
            )

    def test_enough_passing_tests_pass(self):
        result = self.run_check(2, suite_xml(3))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("3 passed", result.stdout)

    def test_zero_tests_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run(
                [sys.executable, str(CHECK), "1", directory],
                capture_output=True,
                text=True,
            )
        self.assertEqual(result.returncode, 1)
        self.assertIn("no connected-test results", result.stderr)

    def test_too_few_tests_fail(self):
        result = self.run_check(5, suite_xml(3))
        self.assertEqual(result.returncode, 1)
        self.assertIn("expected at least 5", result.stderr)

    def test_failures_fail(self):
        result = self.run_check(1, suite_xml(3, failures=1))
        self.assertEqual(result.returncode, 1)
        self.assertIn("1 failure", result.stderr)

    def test_an_unmet_assumption_is_a_skip(self):
        content = (
            '<?xml version="1.0" encoding="UTF-8"?>'
            '<testsuites><testsuite name="suite" tests="3" failures="1" errors="0" skipped="0">'
            '<testcase name="ok"/><testcase name="ok2"/>'
            '<testcase name="needsFixture"><failure>org.junit.AssumptionViolatedException: '
            "fixture not provisioned</failure></testcase>"
            "</testsuite></testsuites>"
        )
        result = self.run_check(1, content)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("2 passed, 1 skipped", result.stdout)
        self.assertEqual(self.run_check(1, content, "--no-skips").returncode, 1)

    def test_skipped_tests_pass_unless_asked(self):
        result = self.run_check(1, suite_xml(3, skipped=2))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("2 skipped", result.stdout)

    def test_skipped_tests_fail_with_no_skips(self):
        result = self.run_check(1, suite_xml(3, skipped=1), "--no-skips")
        self.assertEqual(result.returncode, 1)
        self.assertIn("must run them all", result.stderr)


if __name__ == "__main__":
    unittest.main()