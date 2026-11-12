"""Behaviour tests for scripts/check-apk-script-library.sh.

Run with: PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/tests

The script library lives next to the Rime engine in every ABI directory. An APK that drops it
installs and only fails when a script runs, so the check is exercised against a synthetic APK and
against the two ways it can go wrong: a missing entry and a stub entry.
"""

import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
CHECK = REPO / "scripts" / "check-apk-script-library.sh"
ENGINE_SIZE = 100
SCRIPT_SIZE = 600000


class ApkScriptLibraryCheckTest(unittest.TestCase):
    def build_apk(self, files):
        handle = tempfile.NamedTemporaryFile(suffix=".apk", delete=False)
        handle.close()
        with zipfile.ZipFile(handle.name, "w") as archive:
            for name, size in files.items():
                archive.writestr(name, b"\0" * size)
        return Path(handle.name)

    def run_check(self, apk):
        try:
            return subprocess.run(
                ["bash", str(CHECK), str(apk)], capture_output=True, text=True
            )
        finally:
            apk.unlink()

    def test_an_apk_with_both_libraries_passes(self):
        apk = self.build_apk(
            {
                "lib/arm64-v8a/libsongyan_rime.so": ENGINE_SIZE,
                "lib/arm64-v8a/libsongyan-script.so": SCRIPT_SIZE,
                "lib/x86_64/libsongyan_rime.so": ENGINE_SIZE,
                "lib/x86_64/libsongyan-script.so": SCRIPT_SIZE,
            }
        )
        result = self.run_check(apk)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("2 ABI(s)", result.stdout)

    def test_an_apk_without_the_script_library_fails(self):
        apk = self.build_apk(
            {
                "lib/arm64-v8a/libsongyan_rime.so": ENGINE_SIZE,
            }
        )
        result = self.run_check(apk)
        self.assertEqual(result.returncode, 1)
        self.assertIn("no lib/arm64-v8a/libsongyan-script.so", result.stderr)

    def test_a_stub_script_library_fails(self):
        apk = self.build_apk(
            {
                "lib/arm64-v8a/libsongyan_rime.so": ENGINE_SIZE,
                "lib/arm64-v8a/libsongyan-script.so": 1024,
            }
        )
        result = self.run_check(apk)
        self.assertEqual(result.returncode, 1)
        self.assertIn("only 1024 bytes", result.stderr)


if __name__ == "__main__":
    unittest.main()
