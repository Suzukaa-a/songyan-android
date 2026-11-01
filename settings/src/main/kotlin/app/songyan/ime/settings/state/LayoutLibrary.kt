package app.songyan.ime.settings.state

import android.net.Uri
import app.songyan.ime.layout.KeyboardPageCategory
import app.songyan.ime.layout.LayoutException
import app.songyan.ime.layout.LayoutRegistry
import app.songyan.ime.layout.SymbolPageCategories
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What one edit of the catalog did. */
sealed interface LayoutEditResult {
    data object Saved : LayoutEditResult

    /** The catalog refused the edit (or could not be written); nothing changed. */
    data class Rejected(
        val error: LayoutException,
    ) : LayoutEditResult
}

/** What importing a layout file did. */
sealed interface LayoutImportOutcome {
    /** The layout is in the catalog as [name]; [copied] when its id was taken and it got a new one. */
    data class Imported(
        val name: String,
        val copied: Boolean,
    ) : LayoutImportOutcome

    data class Rejected(
        val error: LayoutException,
    ) : LayoutImportOutcome
}

/**
 * Bridge to the app module's layout catalog: settings reads [registry] and writes through [edit].
 * The app owns persistence and the hand-off to the keyboard, so settings never touches layout files.
 */
interface LayoutLibrarySource {
    /** The catalog; null until the first load has finished. */
    val registry: StateFlow<LayoutRegistry?>

    /** Reads the stored catalog again. */
    fun refresh()

    /**
     * Applies [change] to the current catalog, validates and stores the result, and hands it to the
     * keyboard. A rejected edit leaves the stored catalog as it was.
     */
    suspend fun edit(change: (LayoutRegistry) -> LayoutRegistry): LayoutEditResult

    /** Reads a layout file the user picked and adds it; an id already in use gets a fresh one. */
    suspend fun importFrom(uri: Uri): LayoutImportOutcome

    /** Writes the layout [id] to the document the user chose; false when it could not be written. */
    suspend fun exportTo(
        id: String,
        uri: Uri,
    ): Boolean

    /**
     * The categories the built-in categorized symbol page shows right now (the symbol library and the
     * recent symbols), which a copy of that page starts from. Read off the main thread.
     */
    suspend fun symbolPageCategories(): List<KeyboardPageCategory> =
        SymbolPageCategories.snapshot(emptyList(), emptyList(), emptyList())

    /** Where the layout [id] is exported by default. */
    fun exportFileName(id: String): String = "$id.json"
}

/** Fallback before the app module supplies the real source: an in-memory catalog with no file access. */
class InMemoryLayoutLibrarySource(
    initial: LayoutRegistry = LayoutRegistry.create(),
) : LayoutLibrarySource {
    private val mutable = MutableStateFlow<LayoutRegistry?>(initial)
    override val registry: StateFlow<LayoutRegistry?> = mutable.asStateFlow()

    override fun refresh() = Unit

    override suspend fun edit(change: (LayoutRegistry) -> LayoutRegistry): LayoutEditResult =
        try {
            mutable.value = change(mutable.value ?: LayoutRegistry.create())
            LayoutEditResult.Saved
        } catch (error: LayoutException) {
            LayoutEditResult.Rejected(error)
        }

    override suspend fun importFrom(uri: Uri): LayoutImportOutcome =
        LayoutImportOutcome.Rejected(
            LayoutException.Invalid(app.songyan.ime.layout.LayoutInvalidCode.UNREADABLE_SOURCE),
        )

    override suspend fun exportTo(
        id: String,
        uri: Uri,
    ): Boolean = false
}

/** Process-wide holder for the app-provided source, mirroring [AppearanceSettingsHolder]. */
object LayoutLibraryHolder {
    private val fallback = InMemoryLayoutLibrarySource()

    @Volatile
    private var installed: LayoutLibrarySource? = null

    val current: LayoutLibrarySource
        get() = installed ?: fallback

    fun install(source: LayoutLibrarySource) {
        installed = source
    }
}
