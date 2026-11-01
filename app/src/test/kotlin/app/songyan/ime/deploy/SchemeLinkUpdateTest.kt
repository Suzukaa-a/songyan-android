package app.songyan.ime.deploy

import app.songyan.ime.deploy.DeployFixtures.dictionary
import app.songyan.ime.deploy.net.DictionaryLinkFetcher
import app.songyan.ime.deploy.net.HttpResponse
import app.songyan.ime.deploy.net.HttpTransport
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Scheme packages from a link (P4-2) and their updates (P4-1): the download is rewritten for
 * GitHub, remembered as the package's origin, and an update installs the new files under the same
 * package id so learned words stay, stacked dictionaries are rebuilt and the keyboard keeps
 * using whatever it used.
 */
class SchemeLinkUpdateTest {
    private val storage = DeployFixtures.storage()
    private val runner = DeployFixtures.FakeRunner()
    private val publisher = GenerationPublisher(storage)
    private val catalog = SourceCatalog(storage)

    /** What the "server" answers for each requested URI; records what was asked. */
    private val asked = mutableListOf<URI>()
    private var served: ByteArray = ByteArray(0)

    private class Reply(
        private val bytes: ByteArray,
    ) : HttpResponse {
        override val status: Int = 200
        override val location: String? = null
        override val contentLength: Long = bytes.size.toLong()
        override val body: InputStream = ByteArrayInputStream(bytes)

        override fun close() = Unit
    }

    private val transport =
        HttpTransport {
            asked += it
            Reply(served)
        }

    private val pipeline =
        DeployPipeline(
            storage,
            DeployFixtures.coordinator(storage, runner, DeployFixtures.assets()),
            SourceBuilder(DeployFixtures.assets()),
            fetcher = DictionaryLinkFetcher(transport),
        )

    private fun zipOf(files: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((path, text) in files) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun version(rows: String) = DeployFixtures.stackablePackageFiles(rows)

    private suspend fun importFromLink(
        files: Map<String, String>,
        url: String = LINK,
    ): RequestStatus {
        served = zipOf(files)
        return pipeline.run(DeployRequest("l" + System.nanoTime(), DeployMode.IMPORT_SCHEME_URL, url = url))
    }

    private suspend fun update(
        sourceId: String,
        files: Map<String, String>,
    ): RequestStatus {
        served = zipOf(files)
        val origin = catalog.read(sourceId)!!.origin!!
        return pipeline.run(
            DeployRequest("u" + System.nanoTime(), DeployMode.IMPORT_SCHEME_URL, sourceId = sourceId, url = origin),
        )
    }

    @Test
    fun aGithubRepositoryAddressIsDownloadedAsItsZipAndRemembered() =
        runTest {
            val status = importFromLink(version("你好\tni hao\t1\n"))

            assertEquals(status.problem, RequestState.SUCCEEDED, status.state)
            assertEquals(
                URI("https://codeload.github.com/iDvel/rime-ice/zip/HEAD"),
                asked.single(),
            )
            val stored = catalog.read(status.sourceId!!)!!
            assertEquals("https://codeload.github.com/iDvel/rime-ice/zip/HEAD", stored.origin)
            assertEquals(status.sourceId, publisher.readActive()!!.sourceId)
        }

    @Test
    fun aLinkThatAnswersWithAWebPageInstallsNothing() =
        runTest {
            served = "<html>not a zip</html>".toByteArray()

            val status = pipeline.run(DeployRequest("r1", DeployMode.IMPORT_SCHEME_URL, url = LINK))

            assertEquals(RequestState.FAILED, status.state)
            assertEquals("NOT_ZIP", status.problem)
            assertTrue(catalog.list().none { SourceIds.isScheme(it.sourceId) })
            assertFalse(storage.incomingDir("r1").exists())
        }

    @Test
    fun anUpdateKeepsThePackageIdItsLearnedWordsAndRebuildsTheStackedDictionary() =
        runTest {
            val first = importFromLink(version("你好\tni hao\t1\n"))
            val id = first.sourceId!!
            val learned =
                File(LearningData.directory(storage, id), "rime_ice.userdb/CURRENT").also {
                    it.parentFile.mkdirs()
                    it.writeText("learned")
                }
            DeployFixtures.pickDictionaries(
                storage,
                "s1",
                mapOf("mine.dict.yaml" to dictionary("mine", "松烟\tsong yan\t9\n")),
            )
            val stacked = pipeline.run(DeployRequest("s1", DeployMode.IMPORT_DICTIONARY, sourceId = id))
            assertEquals(stacked.problem, RequestState.SUCCEEDED, stacked.state)
            val before = catalog.read(id)!!

            val status = update(id, version("你好\tni hao\t1\n世界\tshi jie\t2\n"))

            assertEquals(status.problem, RequestState.SUCCEEDED, status.state)
            assertEquals("the update keeps the package id", id, status.sourceId)
            val after = catalog.read(id)!!
            assertNotEquals(before.revision, after.revision)
            assertEquals("the link is kept", before.origin, after.origin)
            assertEquals(id, publisher.readActive()!!.sourceId)
            assertTrue("learned words stay", learned.isFile)
            assertTrue(
                "the stacked dictionary is part of the rebuilt package",
                runner.overlays
                    .last()
                    .getValue("songyan_imported.dict.yaml")
                    .contains("松烟"),
            )
            assertTrue(File(storage.sourceDir(id), "rime_ice.dict.yaml").readText().contains("世界\tshi jie\t2"))
        }

    @Test
    fun anUpdateWithNothingNewInstallsNothing() =
        runTest {
            val files = version("你好\tni hao\t1\n")
            val id = importFromLink(files).sourceId!!
            val calls = runner.calls
            val generation = publisher.readActive()!!.generation

            val status = update(id, files)

            assertEquals(RequestState.SUCCEEDED, status.state)
            assertEquals(DeployPipeline.UP_TO_DATE, status.problem)
            assertEquals(calls, runner.calls)
            assertEquals(generation, publisher.readActive()!!.generation)
        }

    @Test
    fun anUpdateOfAPackageThatIsNotInUseLeavesTheKeyboardWhereItWas() =
        runTest {
            val id = importFromLink(version("你好\tni hao\t1\n")).sourceId!!
            pipeline.run(DeployRequest("b1", DeployMode.ACTIVATE_SOURCE, sourceId = SourceIds.BUILTIN))
            val builtinGeneration = publisher.readActive()!!.generation
            val before = catalog.read(id)!!.revision

            val status = update(id, version("你好\tni hao\t1\n世界\tshi jie\t2\n"))

            assertEquals(status.problem, RequestState.SUCCEEDED, status.state)
            assertNotEquals(before, catalog.read(id)!!.revision)
            val active = publisher.readActive()!!
            assertEquals(SourceIds.BUILTIN, active.sourceId)
            assertEquals("the built-in generation is reused, not rebuilt", builtinGeneration, active.generation)
        }

    @Test
    fun anUpdateForAPackageThatIsGoneFails() =
        runTest {
            val status =
                pipeline.run(
                    DeployRequest("u1", DeployMode.IMPORT_SCHEME_URL, sourceId = "scheme-0123456789abcdef", url = LINK),
                )

            assertEquals(RequestState.FAILED, status.state)
            assertEquals("UPDATE_TARGET_MISSING", status.problem)
            assertTrue(asked.isEmpty())
        }

    @Test
    fun importingTheSamePackageFromAFileKeepsItsLink() =
        runTest {
            val files = version("你好\tni hao\t1\n")
            val id = importFromLink(files).sourceId!!
            DeployFixtures.zip(File(storage.incomingDir("f1"), DeployPipeline.PACKAGE_FILE), files)

            val status = pipeline.run(DeployRequest("f1", DeployMode.IMPORT_SCHEME))

            assertEquals(id, status.sourceId)
            assertEquals("https://codeload.github.com/iDvel/rime-ice/zip/HEAD", catalog.read(id)!!.origin)
        }

    @Test
    fun aFolderPackageHasNoLink() =
        runTest {
            DeployFixtures.pickSchemePackage(storage, "f1")

            val status = pipeline.run(DeployRequest("f1", DeployMode.IMPORT_SCHEME))

            assertNull(catalog.read(status.sourceId!!)!!.origin)
        }

    private companion object {
        const val LINK = "https://github.com/iDvel/rime-ice"
    }
}
