package app.songyan.ime.deploy

import app.songyan.ime.deploy.DeployFixtures.dictionary
import app.songyan.ime.storage.BuiltinSchemes
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.LearningData
import app.songyan.ime.storage.SourceCatalog
import app.songyan.ime.storage.SourceIds
import app.songyan.ime.storage.declaredInputMethodsWhileIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** Deleting a stored source can be undone (P4-1): the trash holds the last one, learning data never moves. */
class SourceTrashTest {
    private val storage = DeployFixtures.storage()
    private val runner = DeployFixtures.FakeRunner()
    private val pipeline = DeployFixtures.pipeline(storage, runner)
    private val publisher = GenerationPublisher(storage)
    private val catalog = SourceCatalog(storage)

    private suspend fun importScheme(
        id: String,
        schemaId: String = "rime_ice",
    ): String {
        val files =
            if (schemaId == "rime_ice") {
                DeployFixtures.stackablePackageFiles()
            } else {
                DeployFixtures.schemePackageFiles(schemaId)
            }
        DeployFixtures.pickSchemePackage(storage, id, files)
        val status = pipeline.run(DeployRequest(id, DeployMode.IMPORT_SCHEME))
        assertEquals(status.problem, RequestState.SUCCEEDED, status.state)
        return status.sourceId!!
    }

    private suspend fun delete(id: String) =
        pipeline.run(DeployRequest("d$id", DeployMode.DELETE_SOURCE, sourceId = id))

    private suspend fun restore(id: String) =
        pipeline.run(DeployRequest("t$id", DeployMode.RESTORE_SOURCE, sourceId = id))

    @Test
    fun aDeletedPackageComesBackAsAStoredPackageThatIsNotInUse() =
        runTest {
            val scheme = importScheme("r1")
            val learned =
                File(LearningData.directory(storage, scheme), "rime_ice.userdb/CURRENT").also {
                    it.parentFile.mkdirs()
                    it.writeText("learned")
                }
            delete(scheme)
            assertNull(catalog.read(scheme))
            assertEquals(SourceIds.BUILTIN, publisher.readActive()!!.sourceId)

            val status = restore(scheme)

            assertEquals(status.problem, RequestState.SUCCEEDED, status.state)
            assertEquals("restoring does not switch the keyboard", SourceIds.BUILTIN, publisher.readActive()!!.sourceId)
            assertTrue(catalog.read(scheme)!!.let(catalog::isIntact))
            assertTrue("learned words were never touched", learned.isFile)
            assertFalse(storage.trashedSourceDir(scheme).exists())
            val use = pipeline.run(DeployRequest("a1", DeployMode.ACTIVATE_SOURCE, sourceId = scheme))
            assertEquals(use.problem, RequestState.SUCCEEDED, use.state)
        }

    @Test
    fun theStackedDictionaryGoesToTheTrashAndBackWithItsPackage() =
        runTest {
            val scheme = importScheme("r1")
            DeployFixtures.pickDictionaries(
                storage,
                "s1",
                mapOf("mine.dict.yaml" to dictionary("mine", "松烟\tsong yan\t9\n")),
            )
            pipeline.run(DeployRequest("s1", DeployMode.IMPORT_DICTIONARY, sourceId = scheme))
            assertTrue(storage.stackedDictionaryDir(scheme).isDirectory)

            delete(scheme)
            assertFalse(storage.stackedDictionaryDir(scheme).exists())

            restore(scheme)
            assertTrue(storage.stackedDictionaryDir(scheme).isDirectory)
        }

    @Test
    fun onlyTheLatestDeletionCanBeUndone() =
        runTest {
            val first = importScheme("r1", "rime_ice")
            val second = importScheme("r2", "other_scheme")
            delete(first)
            delete(second)

            assertEquals(RequestState.FAILED, restore(first).state)
            assertEquals(RequestState.SUCCEEDED, restore(second).state)
        }

    @Test
    fun importingThePackageAgainSupersedesItsUndoCopy() =
        runTest {
            val scheme = importScheme("r1")
            delete(scheme)

            assertEquals(scheme, importScheme("r2"))

            assertFalse(storage.trashedSourceDir(scheme).exists())
            assertEquals(RequestState.FAILED, restore(scheme).state)
        }

    @Test
    fun aDamagedPackageIsRemovedForGood() =
        runTest {
            val scheme = importScheme("r1")
            pipeline.run(DeployRequest("b1", DeployMode.ACTIVATE_SOURCE, sourceId = SourceIds.BUILTIN))
            File(storage.sourceDir(scheme), "rime_ice.schema.yaml").delete()
            assertEquals(listOf(scheme), catalog.damaged())

            val status = delete(scheme)

            assertEquals(RequestState.SUCCEEDED, status.state)
            assertFalse(storage.sourceDir(scheme).exists())
            assertFalse("nothing worth undoing", storage.trashedSourceDir(scheme).exists())
            assertTrue(catalog.damaged().isEmpty())
        }

    @Test
    fun anOldUndoCopyIsGivenBackToTheDisk() =
        runTest {
            val scheme = importScheme("r1")
            delete(scheme)
            storage.trashDir.setLastModified(System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000)

            pipeline.run(DeployRequest("b1", DeployMode.BUILTIN))

            assertFalse(storage.trashDir.exists())
        }

    @Test
    fun aSourceWithoutItsManifestOrFilesCountsAsDamaged() {
        val broken = storage.sourceDir("scheme-deadbeef").also { it.mkdirs() }
        File(broken, "readme.txt").writeText("left over")

        assertEquals(listOf("scheme-deadbeef"), catalog.damaged())
        // The built-in tree is rewritten by every built-in deploy and never reported.
        storage.sourceDir(SourceIds.BUILTIN).mkdirs()
        assertEquals(listOf("scheme-deadbeef"), catalog.damaged())
    }

    @Test
    fun anInputMethodStaysProvidedWhileItsPackageIsInTheTrashAndGoesWithTheTrash() =
        runTest {
            val scheme = importScheme("p1")
            assertTrue("rime_ice" in catalog.declaredInputMethods()!!)
            delete(scheme)
            // The package can still be restored, and its keyboards' pairings with it.
            assertTrue("rime_ice" in catalog.declaredInputMethods()!!)
            storage.trashDir.deleteRecursively()
            val left = catalog.declaredInputMethods()!!
            assertFalse("rime_ice" in left)
            assertTrue(left.containsAll(BuiltinSchemes.IDS))
        }

    @Test
    fun aSourceThatCannotBeReadLeavesTheInputMethodsUnknown() =
        runTest {
            val scheme = importScheme("p2")
            File(storage.sourceDir(scheme), SourceCatalog.MANIFEST).writeText("{")
            assertNull(catalog.declaredInputMethods())
        }

    @Test
    fun theInputMethodsAreNotReadWhileADeploymentHoldsTheLock() =
        runTest {
            importScheme("p3")
            val path = storage.deployLockFile.toPath()
            FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use { assertNull(declaredInputMethodsWhileIdle(storage)) }
            }
            assertTrue("rime_ice" in declaredInputMethodsWhileIdle(storage)!!)
        }
}
