package app.songyan.ime.appearance

import android.content.Context
import android.net.Uri
import app.songyan.ime.storage.readAtMost
import java.io.IOException

/** Reads a picked appearance file with the format's byte budget, so a huge file never fills memory. */
internal object AppearanceFileReader {
    /**
     * The file's text. A file over [AppearanceDocument.MAX_BYTES] is rejected as oversize after at most
     * one byte more than the budget has been read.
     */
    @Throws(AppearanceError::class, IOException::class)
    fun read(
        context: Context,
        uri: Uri,
    ): String {
        val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("no stream")
        stream.use {
            return decode(it.readAtMost(AppearanceDocument.MAX_BYTES + 1))
        }
    }

    internal fun decode(bytes: ByteArray): String {
        if (bytes.size > AppearanceDocument.MAX_BYTES) throw AppearanceError(AppearanceErrorCode.OVERSIZE, "$")
        return bytes.decodeToString()
    }
}
