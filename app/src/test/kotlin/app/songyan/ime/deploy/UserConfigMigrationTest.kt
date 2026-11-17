package app.songyan.ime.deploy

import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.SourceIds
import app.songyan.ime.userconfig.UserConfigMigration
import app.songyan.ime.userconfig.UserConfigStatus
import app.songyan.ime.userconfig.UserScripts
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The single configuration folder of earlier versions (and of their backups) becomes one copy per
 * source that deployed with it, without changing what any source deploys and without overwriting
 * anything the user has in a source's own folder.
 */
class UserConfigMigrationTest {
    private val storage = DeployFixtures.storage()
    private val runner = DeployFixtures.FakeRunner()
    private val pipeline = DeployFixtures.pipeline(storage, runner)
    private val publisher = GenerationPublisher(storage)

    private val files =
        mapOf(
            "default.custom.yaml" to "patch:\n  menu/page_size: 5\n",
            "double_pinyin.custom.yaml" to "patch:\n  menu/page_size: 7\n",
            "custom_phrase.txt" to "松烟\tsy\t1\n",
            "lua/mine.lua" to "return 1\n",
        )

    private fun write(
        dir: File,
        contents: Map<String, String>,
    ) {
        for ((path, text) in contents) File(dir, path).also { it.parentFile?.mkdirs() }.writeText(text)
    }

    private fun tree(dir: File): Map<String, String> =
        dir
            .walkTopDown()
            .filter { it.isFile }
            .associate { it.relativeTo(dir).invariantSeparatorsPath to it.readText() }

    private fun legacy(contents: Map<String, String> = files) {
        write(storage.legacyUserConfigDir, contents)
        write(storage.legacyAppliedConfigDir, contents)
        storage.legacyOverrideMarksFile.writeText("{\"bases\":{},\"acknowledged\":[\"custom_phrase.txt\"]}")
        storage.legacyConfigStatusFile.writeText(UserConfigStatus.applied("d").encode())
    }

    private fun legacyIsGone() =
        listOf(
            storage.legacyUserConfigDir,
            storage.legacyAppliedConfigDir,
            storage.legacyOverrideMarksFile,
            storage.legacyConfigStatusFile,
        ).none { it.exists() }

    @Test
    fun everySourceKeepsWhatItDeployedWithAndTheUsersOwnFilesWin() =
        runTest {
            DeployFixtures.pickSchemePackage(storage, "b1", DeployFixtures.schemePackageFiles("other_ime", "别的拼音"))
            val b = pipeline.run(DeployRequest("b1", DeployMode.IMPORT_SCHEME)).sourceId!!
            DeployFixtures.pickSchemePackage(storage, "a1", DeployFixtures.stackablePackageFiles())
            val a = pipeline.run(DeployRequest("a1", DeployMode.IMPORT_SCHEME)).sourceId!!
            // What an earlier version published: every source deployed with the one folder.
            val before = HashMap<String, Pair<String, String?>>()
            val scripts = UserScripts(storage)
            for (id in listOf(a, b, SourceIds.BUILTIN)) {
                pipeline.run(DeployRequest("s-$id", DeployMode.ACTIVATE_SOURCE, sourceId = id))
                write(storage.userConfigDir(id), files)
                scripts.accept(scripts.unconfirmed(storage.userConfigDir(id), storage.sourceDir(id)))
                val applied = pipeline.run(DeployRequest("c-$id", DeployMode.APPLY_USER_CONFIG))
                assertEquals(applied.problem, RequestState.SUCCEEDED, applied.state)
                val record = publisher.readActive()!!
                before[id] = record.revision to record.configDigest
            }
            storage.configScopesDir.deleteRecursively()
            storage.configStatusDir.deleteRecursively()
            legacy()
            // Newer than the backup: what the user has in a source's folder stays.
            write(storage.userConfigDir(b), mapOf("custom_phrase.txt" to "B's own\n"))
            File(storage.trashedSourceDir("scheme-gone"), DeploymentCoordinator.TRASH_TREE).mkdirs()
            File(storage.sourcesDir, "scheme-unreadable").mkdirs()

            for (id in listOf(a, b, SourceIds.BUILTIN)) {
                val calls = runner.calls
                val switched = pipeline.run(DeployRequest("m-$id", DeployMode.ACTIVATE_SOURCE, sourceId = id))
                assertEquals(switched.problem, RequestState.SUCCEEDED, switched.state)
                val record = publisher.readActive()!!
                assertEquals(id, record.sourceId)
                assertEquals("$id deploys as before", before[id], record.revision to record.configDigest)
                assertEquals("$id's generation is still current", calls, runner.calls)
            }
            assertEquals(RequestState.SUCCEEDED, pipeline.run(DeployRequest("r", DeployMode.REDEPLOY_ACTIVE)).state)
            val redeployed = publisher.readActive()!!
            assertEquals(before[SourceIds.BUILTIN], redeployed.revision to redeployed.configDigest)

            assertTrue(legacyIsGone())
            for (id in listOf(a, b, SourceIds.BUILTIN, "scheme-gone", "scheme-unreadable")) {
                val edit = if (id == b) files + ("custom_phrase.txt" to "B's own\n") else files
                assertEquals(id, edit, tree(storage.userConfigDir(id)))
                assertEquals(id, files, tree(storage.appliedConfigDir(id)))
                assertTrue(id, storage.overrideMarksFile(id).readText().contains("custom_phrase.txt"))
                assertTrue(id, storage.configStatusFile(id).isFile)
            }

            val after = tree(storage.configRoot)
            assertTrue(UserConfigMigration.run(storage))
            assertEquals("a second run changes nothing", after, tree(storage.configRoot))
        }

    @Test
    fun aRestoredOldBackupNeverOverwritesAScope() {
        write(storage.userConfigDir(SourceIds.BUILTIN), mapOf("x.custom.yaml" to "new\n"))
        write(storage.appliedConfigDir(SourceIds.BUILTIN), mapOf("x.custom.yaml" to "new\n"))
        storage
            .configStatusFile(
                SourceIds.BUILTIN,
            ).also { it.parentFile?.mkdirs() }
            .writeText("{\"state\":\"applied\"}")
        legacy(mapOf("x.custom.yaml" to "old\n", "y.txt" to "old\n"))

        assertTrue(UserConfigMigration.run(storage))

        assertEquals(
            mapOf("x.custom.yaml" to "new\n", "y.txt" to "old\n"),
            tree(storage.userConfigDir(SourceIds.BUILTIN)),
        )
        assertEquals(
            "the applied copy counts as a whole",
            mapOf("x.custom.yaml" to "new\n"),
            tree(storage.appliedConfigDir(SourceIds.BUILTIN)),
        )
        assertEquals("{\"state\":\"applied\"}", storage.configStatusFile(SourceIds.BUILTIN).readText())
        assertTrue(legacyIsGone())
    }

    @Test
    fun theOldFolderStaysUntilEveryCopySucceeded() {
        legacy()
        File(storage.trashedSourceDir("scheme-gone"), DeploymentCoordinator.TRASH_TREE).mkdirs()
        // Something in the way of one scope: its copy cannot be made.
        storage.configScopeDir("scheme-gone").also { it.parentFile?.mkdirs() }.writeText("in the way")

        assertFalse(UserConfigMigration.run(storage))
        assertTrue(storage.legacyUserConfigDir.isDirectory)
        assertTrue(storage.legacyAppliedConfigDir.isDirectory)
        assertTrue(storage.legacyOverrideMarksFile.isFile)

        storage.configScopeDir("scheme-gone").delete()
        // A temporary folder a dead run left.
        File(
            storage.configScopeDir("scheme-gone"),
            ".applied-migrate-1/x.yaml",
        ).also { it.parentFile?.mkdirs() }.writeText("x")
        assertTrue(UserConfigMigration.run(storage))

        assertTrue(legacyIsGone())
        for (id in listOf(SourceIds.BUILTIN, "scheme-gone")) {
            assertEquals(id, files, tree(storage.userConfigDir(id)))
            assertEquals(id, files, tree(storage.appliedConfigDir(id)))
        }
        assertFalse(File(storage.configScopeDir("scheme-gone"), ".applied-migrate-1").exists())
    }

    @Test
    fun anApplicationAnEarlierVersionLeftUnfinishedIsSettledFirst() {
        legacy(mapOf("x.custom.yaml" to "half\n"))
        // Never published: no generation carries the new copy, so the one set aside comes back.
        write(File(storage.configRoot, ".previous-5"), mapOf("x.custom.yaml" to "before\n"))
        File(storage.configRoot, ".staging-6").mkdirs()

        assertTrue(UserConfigMigration.run(storage))

        assertEquals(mapOf("x.custom.yaml" to "before\n"), tree(storage.appliedConfigDir(SourceIds.BUILTIN)))
        assertEquals(mapOf("x.custom.yaml" to "half\n"), tree(storage.userConfigDir(SourceIds.BUILTIN)))
        assertTrue(storage.configRoot.list()!!.none { it.startsWith(".") })
    }

    @Test
    fun withNothingLeftFromEarlierVersionsNothingIsTouched() {
        assertTrue(UserConfigMigration.run(storage))
        assertFalse(storage.configMigrationLockFile.exists())
    }
}
