package app.songyan.ime.script

import java.net.HttpURLConnection
import java.net.URL

/** Downloads a script link once, at import or update time. HTTPS only, bounded size, one redirect
 *  hop at most - the import path is not a browser. */
internal object ScriptFetcher {
    /** A script larger than this could not be imported anyway. */
    const val MAX_BYTES = ScriptLimits.SOURCE_BYTES.toInt()

    fun download(
        url: String,
        followRedirects: Int = 2,
    ): ByteArray {
        require(url.startsWith("https://")) { "script links must be HTTPS: " + url }
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = false
        try {
            when (connection.responseCode / 100) {
                2 -> {
                    // Content-Length is absent for chunked and transparently gunzipped responses (raw
                    // GitHub files, gists), so the bounded read below is the only size check.
                    val size = connection.contentLengthLong
                    require(size <= MAX_BYTES.toLong()) { "the script is too large: " + size }
                    return connection.inputStream.use { stream ->
                        // InputStream.readNBytes needs API 33; read with a bound of our own.
                        val buffer = ByteArray(MAX_BYTES + 1)
                        var filled = 0
                        while (filled < buffer.size) {
                            val read = stream.read(buffer, filled, buffer.size - filled)
                            if (read < 0) break
                            filled += read
                        }
                        require(filled <= MAX_BYTES) { "the script is too large" }
                        buffer.copyOf(filled)
                    }
                }
                3 -> {
                    val location =
                        connection.getHeaderField("Location")
                            ?: error("a redirect without a Location header")
                    require(followRedirects > 0) { "too many redirects" }
                    return download(location, followRedirects - 1)
                }
                else -> error("HTTP " + connection.responseCode)
            }
        } finally {
            connection.disconnect()
        }
    }
}
