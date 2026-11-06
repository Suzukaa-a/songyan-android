package app.songyan.ime.ui

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import java.util.Locale

/**
 * Reads a keyboard-tool file out of an intent another app sent (open or share).
 *
 * Only content URIs count: the sender grants read access to exactly that document, while a file
 * path would be read with this app's own permissions. This only decides whether the settings page
 * should offer the document; the tool format validates it before anything is imported.
 */
object ToolImportIntents {
    /** MIME types a .songyantool.json file can arrive with; the format checks the content. */
    private val TOOL_TYPES =
        setOf(
            "application/json",
            "application/octet-stream",
            "text/plain",
            "application/vnd.songyan.tool+json",
        )

    fun toolUri(intent: Intent?): Uri? {
        intent ?: return null
        val uri =
            when (intent.action) {
                Intent.ACTION_VIEW -> intent.data
                Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                else -> null
            } ?: return null
        if (uri.scheme != "content") return null
        val type = intent.type?.lowercase(Locale.ROOT)
        return uri.takeIf { type == null || type in TOOL_TYPES }
    }
}
