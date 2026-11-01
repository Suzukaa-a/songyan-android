package app.songyan.ime

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/**
 * Reads a scheme package out of an intent another app sent (open or share a ZIP). Only content
 * URIs count: the sender grants read access to exactly that document, while a file path would
 * be read with this app's own permissions.
 */
internal object IncomingPackageIntents {
    private val ZIP_TYPES = setOf("application/zip", "application/x-zip-compressed")

    fun packageUri(intent: Intent?): Uri? {
        intent ?: return null
        val uri =
            when (intent.action) {
                Intent.ACTION_VIEW -> intent.data
                Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                else -> null
            } ?: return null
        if (uri.scheme != "content") return null
        val type = intent.type?.lowercase()
        return uri.takeIf { type == null || type in ZIP_TYPES }
    }
}
