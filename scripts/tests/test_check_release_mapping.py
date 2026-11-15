"""Behaviour tests for scripts/check-release-mapping.sh.

Run with: PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/tests

The release build is shrunk by R8 and the script JNI layer finds its host class and members by
name, so the check must fail whenever the mapping no longer names them or changes a signature.
Each case writes one mapping file and runs the real script; only the exit status and message are
inspected.
"""

import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
CHECK = REPO / "scripts" / "check-release-mapping.sh"

VALID = """# compiler: R8
# {"id":"com.android.tools.r8.synthesized"}
app.songyan.ime.script.ScriptRunHost -> app.songyan.ime.script.ScriptRunHost:
# {"id":"sourceFile","fileName":"ScriptRunHost.kt"}
    java.util.List declaredHosts -> a
    1:2:app.songyan.ime.script.ScriptRunHost use():0 -> use
    89:89:void fetch(java.lang.String,java.lang.String,java.lang.String,byte[],long):39:39 -> fetch
    4:9:boolean authorize(java.lang.String,int):28:28 -> authorize
    4:16:void log(byte[]):53:53 -> log
app.songyan.ime.script.ScriptNative -> app.songyan.ime.script.ScriptNative:
app.songyan.ime.script.ScriptProxyService -> app.songyan.ime.script.ScriptProxyService:
app.songyan.ime.script.ScriptIsolatedService -> app.songyan.ime.script.ScriptIsolatedService:
app.songyan.ime.script.ScriptStore -> ew3:
"""


class ReleaseMappingCheckTest(unittest.TestCase):
    def run_check(self, mapping):
        with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as handle:
            handle.write(mapping)
            path = Path(handle.name)
        try:
            return subprocess.run(
                ["bash", str(CHECK), str(path)], capture_output=True, text=True
            )
        finally:
            path.unlink()

    def test_a_kept_mapping_passes(self):
        result = self.run_check(VALID)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("keeps the script JNI entry points", result.stdout)

    def test_crlf_transport_still_passes(self):
        result = self.run_check(VALID.replace("\n", "\r\n"))
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_a_renamed_host_class_fails(self):
        result = self.run_check(
            VALID.replace(
                "ScriptRunHost -> app.songyan.ime.script.ScriptRunHost",
                "ScriptRunHost -> rv3",
            )
        )
        self.assertEqual(result.returncode, 1)
        self.assertIn("renamed", result.stderr)

    def test_a_renamed_member_fails(self):
        result = self.run_check(VALID.replace(":0 -> use", ":0 -> useX"))
        self.assertEqual(result.returncode, 1)
        self.assertIn("renamed", result.stderr)

    def test_a_changed_member_signature_fails(self):
        result = self.run_check(
            VALID.replace(
                "boolean authorize(java.lang.String,int)",
                "boolean authorize(java.lang.String,long)",
            )
        )
        self.assertEqual(result.returncode, 1)
        self.assertIn("missing from the mapping", result.stderr)

    def test_a_renamed_script_service_fails(self):
        result = self.run_check(
            VALID.replace(
                "ScriptIsolatedService -> app.songyan.ime.script.ScriptIsolatedService",
                "ScriptIsolatedService -> xy3",
            )
        )
        self.assertEqual(result.returncode, 1)
        self.assertIn("renamed", result.stderr)

    def test_a_missing_mapping_fails(self):
        result = subprocess.run(
            ["bash", str(CHECK), "/nonexistent/mapping.txt"],
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 1)
        self.assertIn("missing R8 mapping", result.stderr)


if __name__ == "__main__":
    unittest.main()
