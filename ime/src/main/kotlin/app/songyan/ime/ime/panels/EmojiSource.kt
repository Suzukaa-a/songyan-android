package app.songyan.ime.ime.panels

import android.content.Context
import android.graphics.Paint
import androidx.core.graphics.PaintCompat
import androidx.emoji2.text.DefaultEmojiCompatConfig
import androidx.emoji2.text.EmojiCompat
import app.songyan.ime.content.EmojiCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The emoji the keyboard offers: the bundled catalog limited to what this device can draw and, when
 * the editor says which emoji metadata version it can render, to what that editor supports.
 *
 * The catalog is read and filtered once per process on an IO thread; the editor filter is applied per
 * request because it differs between editors. See
 * https://developer.android.com/develop/ui/views/text-and-emoji/emoji2 for the metadata version.
 */
class EmojiSource(
    private val context: Context,
) {
    private var drawable: EmojiCatalog? = null

    /**
     * The catalog for an editor whose renderer understands emoji up to [metadataVersion] (0 when the
     * editor does not say). EmojiCompat is started on first use, never at process start.
     */
    suspend fun catalog(metadataVersion: Int = 0): EmojiCatalog =
        withContext(Dispatchers.IO) {
            val base = drawable ?: loadDrawable().also { drawable = it }
            if (metadataVersion <= 0) return@withContext base
            ensureCompat(context)
            val compat = readyCompat() ?: return@withContext base
            base.filtered { emoji -> compat.getEmojiMatch(emoji, metadataVersion) != EmojiCompat.EMOJI_UNSUPPORTED }
        }

    /** Forgets the parsed catalog; the next request reads and filters it again. */
    fun release() {
        drawable = null
    }

    private fun loadDrawable(): EmojiCatalog {
        val parsed =
            context.assets
                .open(ASSET)
                .bufferedReader()
                .use { EmojiCatalog.parse(it.lineSequence().toList().asSequence()) }
        // The platform font decides: an emoji it cannot draw would show as a box, newer than the OS.
        val paint = Paint()
        return parsed.filtered { PaintCompat.hasGlyph(paint, it) }
    }

    private fun readyCompat(): EmojiCompat? =
        if (EmojiCompat.isConfigured() &&
            EmojiCompat.get().loadState == EmojiCompat.LOAD_STATE_SUCCEEDED
        ) {
            EmojiCompat.get()
        } else {
            // Not loaded (yet): the editor's version cannot be checked, so the device's own font decides.
            null
        }

    private companion object {
        const val ASSET = "emoji/emoji-data.tsv"

        @Synchronized
        fun ensureCompat(context: Context) {
            if (EmojiCompat.isConfigured()) return
            DefaultEmojiCompatConfig.create(context.applicationContext)?.let { EmojiCompat.init(it) }
        }
    }
}
