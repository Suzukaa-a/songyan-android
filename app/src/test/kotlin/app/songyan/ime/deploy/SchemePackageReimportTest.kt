package app.songyan.ime.deploy

import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.LearningData
import app.songyan.ime.storage.SourceCatalog
import app.songyan.ime.storage.SourceIds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A newer version of a stored scheme package picked as a file. Its files hash to a new source id,
 * so it used to be stored as a second package that learned from scratch; the one the user had
 * taught stayed beside it and the grammar model added to its folder was gone. Now the new files
 * take the stored package's place under its id.
 */
class SchemePackageReimportTest {
    private val storage = DeployFixtures.storage()
    private val runner = DeployFixtures.FakeRunner()
    private val pipeline = DeployFixtures.pipeline(storage, runner)
    private val publisher = GenerationPublisher(storage)
    private val catalog = SourceCatalog(storage)

    private fun version(
        rows: String,
        model: Boolean = false,
    ): Map<String, String> =
        DeployFixtures.stackablePackageFiles(rows).let { files ->
            if (model) files + ("rime_ice-main/wanxiang-lts-zh-hans.gram" to "model") else files
        }

    private suspend fun importFile(files: Map<String, String>): RequestStatus {
        val id = "f" + System.nanoTime()
        DeployFixtures.pickSchemePackage(storage, id, files)
        return pipeline.run(DeployRequest(id, DeployMode.IMPORT_SCHEME))
    }

    private fun schemePackages() = catalog.list().filter { SourceIds.isScheme(it.sourceId) }

    @Test
    fun aNewerFileOfAStoredPackageReplacesItAndKeepsItsLearnedWords() =
        runTest {
            val first = importFile(version("你好\tni hao\t1\n"))
            val id = first.sourceId!!
            val learned =
                File(LearningData.directory(storage, id), "rime_ice.userdb/CURRENT").also {
                    it.parentFile.mkdirs()
                    it.writeText("learned")
                }
            val before = catalog.read(id)!!.revision

            val second = importFile(version("你好\tni hao\t1\n世界\tshi jie\t2\n"))

            assertEquals(second.problem, RequestState.SUCCEEDED, second.state)
            assertEquals("the new version takes the stored package's id", id, second.sourceId)
            assertEquals(DeployPipeline.REPLACED, second.problem)
            assertEquals("one package, not two", listOf(id), schemePackages().map { it.sourceId })
            assertNotEquals(before, catalog.read(id)!!.revision)
            assertTrue(File(storage.sourceDir(id), "rime_ice.dict.yaml").readText().contains("世界"))
            assertEquals(id, publisher.readActive()!!.sourceId)
            assertEquals(LearningData.directory(storage, id), LearningData.directory(storage, second.sourceId!!))
            assertTrue("the learned words stay with the package", learned.isFile)
        }

    @Test
    fun aGrammarModelAddedToThePackageStaysWhenTheNewVersionLacksIt() =
        runTest {
            val id = importFile(version("你好\tni hao\t1\n", model = true)).sourceId!!

            val updated = importFile(version("你好\tni hao\t1\n世界\tshi jie\t2\n"))

            assertEquals(updated.problem, RequestState.SUCCEEDED, updated.state)
            assertEquals(id, updated.sourceId)
            assertEquals("model", File(storage.sourceDir(id), "wanxiang-lts-zh-hans.gram").readText())

            // The same new file again is the version already stored, model included.
            val again = importFile(version("你好\tni hao\t1\n世界\tshi jie\t2\n"))
            assertEquals(id, again.sourceId)
            assertEquals(catalog.read(id)!!.revision, publisher.readActive()!!.revision.substringBefore("+"))
            assertTrue(File(storage.sourceDir(id), "wanxiang-lts-zh-hans.gram").isFile)
        }

    @Test
    fun aNewVersionThatBringsItsOwnModelUsesIt() =
        runTest {
            val id = importFile(version("你好\tni hao\t1\n", model = true)).sourceId!!
            val files =
                version("你好\tni hao\t1\n世界\tshi jie\t2\n") +
                    ("rime_ice-main/wanxiang-lts-zh-hans.gram" to "newer model")

            assertEquals(id, importFile(files).sourceId)

            assertEquals("newer model", File(storage.sourceDir(id), "wanxiang-lts-zh-hans.gram").readText())
        }

    @Test
    fun aPackageWithAnotherMainInputMethodIsStoredBeside() =
        runTest {
            val ice = importFile(version("你好\tni hao\t1\n")).sourceId!!

            val other = importFile(DeployFixtures.schemePackageFiles(schemaId = "luna_pinyin", name = "朙月拼音"))

            assertEquals(other.problem, RequestState.SUCCEEDED, other.state)
            assertNotEquals(ice, other.sourceId)
            assertNull(other.problem)
            assertEquals(2, schemePackages().size)
        }

    @Test
    fun theNewVersionReplacesThePackageInUseWhenEarlierVersionsStoredTwo() =
        runTest {
            val older = importFile(version("你好\tni hao\t1\n")).sourceId!!
            // What earlier versions of the app left: the same package stored twice.
            val copy = "scheme-00000000000000aa"
            storage.sourceDir(older).copyRecursively(storage.sourceDir(copy))
            SourceCatalog.write(storage.sourceDir(copy), catalog.read(older)!!.copy(sourceId = copy))
            pipeline.run(DeployRequest("a1", DeployMode.ACTIVATE_SOURCE, sourceId = copy))
            assertEquals(copy, publisher.readActive()!!.sourceId)

            val status = importFile(version("你好\tni hao\t1\n世界\tshi jie\t2\n"))

            assertEquals(copy, status.sourceId)
            assertTrue(File(storage.sourceDir(copy), "rime_ice.dict.yaml").readText().contains("世界"))
        }

    @Test
    fun aPackageDeletedMomentsAgoComesBackAsTheNewVersionWithItsStackedDictionary() =
        runTest {
            val id = importFile(version("你好\tni hao\t1\n")).sourceId!!
            DeployFixtures.pickDictionaries(
                storage,
                "s1",
                mapOf("mine.dict.yaml" to DeployFixtures.dictionary("mine", "松烟\tsong yan\t9\n")),
            )
            pipeline.run(DeployRequest("s1", DeployMode.IMPORT_DICTIONARY, sourceId = id))
            pipeline.run(DeployRequest("d1", DeployMode.DELETE_SOURCE, sourceId = id))
            assertNull(catalog.read(id))

            val status = importFile(version("你好\tni hao\t1\n世界\tshi jie\t2\n"))

            assertEquals(id, status.sourceId)
            assertFalse(storage.trashedSourceDir(id).exists())
            assertTrue(File(storage.stackedDictionaryDir(id), DeployOverlay.STACKED_FILE).isFile)
        }
}
