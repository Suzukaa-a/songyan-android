package app.songyan.ime.settings.state

import android.graphics.Typeface
import android.net.Uri
import app.songyan.ime.appearance.font.FontRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** One imported font file; [family] is null when the file can no longer be read. */
data class FontEntry(
    val file: String,
    val family: String?,
) {
    val readable: Boolean get() = family != null
}

/** What an import did: the family names added, and the names of the files that are not usable fonts. */
data class FontImportResult(
    val imported: List<String> = emptyList(),
    val rejected: List<String> = emptyList(),
    val failed: Boolean = false,
)

/**
 * Imported fonts and the choice of display font for candidate text and key labels. The app module
 * implements it with the files in its own storage; the keyboard reads the same files.
 */
interface FontGateway {
    /** Imported fonts in import order, which is also the order they fill in for missing characters. */
    val fonts: Flow<List<FontEntry>>

    /** The chosen font file for [role]; empty is the system font. */
    fun choice(role: FontRole): Flow<String>

    suspend fun import(uris: List<Uri>): FontImportResult

    suspend fun delete(file: String)

    suspend fun choose(
        role: FontRole,
        file: String,
    )

    /**
     * The typeface the keyboard draws with for the cascade [files] (the chosen font first, as
     * `FontCascade.files` returns them); null for the system font or when the first cannot be
     * opened. Reads files, so call it off the main thread.
     */
    fun previewTypeface(files: List<String>): Typeface?
}

class UnavailableFontGateway : FontGateway {
    override val fonts: Flow<List<FontEntry>> = flowOf(emptyList())

    override fun choice(role: FontRole): Flow<String> = flowOf("")

    override suspend fun import(uris: List<Uri>) = FontImportResult(failed = true)

    override suspend fun delete(file: String) = Unit

    override suspend fun choose(
        role: FontRole,
        file: String,
    ) = Unit

    override fun previewTypeface(files: List<String>): Typeface? = null
}

/** Process-wide holder; the app module installs its implementation once during Application creation. */
object FontGatewayHolder {
    private val fallback = UnavailableFontGateway()

    @Volatile
    private var installed: FontGateway? = null

    val current: FontGateway
        get() = installed ?: fallback

    fun install(gateway: FontGateway) {
        installed = gateway
    }
}
