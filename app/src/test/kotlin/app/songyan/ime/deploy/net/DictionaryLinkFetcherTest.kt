package app.songyan.ime.deploy.net

import app.songyan.ime.content.DictionaryImportProblemException
import app.songyan.ime.content.DictionaryImportProblemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.nio.file.Files

class DictionaryLinkFetcherTest {
    private val directory: File = Files.createTempDirectory("downloads").toFile()

    private class Response(
        override val status: Int,
        text: String = "",
        override val location: String? = null,
    ) : HttpResponse {
        override val contentLength: Long = text.length.toLong()
        override val body: InputStream = ByteArrayInputStream(text.toByteArray())

        override fun close() = Unit
    }

    private class Server(
        val routes: Map<String, HttpResponse>,
    ) : HttpTransport {
        val requested = mutableListOf<String>()

        override fun get(uri: URI): HttpResponse {
            requested += uri.toString()
            return routes[uri.toString()] ?: Response(404)
        }
    }

    private fun problemOf(block: () -> Unit): DictionaryImportProblemKind? =
        (runCatching(block).exceptionOrNull() as? DictionaryImportProblemException)?.kind

    @Test
    fun githubPageLinksPointAtTheRawFile() {
        assertEquals(
            URI("https://raw.githubusercontent.com/iDvel/rime-ice/main/cn_dicts/base.dict.yaml"),
            DictionaryLinkFetcher.normalize("https://github.com/iDvel/rime-ice/blob/main/cn_dicts/base.dict.yaml"),
        )
        assertEquals(
            URI("https://example.com/a.dict.yaml"),
            DictionaryLinkFetcher.normalize(" https://example.com/a.dict.yaml "),
        )
        assertNull(DictionaryLinkFetcher.normalize("http://example.com/a.dict.yaml"))
        assertNull(DictionaryLinkFetcher.normalize("not a url"))
        assertNull(DictionaryLinkFetcher.normalize("https://"))
    }

    @Test
    fun importedTablesAreFetchedRelativeToTheLinkedFile() {
        val main = "---\nname: main\nimport_tables:\n  - extra\n...\n你好\tni hao\t1\n"
        val extra = "---\nname: extra\n...\n世界\tshi jie\t1\n"
        val server =
            Server(
                mapOf(
                    "https://raw.githubusercontent.com/o/r/main/dicts/main.dict.yaml" to Response(200, main),
                    "https://raw.githubusercontent.com/o/r/main/dicts/extra.dict.yaml" to Response(200, extra),
                ),
            )

        val files =
            DictionaryLinkFetcher(server).fetch("https://github.com/o/r/blob/main/dicts/main.dict.yaml", directory)

        assertEquals(listOf("main.dict.yaml", "extra.dict.yaml"), files.map { it.path })
        assertEquals(extra, files[1].file.readText())
    }

    @Test
    fun aRedirectToPlainHttpIsReportedAsInsecure() {
        val server =
            Server(mapOf("https://a.example/x.dict.yaml" to Response(302, location = "http://a.example/x.dict.yaml")))

        assertEquals(
            DictionaryImportProblemKind.INSECURE_REDIRECT,
            problemOf { DictionaryLinkFetcher(server).fetch("https://a.example/x.dict.yaml", directory) },
        )
    }

    @Test
    fun linksNotNamingADictionaryAreRefusedBeforeAnyRequest() {
        val server = Server(emptyMap())
        assertEquals(
            DictionaryImportProblemKind.UNSUPPORTED_URL_FILE,
            problemOf { DictionaryLinkFetcher(server).fetch("https://a.example/readme.md", directory) },
        )
        assertEquals(
            DictionaryImportProblemKind.UNSUPPORTED_URL,
            problemOf { DictionaryLinkFetcher(server).fetch("ftp://a.example/x.dict.yaml", directory) },
        )
        assertEquals(emptyList<String>(), server.requested)
    }

    @Test
    fun theWholeDownloadHasATimeLimitAndCanBeCancelled() {
        var now = 0L
        val slow =
            HttpTransport {
                now += 200_000
                Response(200, "---\nname: x\n...\n")
            }
        assertEquals(
            DictionaryImportProblemKind.DOWNLOAD_TIMEOUT,
            problemOf {
                DictionaryLinkFetcher(
                    slow,
                    totalTimeMs = 180_000,
                    clock = { now },
                ).fetch("https://a/x.dict.yaml", directory)
            },
        )
        assertEquals(
            DictionaryImportProblemKind.CANCELLED,
            problemOf { DictionaryLinkFetcher(slow).fetch("https://a/x.dict.yaml", directory) { true } },
        )
    }

    @Test
    fun tooManyRedirectsStop() {
        val loop = HttpTransport { Response(301, location = "https://a/x.dict.yaml") }
        assertEquals(
            DictionaryImportProblemKind.TOO_MANY_REDIRECTS,
            problemOf { DictionaryLinkFetcher(loop).fetch("https://a/x.dict.yaml", directory) },
        )
    }

    private fun failing(error: java.io.IOException) = HttpTransport { throw error }

    @Test
    fun anUnknownHostIsADomainProblemWhileOnlineAndANetworkProblemWhileOffline() {
        val unknown = failing(java.net.UnknownHostException("a.example"))
        assertEquals(
            DictionaryImportProblemKind.DOWNLOAD_HOST,
            problemOf {
                DictionaryLinkFetcher(
                    unknown,
                    online = { true },
                ).fetch("https://a.example/x.dict.yaml", directory)
            },
        )
        assertEquals(
            DictionaryImportProblemKind.DOWNLOAD_OFFLINE,
            problemOf {
                DictionaryLinkFetcher(
                    unknown,
                    online = { false },
                ).fetch("https://a.example/x.dict.yaml", directory)
            },
        )
    }

    @Test
    fun aRefusedConnectionOnlyBlamesTheNetworkWhenThereIsNone() {
        val refused = failing(java.net.ConnectException("refused"))
        assertEquals(
            DictionaryImportProblemKind.DOWNLOAD_OFFLINE,
            problemOf {
                DictionaryLinkFetcher(
                    refused,
                    online = { false },
                ).fetch("https://a.example/x.dict.yaml", directory)
            },
        )
        assertEquals(
            DictionaryImportProblemKind.DOWNLOAD_FAILED,
            problemOf {
                DictionaryLinkFetcher(
                    refused,
                    online = { true },
                ).fetch("https://a.example/x.dict.yaml", directory)
            },
        )
    }

    @Test
    fun serverErrorsCarryTheirStatusAndAMissingFileIsItsOwnProblem() {
        val server = Server(mapOf("https://a.example/x.dict.yaml" to Response(503)))
        val error =
            runCatching {
                DictionaryLinkFetcher(server).fetch("https://a.example/x.dict.yaml", directory)
            }.exceptionOrNull()
        assertEquals(DictionaryImportProblemKind.DOWNLOAD_SERVER, (error as DictionaryImportProblemException).kind)
        assertEquals("503", error.detail)
        assertEquals(
            DictionaryImportProblemKind.DOWNLOAD_NOT_FOUND,
            problemOf { DictionaryLinkFetcher(Server(emptyMap())).fetch("https://a.example/y.dict.yaml", directory) },
        )
    }

    @Test
    fun aSingleFileIsDownloadedWithinItsLimit() {
        val server = Server(mapOf("https://a.example/model.gram" to Response(200, "0123456789")))
        val target = File(directory, "out/model.bin")
        val size = DictionaryLinkFetcher(server).fetchFile("https://a.example/model.gram", target, maxBytes = 100)
        assertEquals(10L, size)
        assertEquals("0123456789", target.readText())
    }

    @Test
    fun aModelFileReportsWhatArrivedAndGetsMoreThanTheDictionaryTimeLimit() {
        // A 400 MB model on a slow connection takes far longer than a dictionary may.
        var now = 0L
        val slow =
            HttpTransport {
                now += 300_000
                Response(200, "0123456789")
            }
        val reports = mutableListOf<Pair<Long, Long?>>()
        val size =
            DictionaryLinkFetcher(slow, totalTimeMs = 180_000, clock = { now })
                .fetchFile("https://a.example/model.gram", File(directory, "m.bin"), maxBytes = 100) { bytes, total ->
                    reports += bytes to total
                }
        assertEquals(10L, size)
        assertEquals(10L to 10L, reports.last())
    }

    @Test
    fun aFileOverItsLimitIsRefusedNotTruncated() {
        val server = Server(mapOf("https://a.example/model.gram" to Response(200, "0123456789")))
        val target = File(directory, "model.bin")
        assertEquals(
            DictionaryImportProblemKind.FILE_TOO_LARGE,
            problemOf { DictionaryLinkFetcher(server).fetchFile("https://a.example/model.gram", target, maxBytes = 5) },
        )
    }

    @Test
    fun aSingleFileLinkMustBeHttpsAndNeedsNoDictionaryName() {
        val server = Server(emptyMap())
        assertEquals(
            DictionaryImportProblemKind.UNSUPPORTED_URL,
            problemOf {
                DictionaryLinkFetcher(server).fetchFile("http://a.example/m.gram", File(directory, "x"), maxBytes = 5)
            },
        )
        assertEquals(emptyList<String>(), server.requested)
    }

    @Test
    fun aMissingSingleFileIsReportedAsMissing() {
        assertEquals(
            DictionaryImportProblemKind.DOWNLOAD_NOT_FOUND,
            problemOf {
                DictionaryLinkFetcher(Server(emptyMap())).fetchFile("https://a.example/m.gram", File(directory, "x"), 5)
            },
        )
    }

    @Test
    fun aGithubRepositoryAddressBecomesItsZipOnCodeload() {
        fun archive(url: String) = DictionaryLinkFetcher.normalizeArchive(url)?.toString()

        assertEquals(
            "https://codeload.github.com/iDvel/rime-ice/zip/HEAD",
            archive("https://github.com/iDvel/rime-ice"),
        )
        assertEquals(
            "https://codeload.github.com/iDvel/rime-ice/zip/HEAD",
            archive(" https://www.github.com/iDvel/rime-ice.git "),
        )
        assertEquals(
            "https://codeload.github.com/o/r/zip/feature/x",
            archive("https://github.com/o/r/tree/feature/x"),
        )
        // Release assets and every other host are downloaded as given.
        val release = "https://github.com/amzxyz/rime-wanxiang/releases/download/v18.1.3/rime-wanxiang-base.zip"
        assertEquals(release, archive(release))
        assertEquals("https://example.com/pkg.zip?x=1", archive("https://example.com/pkg.zip?x=1"))
    }

    @Test
    fun aSchemeLinkMustBeHttpsWithoutCredentials() {
        assertNull(DictionaryLinkFetcher.normalizeArchive("http://example.com/pkg.zip"))
        assertNull(DictionaryLinkFetcher.normalizeArchive("https://user:secret@example.com/pkg.zip"))
        assertNull(DictionaryLinkFetcher.normalizeArchive("https://"))
        assertNull(DictionaryLinkFetcher.normalizeArchive("ftp://example.com/pkg.zip"))
    }

    @Test
    fun anArchiveMustStartLikeAZipAndStaysWithinItsSize() {
        val zip = Response(200, "PK\u0003\u0004 archive bytes")
        val target = File(directory, "package.zip")
        assertEquals(
            zip.contentLength,
            DictionaryLinkFetcher(Server(mapOf("https://a.example/p.zip" to zip)))
                .fetchArchive("https://a.example/p.zip", target, maxBytes = 1000),
        )

        val page = File(directory, "page.zip")
        assertEquals(
            DictionaryImportProblemKind.NOT_ZIP,
            problemOf {
                DictionaryLinkFetcher(Server(mapOf("https://a.example/p.zip" to Response(200, "<html>"))))
                    .fetchArchive("https://a.example/p.zip", page, maxBytes = 1000)
            },
        )
        assertEquals("a refused download leaves no file", false, page.exists())

        assertEquals(
            DictionaryImportProblemKind.FILE_TOO_LARGE,
            problemOf {
                DictionaryLinkFetcher(Server(mapOf("https://a.example/p.zip" to Response(200, "PK0123456789"))))
                    .fetchArchive("https://a.example/p.zip", File(directory, "big.zip"), maxBytes = 5)
            },
        )
    }

    @Test
    fun anArchiveDownloadAllowsMoreTimeThanADictionary() {
        var calls = 0
        val server = Server(mapOf("https://a.example/p.zip" to Response(200, "PK ok")))
        // The deadline is set at time 0; every later check happens 10 minutes in. A dictionary
        // download (3 minutes) would have timed out by then.
        val fetcher = DictionaryLinkFetcher(server, clock = { if (calls++ == 0) 0L else 10 * 60_000L })

        assertEquals(5L, fetcher.fetchArchive("https://a.example/p.zip", File(directory, "p.zip"), maxBytes = 100))
    }
}
