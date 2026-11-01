package app.songyan.ime.storage

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Up to [limit] bytes of the stream. A caller that reads one byte more than its budget learns the
 * input is oversize without ever holding the rest of it. (`readNBytes` needs API 33.)
 */
fun InputStream.readAtMost(limit: Int): ByteArray {
    val out = ByteArrayOutputStream(minOf(limit, BUFFER))
    val buffer = ByteArray(BUFFER)
    var total = 0
    while (total < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - total))
        if (read < 0) break
        out.write(buffer, 0, read)
        total += read
    }
    return out.toByteArray()
}

private const val BUFFER = 8 * 1024
