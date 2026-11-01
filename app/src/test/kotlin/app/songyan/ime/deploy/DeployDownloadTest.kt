package app.songyan.ime.deploy

import app.songyan.ime.deploy.net.DictionaryLinkFetcher
import app.songyan.ime.deploy.net.HttpResponse
import app.songyan.ime.deploy.net.HttpTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.UnknownHostException

/** The download request the engine feature screen uses: the file lands where the settings side picks it up. */
class DeployDownloadTest {
    private val storage = DeployFixtures.storage()

    private class Body(
        override val status: Int,
        private val text: String = "",
    ) : HttpResponse {
        override val location: String? = null
        override val contentLength: Long = text.length.toLong()
        override val body: InputStream = ByteArrayInputStream(text.toByteArray())

        override fun close() = Unit
    }

    private fun pipeline(transport: HttpTransport) =
        DeployPipeline(
            storage,
            DeployFixtures.coordinator(storage, DeployFixtures.FakeRunner(), DeployFixtures.assets()),
            SourceBuilder(DeployFixtures.assets()),
            fetcher = DictionaryLinkFetcher(transport, online = { true }),
        )

    private fun request(url: String = "https://example.com/m.gram") =
        DeployRequest("r1", DeployMode.DOWNLOAD_FILE, url = url)

    @Test
    fun theDownloadedFileIsLeftForTheSettingsSide() =
        runTest {
            val status = pipeline { Body(200, "model bytes") }.run(request())

            assertEquals(RequestState.SUCCEEDED, status.state)
            assertEquals("model bytes", storage.downloadFile("r1").readText())
        }

    @Test
    fun aSmallFileRequestRefusesALargerOne() =
        runTest {
            val status =
                pipeline { Body(200, "x".repeat(300)) }
                    .run(
                        DeployRequest(
                            "r1",
                            DeployMode.DOWNLOAD_FILE,
                            url = "https://example.com/h.json",
                            maxBytes = 256,
                        ),
                    )

            assertEquals("FILE_TOO_LARGE", status.problem)
            assertFalse(storage.downloadFile("r1").exists())
        }

    @Test
    fun aFailedDownloadLeavesNothingAndNamesTheReason() =
        runTest {
            val status = pipeline { throw UnknownHostException("example.com") }.run(request())

            assertEquals(RequestState.FAILED, status.state)
            assertEquals("DOWNLOAD_HOST", status.problem)
            assertFalse(storage.downloadFile("r1").exists())
        }

    @Test
    fun aServerErrorCarriesItsStatus() =
        runTest {
            val status = pipeline { Body(503) }.run(request())

            assertEquals("DOWNLOAD_SERVER", status.problem)
            assertEquals(listOf("503"), status.paths)
        }

    @Test
    fun aDroppedConnectionIsAPlainDownloadFailureAndCanBeRetried() =
        runTest {
            val status = pipeline { throw IOException("reset") }.run(request())

            assertEquals("DOWNLOAD_FAILED", status.problem)
            assertTrue(
                app.songyan.ime.content.DictionaryImportProblems.retryable(
                    app.songyan.ime.content.DictionaryImportProblemKind.DOWNLOAD_FAILED,
                ),
            )
        }
}
