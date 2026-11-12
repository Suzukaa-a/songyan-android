"""Behaviour tests for scripts/check-ios-sync.sh.

Run with: PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/tests

Every case runs the real script against a real fixture git repository and only looks at what a
user sees: the exit status and the drift report. The cases cover an in-sync file and tree, a
modified file inside a tree, extra and missing local files, a path absent from the recorded
commit, a newer commit, the ignored compiler/Finder noise, spaced and non-ASCII names (git
quotes non-ASCII paths by default, which must not turn into drift), and symlinks.

The fixtures are created under .scratch/ in a unique directory for this run and moved into the
system trash when the class is done. Only the system's own trash is used (the macOS Trash
directory or a trash command such as gio); nothing is ever deleted recursively, and if no trash
is available the fixtures stay where they are and the run reports that as an error.
"""

import os
import shutil
import subprocess
import time
import unittest
import uuid
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPTS = REPO / "scripts"
CHECK = SCRIPTS / "check-ios-sync.sh"
SCRATCH = REPO / ".scratch"

IN_SYNC_MANIFEST = """# fixture manifest
Docs/readme.txt docs/readme.txt
Assets/tree/ assets/tree/
"""

SPACED_NAME = "Assets/tree/name with space.txt"
CHINESE_NAME = "Assets/tree/中文 文件.txt"

# Regular files stored on the iOS side; the local copy is written from the same bytes.
TREE_FILES = {
    "Assets/tree/a.txt": b"a\n",
    "Assets/tree/sub/b.bin": b"\x00\x01binary\n",
    SPACED_NAME: b"spaced\n",
    CHINESE_NAME: "中文内容\n".encode("utf-8"),
    # The iOS side stores this as a file; the local side as a symlink to a.txt (same bytes).
    "Assets/tree/regular.txt": b"a\n",
}

# Symlinks stored on the iOS side (git mode 120000); the local side mirrors them.
TREE_SYMLINKS = {
    "Assets/tree/link.txt": "a.txt",
}


def local_path(ios_path):
    """The local path the fixture keeps for the same file (the tree is lower case there)."""
    if ios_path.startswith("Assets/"):
        return "assets/" + ios_path[len("Assets/"):]
    if ios_path.startswith("Docs/"):
        return "docs/" + ios_path[len("Docs/"):]
    return ios_path


def git(cwd, *args, check=True):
    return subprocess.run(
        ["git", "-C", str(cwd), *args], check=check, capture_output=True, text=True
    )


def commit_all(repo, message):
    git(repo, "add", "-A")
    subprocess.run(
        [
            "git", "-C", str(repo),
            "-c", "user.name=fixture",
            "-c", "user.email=fixture@example.com",
            "commit", "-q", "-m", message,
        ],
        check=True, capture_output=True, text=True,
    )
    return git(repo, "rev-parse", "HEAD").stdout.strip()


def rename_into(trash_folder, path):
    if os.stat(path).st_dev != os.stat(trash_folder).st_dev:
        raise OSError("different file system; refusing to copy")
    target = trash_folder / (path.name + "-" + str(time.time_ns()))
    os.rename(path, target)
    return "moved to " + str(target)


def move_to_trash(path):
    """Moves this run's directory into the system trash; never deletes anything recursively."""
    if not path.exists():
        return "already gone"
    macos_trash = Path.home() / ".Trash"
    if macos_trash.is_dir():
        try:
            return rename_into(macos_trash, path)
        except OSError:
            pass
    for command in (["gio", "trash"], ["trash-put"], ["trash"]):
        executable = shutil.which(command[0])
        if executable is None:
            continue
        done = subprocess.run(
            [executable] + command[1:] + [str(path)], capture_output=True, text=True
        )
        if done.returncode == 0:
            return "moved with " + command[0]
    return "left in place: no system trash available, fixtures kept at " + str(path)


class Fixture:
    """One Android-side root and one iOS-side repository holding the same content."""

    def __init__(self, case_dir):
        self.case = case_dir
        self.root = case_dir / "android"
        self.ios = case_dir / "ios"
        (self.root / "scripts").mkdir(parents=True)
        (self.root / "native").mkdir()
        shutil.copy2(CHECK, self.root / "scripts" / "check-ios-sync.sh")

        self.write_local_file("docs/readme.txt", b"hello\n")
        for path, data in TREE_FILES.items():
            self.write_local_file(local_path(path), data)
        (self.root / local_path("Assets/tree/regular.txt")).unlink()
        os.symlink("a.txt", self.root / local_path("Assets/tree/regular.txt"))
        for link, target in TREE_SYMLINKS.items():
            os.symlink(target, self.root / local_path(link))

        self.ios.mkdir()
        git(self.ios, "init", "-q")
        (self.ios / "Docs").mkdir()
        (self.ios / "Docs" / "readme.txt").write_bytes(b"hello\n")
        for path, data in TREE_FILES.items():
            target = self.ios / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        for link, target in TREE_SYMLINKS.items():
            os.symlink(target, self.ios / link)
        self.commit = commit_all(self.ios, "fixture")
        (self.root / "native" / "UPSTREAM").write_text(self.commit + "\n")
        self.set_manifest(IN_SYNC_MANIFEST)

    def write_local_file(self, path, data):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)

    def set_manifest(self, text):
        (self.root / "scripts" / "ios-sync-manifest").write_text(text)

    def record_new_commit(self, message="newer"):
        newer = commit_all(self.ios, message)
        (self.root / "native" / "UPSTREAM").write_text(newer + "\n")
        return newer

    def run(self):
        env = os.environ.copy()
        env["PYTHONDONTWRITEBYTECODE"] = "1"
        return subprocess.run(
            ["bash", str(self.root / "scripts" / "check-ios-sync.sh"), str(self.ios)],
            capture_output=True, text=True, env=env,
        )


class CheckIosSyncTest(unittest.TestCase):
    run_dir = None

    @classmethod
    def setUpClass(cls):
        SCRATCH.mkdir(parents=True, exist_ok=True)
        cls.run_dir = SCRATCH / (
            "check-ios-sync-" + str(time.time_ns()) + "-" + uuid.uuid4().hex[:8]
        )
        if cls.run_dir.exists():
            raise AssertionError("scratch directory collision: " + str(cls.run_dir))
        cls.run_dir.mkdir()

    @classmethod
    def tearDownClass(cls):
        outcome = move_to_trash(cls.run_dir)
        if cls.run_dir.exists():
            raise AssertionError(
                "the fixtures were not moved into the system trash; " + outcome
            )

    def setUp(self):
        self.fixture = Fixture(self.run_dir / ("case-" + uuid.uuid4().hex[:12]))

    def assertSyncPasses(self):
        result = self.fixture.run()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("iOS sync check passed against " + self.fixture.commit, result.stdout)

    def assertSyncFails(self, *expected_fragments):
        result = self.fixture.run()
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("iOS sync check failed", result.stderr)
        for fragment in expected_fragments:
            self.assertIn(fragment, result.stderr)

    def testAnInSyncFileAndTreePass(self):
        self.assertSyncPasses()

    def testAChinesePathInsideATreeIsNotReportedAsDrift(self):
        # git quotes non-ASCII paths unless asked for raw bytes; the check must compare the real
        # name instead of reporting the escaped one as an extra file plus a missing file.
        self.assertSyncPasses()

    def testAChangedChineseFileInsideATreeIsDrift(self):
        self.fixture.write_local_file(local_path(CHINESE_NAME), "改过的内容\n".encode("utf-8"))
        self.assertSyncFails("drift:", "Files differ:", "中文 文件.txt")

    def testASpacedPathInsideATreeIsCompared(self):
        self.fixture.write_local_file(local_path(SPACED_NAME), b"changed\n")
        self.assertSyncFails("drift:", "Files differ:", "name with space.txt")

    def testAChangedFileEntryIsDrift(self):
        self.fixture.write_local_file("docs/readme.txt", b"edited\n")
        self.assertSyncFails("drift:", "docs/readme.txt")

    def testAChangedFileInsideATreeIsDrift(self):
        self.fixture.write_local_file("assets/tree/a.txt", b"changed\n")
        self.assertSyncFails("drift:", "Files differ:", "assets/tree/a.txt")

    def testAnExtraLocalFileInATreeIsReported(self):
        self.fixture.write_local_file("assets/tree/extra.txt", b"extra\n")
        self.assertSyncFails("drift:", "Only in", "extra.txt")

    def testAMissingLocalFileInATreeIsReported(self):
        (self.fixture.root / "assets" / "tree" / "sub" / "b.bin").unlink()
        self.assertSyncFails("drift:", "missing:", "b.bin")

    def testAPathAbsentFromTheiOSCommitIsReported(self):
        self.fixture.set_manifest(IN_SYNC_MANIFEST + "Docs/absent.txt docs/readme.txt\n")
        self.assertSyncFails("missing in", "Docs/absent.txt")

    def testANewerCommitMakesAnUnchangedLocalFileDrift(self):
        (self.fixture.ios / "Docs" / "readme.txt").write_bytes(b"newer\n")
        self.fixture.record_new_commit()
        self.assertSyncFails("drift:", "docs/readme.txt")

    def testCompilerAndFinderNoiseInATreeIsIgnored(self):
        self.fixture.write_local_file("assets/tree/__pycache__/cached.pyc", b"cache\n")
        self.fixture.write_local_file("assets/tree/.DS_Store", b"finder\n")
        self.assertSyncPasses()

    def testALocalSymlinkToTheSameContentCountsAsTheFile(self):
        # The iOS side stores a regular file; the local side a symlink to identical bytes. The
        # diff this replaces dereferenced symlinks too, so this must still pass.
        self.assertSyncPasses()

    def testABrokenLocalSymlinkIsReportedNotTreatedAsMissing(self):
        (self.fixture.root / "assets" / "tree" / "a.txt").unlink()
        os.symlink("does-not-exist", self.fixture.root / "assets" / "tree" / "a.txt")
        self.assertSyncFails("drift:", "broken symlink", "assets/tree/a.txt")

    def testAniOSSymlinkMustBeALocalSymlinkWithTheSameTarget(self):
        os.unlink(self.fixture.root / "assets" / "tree" / "link.txt")
        os.symlink("sub/b.bin", self.fixture.root / "assets" / "tree" / "link.txt")
        self.assertSyncFails("drift:", "symlink differs", "assets/tree/link.txt")

    def testAniOSSymlinkRejectsALocalRegularFile(self):
        os.unlink(self.fixture.root / "assets" / "tree" / "link.txt")
        self.fixture.write_local_file("assets/tree/link.txt", b"a.txt")
        self.assertSyncFails("drift:", "expected a symlink", "assets/tree/link.txt")


if __name__ == "__main__":
    unittest.main()
