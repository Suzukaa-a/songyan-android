package app.songyan.ime.deploy

import app.songyan.ime.deploy.net.DictionaryLinkFetcher
import app.songyan.ime.deploy.net.HttpResponse
import app.songyan.ime.deploy.net.HttpTransport
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.SourceCatalog
import app.songyan.ime.storage.SourceScheme
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A recommended entry is assembled from several pinned archives. Every archive is downloaded and
 * must match the digest its catalogue entry names: a different byte installs nothing, and a package
 * that ends up incomplete or cancelled leaves the scheme the keyboard uses as it was.
 */
class MultiArchiveDeployTest {
    private val storage = DeployFixtures.storage()
    private val runner = DeployFixtures.FakeRunner()
    private val publisher = GenerationPublisher(storage)
    private val catalog = SourceCatalog(storage)

    private val asked = mutableListOf<URI>()
    private val served = linkedMapOf<String, ByteArray>()

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
        HttpTransport { uri ->
            asked += uri
            Reply(served.getValue(uri.toString()))
        }

    private val pipeline =
        DeployPipeline(
            storage,
            DeployFixtures.coordinator(storage, runner, DeployFixtures.assets()),
            SourceBuilder(DeployFixtures.assets()),
            fetcher = DictionaryLinkFetcher(transport),
        )

    @Test
    fun pinnedArchivesAreDownloadedVerifiedAndMergedIntoOneScheme() =
        runTest {
            servePackage()

            val status = pipeline.run(request("r1"))

            assertEquals(status.problem, RequestState.SUCCEEDED, status.state)
            val id = status.sourceId!!
            val manifest = catalog.read(id)!!
            assertEquals(listOf(SourceScheme("bopomofo_tw", "注音")), manifest.schemes)
            assertEquals("the entry archive is the package link", MAIN_URL, manifest.origin)
            assertEquals(listOf(MAIN_URL, DEPENDENCY_URL), asked.map { it.toString() })
            val dictionary = File(storage.sourceDir(id), "terra_pinyin.dict.yaml")
            assertTrue("the dependency dictionary is part of the package", dictionary.isFile)
            val config = File(storage.sourceDir(id), "default.yaml").readText()
            assertTrue("the advertised input method is enabled: " + config, config.contains("schema: bopomofo_tw"))
            assertFalse("a dependency schema is not offered", config.contains("schema: terra_pinyin"))
            assertEquals(id, publisher.readActive()!!.sourceId)
            assertFalse(storage.incomingDir("r1").exists())
        }

    @Test
    fun allPinnedDownloadsShareOneArchiveBudget() =
        runTest {
            servePackage()
            pipeline.run(request("r1"))
            val active = publisher.readActive()!!
            val limited =
                DeployPipeline(
                    storage,
                    DeployFixtures.coordinator(storage, runner, DeployFixtures.assets()),
                    SourceBuilder(DeployFixtures.assets()),
                    fetcher = DictionaryLinkFetcher(transport),
                    schemeArchiveBytes = maxOf(mainZip().size, dependencyZip().size).toLong() + 1,
                )

            val status = limited.run(request("r2"))

            assertEquals(RequestState.FAILED, status.state)
            assertEquals("FILE_TOO_LARGE", status.problem)
            assertEquals(active.generation, publisher.readActive()!!.generation)
            assertFalse(storage.incomingDir("r2").exists())
        }

    @Test
    fun anArchiveWhoseDigestDiffersInstallsNothingAndKeepsTheCurrentScheme() =
        runTest {
            servePackage()
            pipeline.run(request("r1"))
            val active = publisher.readActive()!!
            val sources = catalog.list().map { it.sourceId }
            val calls = runner.calls

            val tampered = dependencyFiles(dictionary = TAMPERED_DICTIONARY)
            served[DEPENDENCY_URL] = zip(DEPENDENCY_WRAPPER, tampered)
            // The catalogue still names the archives it pinned, not what the server now sends.
            val status = pipeline.run(request("r2", listOf(mainZip(), dependencyZip())))

            assertEquals(RequestState.FAILED, status.state)
            assertEquals("CHECKSUM_MISMATCH", status.problem)
            assertEquals("the keyboard keeps its generation", active.generation, publisher.readActive()!!.generation)
            assertEquals("nothing was stored", sources, catalog.list().map { it.sourceId })
            assertEquals("nothing was deployed", calls, runner.calls)
            assertFalse(storage.incomingDir("r2").exists())
        }

    @Test
    fun anIncompletePackageIsRefusedAndLeavesTheCurrentSchemeUntouched() =
        runTest {
            servePackage()
            pipeline.run(request("r1"))
            val active = publisher.readActive()!!
            val calls = runner.calls

            val incomplete = zip(DEPENDENCY_WRAPPER, dependencyFiles(dictionary = null))
            served[DEPENDENCY_URL] = incomplete
            val status = pipeline.run(request("r2", listOf(mainZip(), incomplete)))

            assertEquals(RequestState.FAILED, status.state)
            assertEquals("MISSING_DEPENDENCY", status.problem)
            assertEquals(listOf("terra_pinyin.dict.yaml"), status.paths)
            assertEquals(active.generation, publisher.readActive()!!.generation)
            assertEquals(calls, runner.calls)
        }

    @Test
    fun cancellingBetweenArchivesLeavesTheCurrentSchemeUntouched() =
        runTest {
            servePackage()
            pipeline.run(request("r1"))
            val active = publisher.readActive()!!
            val calls = asked.size
            val sources = catalog.list().map { it.sourceId }

            val status = pipeline.run(request("r2"), cancelled = { asked.size > calls })

            assertEquals(RequestState.FAILED, status.state)
            assertEquals(DeployPipeline.CANCELLED, status.problem)
            assertEquals(active.generation, publisher.readActive()!!.generation)
            assertEquals(sources, catalog.list().map { it.sourceId })
            assertFalse(storage.incomingDir("r2").exists())
        }

    @Test
    fun progressCountsEveryArchiveAsOneDownload() =
        runTest {
            servePackage()
            val seen = mutableListOf<Pair<Long, Long?>>()

            pipeline.run(request("r1"), progress = { bytes, total -> seen += bytes to total })

            val total = expectedBytes()
            assertTrue("progress was reported", seen.isNotEmpty())
            assertEquals(total to total, seen.last())
            val advance = seen.map { it.first }.zipWithNext()
            assertTrue("progress never goes backwards", advance.all { (a, b) -> b >= a })
        }

    @Test
    fun theRequestCarriesItsPinnedArchivesAndAdvertisedSchemasThroughStringExtras() {
        val archives =
            listOf(
                PinnedArchive(MAIN_URL, sha256(mainZip()), mainZip().size.toLong()),
                PinnedArchive(DEPENDENCY_URL, sha256(dependencyZip()), dependencyZip().size.toLong()),
            )
        val extras =
            mapOf(
                DeployContract.EXTRA_ARCHIVES to DeployRequest.archiveExtra(archives),
                DeployContract.EXTRA_SCHEMA_IDS to "bopomofo_tw",
            )

        val request =
            DeployRequest.from(
                requestId = "r1",
                mode = DeployMode.IMPORT_SCHEME_URL.wireName,
                force = false,
                stringExtra = { extras[it] },
            )

        assertEquals(archives, request!!.archives)
        assertEquals(listOf("bopomofo_tw"), request.advertisedSchemas)
    }

    @Test
    fun aMalformedArchiveEntryMakesTheRequestUnreadable() {
        val malformed = MAIN_URL + "\tnot-a-digest\t100"

        assertNull(
            DeployRequest.from(
                requestId = "r1",
                mode = DeployMode.IMPORT_SCHEME_URL.wireName,
                force = false,
                stringExtra = { if (it == DeployContract.EXTRA_ARCHIVES) malformed else null },
            ),
        )
    }

    /** A request that pins exactly the archives [expected], while the server may serve other ones. */
    private fun request(
        id: String,
        expected: List<ByteArray> = listOf(mainZip(), dependencyZip()),
    ): DeployRequest =
        DeployRequest(
            id,
            DeployMode.IMPORT_SCHEME_URL,
            archives =
                listOf(
                    PinnedArchive(MAIN_URL, sha256(expected[0]), expected[0].size.toLong()),
                    PinnedArchive(DEPENDENCY_URL, sha256(expected[1]), expected[1].size.toLong()),
                ),
            advertisedSchemas = listOf("bopomofo_tw"),
        )

    private fun expectedBytes(): Long = mainZip().size.toLong() + dependencyZip().size.toLong()

    private fun servePackage() {
        served[MAIN_URL] = mainZip()
        served[DEPENDENCY_URL] = dependencyZip()
    }

    private fun mainZip(): ByteArray =
        zip(
            MAIN_WRAPPER,
            linkedMapOf(
                "bopomofo_tw.schema.yaml" to
                    "schema:\n  schema_id: bopomofo_tw\n  name: 注音\ntranslator:\n  dictionary: terra_pinyin\n",
                "LICENSE" to "LGPL-3.0\n",
            ),
        )

    private fun dependencyZip(): ByteArray = zip(DEPENDENCY_WRAPPER, dependencyFiles(DICTIONARY))

    private fun dependencyFiles(dictionary: String?): Map<String, String> =
        linkedMapOf(
            "terra_pinyin.schema.yaml" to
                "schema:\n  schema_id: terra_pinyin\n  name: 地球拼音\ntranslator:\n  dictionary: terra_pinyin\n",
            "LICENSE" to "LGPL-3.0\n",
        ) + (dictionary?.let { mapOf("terra_pinyin.dict.yaml" to it) } ?: emptyMap())

    private fun zip(
        wrapper: String,
        files: Map<String, String>,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((path, text) in files) {
                zip.putNextEntry(ZipEntry(wrapper + "/" + path))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAIN_URL =
            "https://codeload.github.com/rime/rime-bopomofo/zip/6085c9a38a4a728047862b33d67eee18aa86f3b9"
        const val DEPENDENCY_URL =
            "https://codeload.github.com/rime/rime-terra-pinyin/zip/723e51bc266cf9464530c1ddedb856aa18e3da34"
        const val MAIN_WRAPPER = "rime-bopomofo-6085c9a38a4a728047862b33d67eee18aa86f3b9"
        const val DEPENDENCY_WRAPPER = "rime-terra-pinyin-723e51bc266cf9464530c1ddedb856aa18e3da34"
        const val DICTIONARY = "---\nname: terra_pinyin\nversion: \"1\"\nsort: by_weight\n...\n你\tni\t1\n"
        const val TAMPERED_DICTIONARY =
            "---\nname: terra_pinyin\nversion: \"1\"\nsort: by_weight\n...\n我\two\t2\n"
    }
}
