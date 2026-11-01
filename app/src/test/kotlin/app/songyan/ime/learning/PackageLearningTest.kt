package app.songyan.ime.learning

import app.songyan.ime.deploy.DeployFixtures
import app.songyan.ime.deploy.DeployMode
import app.songyan.ime.deploy.DeployRequest
import app.songyan.ime.deploy.RequestState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Learned words a scheme package ships: staged while it is imported, kept for the installed
 * package, merged only after the user agrees and the package is in use.
 */
class PackageLearningTest {
    private val storage = DeployFixtures.storage()
    private val runner = DeployFixtures.FakeRunner()
    private val pipeline = DeployFixtures.pipeline(storage, runner)
    private val learning = PackageLearning(storage)

    private fun snapshot(
        dictionary: String,
        rows: Int = 2,
    ) = "# Rime user dictionary\n#@/db_name\t$dictionary\n#@/db_type\tuserdb\n" +
        (1..rows).joinToString("") { "ni $it \t你$it\tc=$it d=1 t=1\n" }

    private fun withSnapshots(files: Map<String, String> = DeployFixtures.schemePackageFiles()) =
        files +
            mapOf(
                "rime_ice-main/rime_ice.userdb.txt" to snapshot("rime_ice"),
                "rime_ice-main/double_pinyin.userdb.txt" to snapshot("double_pinyin", rows = 1),
            )

    private suspend fun import(
        id: String,
        files: Map<String, String>,
    ): app.songyan.ime.deploy.RequestStatus {
        DeployFixtures.pickSchemePackage(storage, id, files)
        return pipeline.run(DeployRequest(id, DeployMode.IMPORT_SCHEME))
    }

    @Test
    fun aPackageWithSnapshotsInstallsAndKeepsThemForItsSource() =
        runTest {
            val status = import("r1", withSnapshots())

            assertEquals(status.problem, RequestState.SUCCEEDED, status.state)
            val pending = learning.pending(status.sourceId!!)
            assertEquals(listOf("double_pinyin", "rime_ice"), pending.map { it.snapshot.dictionary }.sorted())
            assertEquals(3, pending.sumOf { it.snapshot.entryCount })
            // Not source files: the deploy sees none of them, and nothing is left staged.
            val source = storage.sourceDir(status.sourceId!!)
            assertTrue(source.walkTopDown().none { it.name.contains("userdb") })
            assertTrue(
                storage.importStagingDir
                    .listFiles()
                    .orEmpty()
                    .isEmpty(),
            )
        }

    @Test
    fun aPackageWithoutSnapshotsKeepsNothing() =
        runTest {
            val status = import("r1", DeployFixtures.schemePackageFiles())

            assertEquals(RequestState.SUCCEEDED, status.state)
            assertTrue(learning.pending(status.sourceId!!).isEmpty())
            assertFalse(
                storage.packageLearningRoot.exists() &&
                    storage.packageLearningRoot
                        .list()
                        .orEmpty()
                        .isNotEmpty(),
            )
        }

    @Test
    fun aNewerFileReplacesThePendingSnapshotsOfThePackage() =
        runTest {
            val first = import("r1", withSnapshots())
            val id = first.sourceId!!
            val newer =
                DeployFixtures.schemePackageFiles() +
                    mapOf(
                        "rime_ice-main/new.txt" to "newer",
                        "rime_ice-main/rime_ice.userdb.txt" to snapshot("rime_ice", rows = 5),
                    )

            val second = import("r2", newer)

            assertEquals(id, second.sourceId)
            assertEquals(listOf(5), learning.pending(id).map { it.snapshot.entryCount })
        }

    @Test
    fun anImportThatFailsOrIsCancelledKeepsNoSnapshots() =
        runTest {
            DeployFixtures.pickSchemePackage(storage, "r1", withSnapshots())
            val cancelled = pipeline.run(DeployRequest("r1", DeployMode.IMPORT_SCHEME), cancelled = { true })
            assertEquals(RequestState.FAILED, cancelled.state)

            val rejected = import("r2", withSnapshots(mapOf("readme.txt" to "no schema")))
            assertEquals(RequestState.FAILED, rejected.state)

            assertTrue(
                storage.packageLearningRoot
                    .list()
                    .orEmpty()
                    .isEmpty(),
            )
            assertTrue(
                storage.importStagingDir
                    .listFiles()
                    .orEmpty()
                    .isEmpty(),
            )
        }

    @Test
    fun snapshotsOfAPackageThatIsGoneAreSwept() =
        runTest {
            val id = import("r1", withSnapshots()).sourceId!!
            File(storage.packageLearningDir("scheme-gone"), "incoming-0.userdb.txt").apply {
                parentFile.mkdirs()
                writeText(snapshot("x"))
            }

            import("r2", withSnapshots(DeployFixtures.schemePackageFiles("other", "其他")))

            assertFalse(storage.packageLearningDir("scheme-gone").exists())
            assertTrue(storage.packageLearningDir(id).isDirectory)
        }

    // The merge decision

    private class Merge(
        val learning: PackageLearning,
        var active: String?,
        var activates: Boolean = true,
        var fail: Boolean = false,
        var matches: Set<String> = setOf("rime_ice", "double_pinyin"),
    ) {
        val activations = ArrayList<String>()
        val merged = ArrayList<List<String>>()

        val flow =
            PackageLearningMerge(
                learning,
                { active },
                { id ->
                    activations += id
                    if (activates) active = id
                    activates
                },
                { snapshots ->
                    check(!fail) { "merge failed" }
                    merged += snapshots.map { it.snapshot.dictionary }
                    val (hit, miss) = snapshots.partition { it.snapshot.dictionary in matches }
                    RimeSnapshotMergeReport(
                        hit,
                        miss.map { SkippedSnapshot(it.fileName, SnapshotSkipReason.NOT_INSTALLED) },
                    )
                },
            )
    }

    @Test
    fun aPackageInUseMergesWithoutBeingActivatedAndTheSnapshotsAreDropped() =
        runTest {
            val id = import("r1", withSnapshots()).sourceId!!
            val merge = Merge(learning, active = id)

            val outcome = merge.flow.accept(id) as PackageLearningOutcome.Merged

            assertEquals(2, outcome.report.dictionaryCount)
            assertEquals(3L, outcome.report.entryCount)
            assertTrue(merge.activations.isEmpty())
            assertTrue(learning.pending(id).isEmpty())
            assertEquals(PackageLearningOutcome.Gone, merge.flow.accept(id))
        }

    @Test
    fun aPackageNotInUseIsPutInUseFirst() =
        runTest {
            val id = import("r1", withSnapshots()).sourceId!!
            val merge = Merge(learning, active = "songyan-builtin")

            assertTrue(merge.flow.accept(id) is PackageLearningOutcome.Merged)
            assertEquals(listOf(id), merge.activations)
        }

    @Test
    fun whenThePackageCannotBeUsedNothingIsMergedAndTheSnapshotsStay() =
        runTest {
            val id = import("r1", withSnapshots()).sourceId!!
            val merge = Merge(learning, active = "songyan-builtin", activates = false)

            assertEquals(PackageLearningOutcome.NotInUse, merge.flow.accept(id))
            assertTrue(merge.merged.isEmpty())
            assertEquals(2, learning.pending(id).size)
        }

    @Test
    fun snapshotsForNoDictionaryOfThePackageMergeNothing() =
        runTest {
            val id = import("r1", withSnapshots()).sourceId!!
            val merge = Merge(learning, active = id, matches = emptySet())

            assertEquals(PackageLearningOutcome.NothingToMerge, merge.flow.accept(id))
            assertTrue(learning.pending(id).isEmpty())
        }

    @Test
    fun aFailedMergeReportsFailureAndNeverTouchesThePackage() =
        runTest {
            val id = import("r1", withSnapshots()).sourceId!!
            val merge = Merge(learning, active = id, fail = true)

            assertEquals(PackageLearningOutcome.Failed, merge.flow.accept(id))
            assertTrue(storage.sourceDir(id).isDirectory)
        }

    @Test
    fun decliningDropsTheSnapshotsAndChangesNothingElse() =
        runTest {
            val id = import("r1", withSnapshots()).sourceId!!

            learning.discard(id)

            assertTrue(learning.pending(id).isEmpty())
            assertTrue(storage.sourceDir(id).isDirectory)
            assertNull(storage.packageLearningDir(id).listFiles())
        }
}
