package app.songyan.ime.content

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** What all quick-content panels show, loaded in one pass so the key area opens them instantly. */
data class QuickContentPanelsData(
    val symbols: SymbolPanelData,
    val phrases: List<CommonPhrase>,
    val clipboard: List<String>,
    val clipboardEnabled: Boolean,
)

/** Everything the symbol panel needs, loaded together so the key area never reads files itself. */
data class SymbolPanelData(
    val catalog: SymbolCatalog,
    val recents: List<String>,
    /** Whether the catalog came from an imported file instead of the bundled default. */
    val customCatalog: Boolean,
)

/**
 * The app process' quick-content data: user phrases, the opt-in clipboard history and the symbol
 * catalog, all below the injected content directory (`filesDir/content`).
 *
 * The stores themselves are the platform-free ones from `:core:content`; this type only owns the
 * paths, keeps the latest state in memory for the keyboard, and moves every file operation onto
 * an IO dispatcher, because the settings screen and the IME share this process and neither may
 * touch disk on the main thread (StrictMode is clean in debug builds).
 *
 * [state] always exists and starts as the disabled, empty state: the recorder reads it on the
 * clipboard callback, so it must never block. Call [refresh] once when the owner starts.
 */
class QuickContentRepository(
    contentDirectory: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val quickContent = LocalQuickContentStore(contentDirectory.toPath())

    /** Shared by the catalog and the recent list, matching the documented layout. */
    private val symbolDirectory = File(contentDirectory, SymbolCatalogStore.DIRECTORY_NAME)

    private val symbolCatalog = SymbolCatalogStore(symbolDirectory.toPath())
    private val recentSymbols = RecentSymbolStore(symbolDirectory.toPath())

    /** Recently inserted emoji: the same MRU store as the symbols, in a directory of its own. */
    private val recentEmoji =
        RecentSymbolStore(File(contentDirectory, EMOJI_DIRECTORY).toPath(), RECENT_EMOJI_LIMIT)

    private val mutableState = MutableStateFlow(QuickContentState())

    /** The last state loaded from storage; clipboard history is off until a load says otherwise. */
    val state: StateFlow<QuickContentState> = mutableState.asStateFlow()

    private val mutableError = MutableStateFlow<ContentErrorCode?>(null)

    /** Why the last operation failed, or null. The UI maps the code to copy and calls [clearError]. */
    val error: StateFlow<ContentErrorCode?> = mutableError.asStateFlow()

    fun clearError() {
        mutableError.value = null
    }

    /** Loads the stored state; a corrupt file falls back to the empty state and reports why. */
    suspend fun refresh(): QuickContentState =
        withContext(io) {
            try {
                quickContent.read().also { mutableState.value = it }
            } catch (error: ContentException) {
                mutableError.value = error.code
                mutableState.value
            }
        }

    suspend fun setClipboardEnabled(enabled: Boolean): QuickContentState =
        mutate { quickContent.setClipboardEnabled(enabled) }

    suspend fun clearClipboard(): QuickContentState = mutate { quickContent.clearClipboard() }

    suspend fun deleteClipboardEntry(text: String): QuickContentState = mutate { quickContent.deleteClipboard(text) }

    /**
     * Stores one captured clipboard text. Returns false when history is off, the text is
     * sensitive, or the store refuses it; a rejection is never surfaced as an error, because the
     * user's copy already succeeded before the keyboard saw it.
     */
    suspend fun recordCapturedClipboardText(
        text: String,
        isSensitive: Boolean = false,
    ): Boolean =
        withContext(io) {
            try {
                val onError: (ContentException) -> Unit = { mutableError.value = it.code }
                val stored = quickContent.recordCapturedClipboardText(text, isSensitive, onError)
                if (stored) mutableState.value = quickContent.read()
                stored
            } catch (error: ContentException) {
                mutableError.value = error.code
                false
            }
        }

    suspend fun savePhrase(phrase: CommonPhrase): QuickContentState = mutate { quickContent.savePhrase(phrase) }

    suspend fun deletePhrase(id: String): QuickContentState = mutate { quickContent.deletePhrase(id) }

    /** The catalog plus recent symbols; a corrupt custom catalog falls back to the bundled one. */
    suspend fun loadSymbols(): SymbolPanelData =
        withContext(io) {
            val loaded = symbolCatalog.loadActiveWithSource { mutableError.value = it.code }
            SymbolPanelData(
                catalog = loaded.catalog,
                recents = recentSymbols.read { mutableError.value = it.code },
                // The actual source, not whether a file exists: a corrupt import shows the bundled catalog.
                customCatalog = loaded.source == SymbolCatalogSource.CUSTOM,
            )
        }

    /**
     * Loads everything the panels render in one IO pass: the keyboard shows a panel the moment the
     * user taps the key instead of loading its lists after the fact.
     */
    suspend fun loadPanelsData(): QuickContentPanelsData =
        withContext(io) {
            val content =
                try {
                    quickContent.read().also { mutableState.value = it }
                } catch (error: ContentException) {
                    mutableError.value = error.code
                    mutableState.value
                }
            QuickContentPanelsData(
                symbols = loadSymbols(),
                phrases = content.phrases,
                clipboard = content.clipboard,
                clipboardEnabled = content.clipboardEnabled,
            )
        }

    /** Records a symbol the user inserted; the panel shows it in the recent row next time. */
    suspend fun recordRecentSymbol(text: String): List<String> =
        withContext(io) {
            val update = recentSymbols.record(text)
            update.error?.let { mutableError.value = it.code }
            update.texts
        }

    suspend fun loadRecentEmoji(): List<String> = withContext(io) { recentEmoji.read { mutableError.value = it.code } }

    /** Records an emoji the user inserted, with the skin tone they chose. */
    suspend fun recordRecentEmoji(text: String): List<String> =
        withContext(io) {
            val update = recentEmoji.record(text)
            update.error?.let { mutableError.value = it.code }
            update.texts
        }

    suspend fun clearRecentSymbols() {
        withContext(io) {
            try {
                recentSymbols.clear()
            } catch (error: ContentException) {
                mutableError.value = error.code
            }
        }
    }

    private suspend fun mutate(body: () -> QuickContentState): QuickContentState =
        withContext(io) {
            try {
                body().also { mutableState.value = it }
            } catch (error: ContentException) {
                mutableError.value = error.code
                mutableState.value
            }
        }

    private companion object {
        const val EMOJI_DIRECTORY = "emoji"
        const val RECENT_EMOJI_LIMIT = 36
    }
}
