package app.songyan.ime.deploy.net

import app.songyan.ime.content.DictionaryImportFiles
import app.songyan.ime.content.DictionaryImportProblemException
import app.songyan.ime.content.DictionaryImportProblemKind
import app.songyan.ime.deploy.DownloadProgress
import app.songyan.ime.packages.DictionaryImportBuilder
import app.songyan.ime.packages.DictionaryImportError
import app.songyan.ime.packages.DictionarySourceFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException

/** One HTTP response as the fetcher needs it; abstracted so redirects and limits are JVM-testable. */
interface HttpResponse : AutoCloseable {
    val status: Int
    val location: String?

    /** -1 when the server does not say. */
    val contentLength: Long
    val body: InputStream
}

fun interface HttpTransport {
    fun get(uri: URI): HttpResponse
}

/**
 * Downloads a dictionary and every dictionary it imports (`import_tables`) from HTTPS links.
 *
 * Runs only in the :deploy process: the input method and settings live in the default
 * process, which never opens a network connection. Plain HTTP is refused at every hop, because the
 * user cannot tell whether such a download was tampered with and a dictionary decides what the
 * keyboard suggests. Files stream to disk under the same caps the importer applies, so a large or
 * endless response never sits in memory.
 */
class DictionaryLinkFetcher(
    private val transport: HttpTransport = UrlConnectionTransport(),
    private val maxRedirects: Int = 5,
    private val totalTimeMs: Long = 180_000L,
    /** A scheme package or a model file is hundreds of megabytes; the stall limit is the transport's read timeout. */
    private val archiveTotalTimeMs: Long = 15L * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Whether the device has a network connection; tells "no such domain" from "no network". */
    private val online: () -> Boolean = { true },
) {
    /**
     * Fetches [url] and its dependencies into [directory] and returns them as importer input, the
     * linked dictionary first. Dependency links resolve against the linked file's own directory,
     * the way Rime resolves `import_tables` names against its data directory.
     */
    fun fetch(
        url: String,
        directory: File,
        cancelled: () -> Boolean = { false },
    ): List<DictionarySourceFile> {
        val root = normalize(url) ?: throw problem(DictionaryImportProblemKind.UNSUPPORTED_URL)
        val rootName = root.path.orEmpty().substringAfterLast('/')
        if (!DictionaryImportFiles.isSafeDictionaryName(rootName)) {
            throw problem(DictionaryImportProblemKind.UNSUPPORTED_URL_FILE)
        }
        val deadline = clock() + totalTimeMs
        val fetched = ArrayList<DictionarySourceFile>()
        val seen = HashSet<String>()
        val pending = ArrayDeque<Pair<String, URI>>()
        pending += rootName to root
        seen += rootName
        var budget = DictionaryImportBuilder.MAX_BYTE_COUNT
        while (pending.isNotEmpty()) {
            val (path, uri) = pending.removeFirst()
            if (fetched.size >= DictionaryImportBuilder.MAX_FILE_COUNT) {
                throw problem(DictionaryImportProblemKind.TOO_MANY_FILES)
            }
            val target = File(directory, path)
            target.parentFile?.mkdirs()
            budget -= download(uri, target, budget, deadline, cancelled)
            val source = DictionarySourceFile(path, target)
            fetched += source
            val dependencies =
                try {
                    DictionaryImportBuilder.inspect(source).dependencies
                } catch (_: DictionaryImportError) {
                    // The importer reports the real reason with the whole set; nothing more to fetch.
                    emptyList()
                }
            for (name in dependencies) {
                val dependencyPath =
                    if (DictionaryImportFiles.isDictionaryName(
                            name,
                        )
                    ) {
                        name
                    } else {
                        DictionaryImportBuilder.dependencyPath(name)
                    }
                if (!DictionaryImportFiles.isSafeDictionaryName(dependencyPath) || !seen.add(dependencyPath)) continue
                pending += dependencyPath to root.resolve(encodePath(dependencyPath))
            }
        }
        return fetched
    }

    /**
     * Downloads the single file at [url] into [target], at most [maxBytes], with the redirect and
     * HTTPS-only rules of a dictionary download. A model file is hundreds of megabytes, so it gets
     * the scheme package's time limit. Returns its size.
     */
    fun fetchFile(
        url: String,
        target: File,
        maxBytes: Long,
        cancelled: () -> Boolean = { false },
        progress: DownloadProgress = DownloadProgress.NONE,
    ): Long {
        val uri = normalize(url) ?: throw problem(DictionaryImportProblemKind.UNSUPPORTED_URL)
        target.parentFile?.mkdirs()
        return download(uri, target, maxBytes, clock() + archiveTotalTimeMs, cancelled, progress)
    }

    /**
     * Downloads a scheme package ZIP from [url] into [target], at most [maxBytes]. A GitHub
     * repository address is rewritten to that repository's ZIP on codeload.github.com. The file
     * must start with the ZIP signature; an HTML page behind a wrong link is refused before any
     * import step reads it. Returns its size.
     */
    fun fetchArchive(
        url: String,
        target: File,
        maxBytes: Long,
        cancelled: () -> Boolean = { false },
        progress: DownloadProgress = DownloadProgress.NONE,
    ): Long {
        val uri = normalizeArchive(url) ?: throw problem(DictionaryImportProblemKind.UNSUPPORTED_URL)
        target.parentFile?.mkdirs()
        val size = download(uri, target, maxBytes, clock() + archiveTotalTimeMs, cancelled, progress)
        if (!startsWithZipSignature(target)) {
            target.delete()
            throw problem(DictionaryImportProblemKind.NOT_ZIP)
        }
        return size
    }

    private fun startsWithZipSignature(file: File): Boolean =
        try {
            file.inputStream().use { it.read() == 'P'.code && it.read() == 'K'.code }
        } catch (_: IOException) {
            false
        }

    /** Streams one file into [target]; returns its size. */
    private fun download(
        start: URI,
        target: File,
        budget: Long,
        deadline: Long,
        cancelled: () -> Boolean,
        progress: DownloadProgress = DownloadProgress.NONE,
    ): Long {
        var current = start
        var redirects = 0
        while (true) {
            checkTime(deadline, cancelled)
            val response =
                try {
                    transport.get(current)
                } catch (e: SocketTimeoutException) {
                    throw problem(DictionaryImportProblemKind.DOWNLOAD_TIMEOUT, e)
                } catch (e: IOException) {
                    throw problem(classify(e), e)
                }
            response.use {
                if (it.status in 300..399) {
                    val location = it.location
                    if (location.isNullOrBlank() || redirects >= maxRedirects) {
                        throw problem(DictionaryImportProblemKind.TOO_MANY_REDIRECTS)
                    }
                    redirects++
                    val next =
                        try {
                            current.resolve(location.trim())
                        } catch (e: IllegalArgumentException) {
                            throw problem(DictionaryImportProblemKind.DOWNLOAD_FAILED, e)
                        }
                    if (!next.scheme.equals("https", ignoreCase = true)) {
                        throw problem(DictionaryImportProblemKind.INSECURE_REDIRECT)
                    }
                    current = next
                    return@use
                }
                if (it.status == HttpURLConnection.HTTP_NOT_FOUND || it.status == HTTP_GONE) {
                    throw problem(DictionaryImportProblemKind.DOWNLOAD_NOT_FOUND)
                }
                if (it.status != HttpURLConnection.HTTP_OK) {
                    throw problem(DictionaryImportProblemKind.DOWNLOAD_SERVER, detail = it.status.toString())
                }
                if (it.contentLength > budget) throw problem(DictionaryImportProblemKind.FILE_TOO_LARGE)
                val expected = it.contentLength.takeIf { length -> length > 0 }
                return copyCapped(
                    it.body,
                    target,
                    budget,
                    deadline,
                    cancelled,
                ) { bytes -> progress.update(bytes, expected) }
            }
        }
    }

    private fun copyCapped(
        input: InputStream,
        target: File,
        budget: Long,
        deadline: Long,
        cancelled: () -> Boolean,
        copied: (Long) -> Unit,
    ): Long {
        var total = 0L
        val buffer = ByteArray(BUFFER_BYTES)
        try {
            FileOutputStream(target).use { output ->
                while (true) {
                    checkTime(deadline, cancelled)
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > budget) throw problem(DictionaryImportProblemKind.FILE_TOO_LARGE)
                    output.write(buffer, 0, read)
                    copied(total)
                }
            }
        } catch (e: SocketTimeoutException) {
            throw problem(DictionaryImportProblemKind.DOWNLOAD_TIMEOUT, e)
        } catch (e: DictionaryImportProblemException) {
            throw e
        } catch (e: IOException) {
            throw problem(DictionaryImportProblemKind.DOWNLOAD_FAILED, e)
        }
        return total
    }

    private fun checkTime(
        deadline: Long,
        cancelled: () -> Boolean,
    ) {
        if (cancelled()) throw problem(DictionaryImportProblemKind.CANCELLED)
        if (clock() > deadline) throw problem(DictionaryImportProblemKind.DOWNLOAD_TIMEOUT)
    }

    /** A failed connection attempt, told apart by what the user can do about it. */
    private fun classify(error: IOException): DictionaryImportProblemKind {
        val offline = DictionaryImportProblemKind.DOWNLOAD_OFFLINE
        return when (error) {
            is UnknownHostException -> if (online()) DictionaryImportProblemKind.DOWNLOAD_HOST else offline
            is ConnectException, is NoRouteToHostException ->
                if (online()) DictionaryImportProblemKind.DOWNLOAD_FAILED else offline

            else -> DictionaryImportProblemKind.DOWNLOAD_FAILED
        }
    }

    private fun problem(
        kind: DictionaryImportProblemKind,
        cause: Throwable? = null,
        detail: String? = null,
    ) = DictionaryImportProblemException(kind, cause, detail)

    companion object {
        private const val BUFFER_BYTES = 64 * 1024
        private const val HTTP_GONE = 410
        private val GITHUB_FILE = Regex("^/([^/]+)/([^/]+)/(?:blob|raw)/(.+)$")

        /**
         * An HTTPS link the fetcher can download, or null. A GitHub page link (`/blob/`) names the
         * HTML page that shows the file; the raw content lives on raw.githubusercontent.com.
         */
        fun normalize(url: String): URI? {
            val uri =
                try {
                    URI(url.trim())
                } catch (_: URISyntaxException) {
                    return null
                }
            if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return null
            val host = uri.host.lowercase()
            if (host == "github.com" || host == "www.github.com") {
                val match = GITHUB_FILE.matchEntire(uri.rawPath.orEmpty()) ?: return uri
                val (owner, repository, rest) = match.destructured
                return URI("https://raw.githubusercontent.com/$owner/$repository/$rest")
            }
            return uri
        }

        /**
         * The link a scheme package is downloaded from, or null when it is not an HTTPS link without
         * embedded credentials. `github.com/<owner>/<repo>` and `.../tree/<ref>` become the
         * repository's ZIP on codeload.github.com; every other link is kept as it is.
         */
        fun normalizeArchive(url: String): URI? {
            val uri =
                try {
                    URI(url.trim())
                } catch (_: URISyntaxException) {
                    return null
                }
            if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() || uri.userInfo != null) {
                return null
            }
            val host = uri.host.lowercase()
            if (host != "github.com" && host != "www.github.com") return uri
            val parts =
                uri.path
                    .orEmpty()
                    .split('/')
                    .filter { it.isNotEmpty() }
            if (parts.size != 2 && !(parts.size >= 4 && parts[2] == "tree")) return uri
            val repository = parts[1].removeSuffix(".git")
            val ref = if (parts.size >= 4) parts.drop(3).joinToString("/") else "HEAD"
            return try {
                URI("https", "codeload.github.com", "/" + parts[0] + "/" + repository + "/zip/" + ref, null)
            } catch (_: URISyntaxException) {
                null
            }
        }

        private fun encodePath(path: String): String = URI(null, null, path, null).rawPath
    }
}

/** The platform HTTP stack; redirects are followed by the fetcher so every hop is re-checked. */
class UrlConnectionTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 20_000,
) : HttpTransport {
    override fun get(uri: URI): HttpResponse {
        val connection =
            try {
                uri.toURL().openConnection() as HttpURLConnection
            } catch (e: ClassCastException) {
                throw IOException("not an HTTP link", e)
            } catch (e: IllegalArgumentException) {
                throw IOException("unusable link", e)
            }
        connection.instanceFollowRedirects = false
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "text/plain, application/yaml, */*")
        // Some hosts answer a bare connection with 403; a plain product token is honest.
        connection.setRequestProperty("User-Agent", "SongyanIME")
        val status =
            try {
                connection.responseCode
            } catch (e: IOException) {
                connection.disconnect()
                throw e
            }
        return object : HttpResponse {
            override val status: Int = status
            override val location: String? = connection.getHeaderField("Location")
            override val contentLength: Long = connection.contentLengthLong
            override val body: InputStream by lazy { connection.inputStream }

            override fun close() {
                connection.disconnect()
            }
        }
    }
}
