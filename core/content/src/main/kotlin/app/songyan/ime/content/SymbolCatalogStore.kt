package app.songyan.ime.content

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Where the active symbol catalog came from: the bundled default or the user's imported file. */
enum class SymbolCatalogSource {
    BUNDLED,
    CUSTOM,
}

/** The active catalog together with the source it was actually loaded from. */
data class ActiveSymbolCatalog(
    val catalog: SymbolCatalog,
    val source: SymbolCatalogSource,
)

/**
 * The symbol catalog the keyboard panel shows: a user-imported catalog if there is one,
 * otherwise the bundled default.
 *
 * The directory is injected by the caller (the Android layout is `filesDir/content/symbols`);
 * this type never guesses a path and never touches the Android APIs. The caller owns the write
 * side, so a read-only IME process can load the same directory.
 *
 * Discipline copied from the iOS store: an import validates the whole document first and only
 * then replaces the active file atomically, so a rejected import leaves the previous catalog
 * untouched; a corrupt custom catalog falls back to the bundled default while reporting the
 * read error, and the bundled default itself is never written.
 */
class SymbolCatalogStore(
    val directory: Path,
) {
    /** The catalog currently shown for a corrupt custom catalog is the bundled default. */
    val activeFile: Path
        get() = directory.resolve(ACTIVE_FILE_NAME)

    /** Whether a custom catalog exists (the settings screen shows "custom library imported"). */
    val hasCustomCatalog: Boolean
        get() = Files.isRegularFile(activeFile)

    /**
     * Imports a catalog document. Writes to disk only when the whole document validates; on
     * failure it throws and the old catalog stays.
     */
    fun importCatalog(bytes: ByteArray): SymbolCatalog = install(SymbolCatalog.decode(bytes))

    /** Same as [importCatalog], read from a user file within the format byte budget. */
    fun importCatalogFile(path: Path): SymbolCatalog = install(SymbolCatalog.decodeFile(path))

    private fun install(catalog: SymbolCatalog): SymbolCatalog {
        BoundedFile.writeAtomically(activeFile, catalog.encode())
        return catalog
    }

    /** Deletes the custom catalog, reverting to the bundled default. */
    fun removeCustomCatalog() {
        try {
            Files.deleteIfExists(activeFile)
        } catch (error: IOException) {
            throw ContentException(ContentErrorCode.STORAGE_UNAVAILABLE, ACTIVE_FILE_NAME, error)
        }
    }

    /** The active catalog, see [loadActiveWithSource]. */
    fun loadActive(onError: ((ContentException) -> Unit)? = null): SymbolCatalog = loadActiveWithSource(onError).catalog

    /**
     * The active catalog: custom first, bundled default when the custom one is missing or
     * corrupt.
     *
     * A corrupt custom catalog is reported through [onError] and the bundled default is
     * returned, because refusing to render the symbol panel would be worse than showing the
     * built-in set. A missing bundled default is a packaging error and yields an empty
     * catalog: the panel shows an empty state rather than fabricated items.
     *
     * [ActiveSymbolCatalog.source] reports what is shown, not what exists on disk: a corrupt
     * import falls back to the bundled catalog, and a UI that localizes built-in names must not
     * treat that fallback as authored text.
     */
    fun loadActiveWithSource(onError: ((ContentException) -> Unit)? = null): ActiveSymbolCatalog {
        if (Files.exists(activeFile)) {
            try {
                return ActiveSymbolCatalog(SymbolCatalog.decodeFile(activeFile), SymbolCatalogSource.CUSTOM)
            } catch (error: ContentException) {
                onError?.invoke(error)
            }
        }
        val bundled =
            try {
                SymbolCatalog.bundledDefault()
            } catch (error: ContentException) {
                onError?.invoke(error)
                SymbolCatalog()
            }
        return ActiveSymbolCatalog(bundled, SymbolCatalogSource.BUNDLED)
    }

    /**
     * Exports the active catalog for backup or migration. A corrupt custom catalog makes the
     * export fail instead of silently exporting the bundled default over the user's file.
     */
    fun exportActive(): ByteArray {
        var failure: ContentException? = null
        val catalog = loadActive { failure = it }
        failure?.let { throw it }
        return catalog.encode()
    }

    companion object {
        /** Recommended directory name below the app's content directory. */
        const val DIRECTORY_NAME: String = "symbols"

        /** File name of the imported catalog. */
        const val ACTIVE_FILE_NAME: String = "catalog.json"
    }
}
