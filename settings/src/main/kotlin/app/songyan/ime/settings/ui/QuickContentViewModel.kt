package app.songyan.ime.settings.ui

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.songyan.ime.content.CommonPhrase
import app.songyan.ime.content.ContentErrorCode
import app.songyan.ime.content.ContentException
import app.songyan.ime.content.LocalQuickContentStore
import app.songyan.ime.content.QuickContentEvents
import app.songyan.ime.content.QuickContentState
import app.songyan.ime.content.RecentSymbolStore
import app.songyan.ime.content.SymbolCatalog
import app.songyan.ime.content.SymbolCatalogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** Everything the quick-content screen renders. */
data class QuickContentUiState(
    val loading: Boolean = true,
    val clipboardEnabled: Boolean = false,
    val clipboard: List<String> = emptyList(),
    val phrases: List<CommonPhrase> = emptyList(),
    val symbolItemCount: Int = 0,
    val symbolCategoryCount: Int = 0,
    val customSymbolCatalog: Boolean = false,
    val symbolCatalogError: ContentErrorCode? = null,
    val recentSymbolCount: Int = 0,
    /** Why the last storage operation failed, or null once the next one succeeds. */
    val error: ContentErrorCode? = null,
) {
    val canAddPhrase: Boolean get() = phrases.size < LocalQuickContentStore.MAXIMUM_PHRASES
}

/** The outcome of a file transfer or a reset, shown once as a snackbar. */
sealed interface QuickContentMessage {
    data class PhrasesImported(
        val count: Int,
    ) : QuickContentMessage

    data object PhrasesExported : QuickContentMessage

    data class SymbolsImported(
        val count: Int,
    ) : QuickContentMessage

    data object SymbolsExported : QuickContentMessage

    data object SymbolsRestored : QuickContentMessage

    /** The file cannot be read at all (revoked access, I/O error). */
    data object FileUnreadable : QuickContentMessage

    data class FileTooLarge(
        val maximumBytes: Int,
    ) : QuickContentMessage

    /** The file was read but is not a file of the expected kind; nothing was changed. */
    data object FileRejected : QuickContentMessage

    data object WriteFailed : QuickContentMessage
}

/**
 * State of the 快捷内容 screen: phrases, the opt-in clipboard history and the symbol library
 * summary, read from the same `filesDir/content` stores the keyboard uses.
 *
 * The screen runs in the app process next to the IME, so a write here is visible to the keyboard
 * on its next read; every file operation is on the IO dispatcher because the settings UI must
 * stay off disk on the main thread.
 */
class QuickContentViewModel(
    application: Application,
) : AndroidViewModel(application) {
    /**
     * The stores resolve `filesDir`, which creates the directory on first use, so they are built on
     * the IO dispatcher at first use and never while this view model is constructed on the main thread.
     */
    private class Stores(
        filesDir: File,
    ) {
        val contentDirectory = File(filesDir, LocalQuickContentStore.DIRECTORY_NAME)
        val quickContent = LocalQuickContentStore(contentDirectory.toPath())
        private val symbolDirectory = File(contentDirectory, SymbolCatalogStore.DIRECTORY_NAME)
        val symbols = SymbolCatalogStore(symbolDirectory.toPath())
        val recents = RecentSymbolStore(symbolDirectory.toPath())
    }

    private val stores: Stores by lazy { Stores(application.filesDir) }

    private val mutableState = MutableStateFlow(QuickContentUiState())
    val uiState: StateFlow<QuickContentUiState> = mutableState.asStateFlow()

    private val mutableMessage = MutableStateFlow<QuickContentMessage?>(null)
    val message: StateFlow<QuickContentMessage?> = mutableMessage.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    fun consumeMessage() {
        mutableMessage.value = null
    }

    /** Replaces the stored phrases with the archive at [uri]; a rejected file changes nothing. */
    fun importPhrases(
        resolver: ContentResolver,
        uri: Uri,
    ) = transfer {
        val bytes =
            readBounded(resolver, uri, LocalQuickContentStore.MAXIMUM_FILE_BYTES)
                ?: return@transfer QuickContentMessage.FileUnreadable
        if (bytes.size > LocalQuickContentStore.MAXIMUM_FILE_BYTES) {
            return@transfer QuickContentMessage.FileTooLarge(LocalQuickContentStore.MAXIMUM_FILE_BYTES)
        }
        val count =
            stores.quickContent
                .importPhrases(bytes)
                .phrases.size
        notifyContentChanged()
        QuickContentMessage.PhrasesImported(count)
    }

    fun exportPhrases(
        resolver: ContentResolver,
        uri: Uri,
    ) = transfer {
        if (!write(resolver, uri, stores.quickContent.exportPhrases())) return@transfer QuickContentMessage.WriteFailed
        QuickContentMessage.PhrasesExported
    }

    /** Installs the catalog at [uri] as the custom symbol library; a rejected file changes nothing. */
    fun importSymbols(
        resolver: ContentResolver,
        uri: Uri,
    ) = transfer {
        val bytes =
            readBounded(resolver, uri, SymbolCatalog.MAX_JSON_BYTES)
                ?: return@transfer QuickContentMessage.FileUnreadable
        if (bytes.size > SymbolCatalog.MAX_JSON_BYTES) {
            return@transfer QuickContentMessage.FileTooLarge(SymbolCatalog.MAX_JSON_BYTES)
        }
        val count =
            stores.symbols
                .importCatalog(bytes)
                .items.size
        notifyContentChanged()
        QuickContentMessage.SymbolsImported(count)
    }

    fun exportSymbols(
        resolver: ContentResolver,
        uri: Uri,
    ) = transfer {
        if (!write(resolver, uri, stores.symbols.exportActive())) return@transfer QuickContentMessage.WriteFailed
        QuickContentMessage.SymbolsExported
    }

    /** Removes the custom symbol library so the built-in one is shown again. */
    fun restoreDefaultSymbols() =
        transfer {
            stores.symbols.removeCustomCatalog()
            notifyContentChanged()
            QuickContentMessage.SymbolsRestored
        }

    /** Runs a file operation off the main thread, then reloads the screen and reports the outcome. */
    private fun transfer(operation: () -> QuickContentMessage) {
        viewModelScope.launch {
            val result =
                try {
                    io(operation)
                } catch (error: ContentException) {
                    when (error.code) {
                        ContentErrorCode.STORAGE_UNAVAILABLE,
                        ContentErrorCode.LOCK_FAILED,
                        -> QuickContentMessage.WriteFailed

                        else -> QuickContentMessage.FileRejected
                    }
                }
            load()
            mutableMessage.value = result
        }
    }

    /** At most [limit] + 1 bytes, so the store can name an oversized file instead of this reading it all. */
    private fun readBounded(
        resolver: ContentResolver,
        uri: Uri,
        limit: Int,
    ): ByteArray? =
        try {
            resolver.openInputStream(uri)?.use { input -> readUpTo(input, limit + 1) }
        } catch (error: IOException) {
            null
        } catch (error: SecurityException) {
            null
        }

    private fun readUpTo(
        input: java.io.InputStream,
        count: Int,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (out.size() < count) {
            val read = input.read(buffer, 0, minOf(buffer.size, count - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun write(
        resolver: ContentResolver,
        uri: Uri,
        bytes: ByteArray,
    ): Boolean =
        try {
            // "wt" truncates: overwriting a longer existing document must not leave its tail behind.
            val stream = resolver.openOutputStream(uri, "wt")
            stream?.use { it.write(bytes) } != null
        } catch (error: IOException) {
            false
        } catch (error: SecurityException) {
            false
        }

    fun refresh() {
        viewModelScope.launch { load() }
    }

    /** Wakes a running keyboard so the new clipboard flag/history applies without a restart. */
    private fun notifyContentChanged() {
        getApplication<Application>().sendBroadcast(
            android.content
                .Intent(QuickContentEvents.ACTION_CHANGED)
                .setPackage(getApplication<Application>().packageName),
        )
    }

    fun setClipboardEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                apply(io { stores.quickContent.setClipboardEnabled(enabled) })
                notifyContentChanged()
            } catch (error: ContentException) {
                report(error)
            }
        }
    }

    fun clearClipboard() {
        viewModelScope.launch {
            try {
                apply(io { stores.quickContent.clearClipboard() })
                notifyContentChanged()
            } catch (error: ContentException) {
                report(error)
            }
        }
    }

    fun deleteClipboardEntry(
        text: String,
        onDeleted: () -> Unit,
    ) {
        viewModelScope.launch {
            try {
                apply(io { stores.quickContent.deleteClipboard(text) })
                notifyContentChanged()
                onDeleted()
            } catch (error: ContentException) {
                report(error)
            }
        }
    }

    /** Checks an editor submission without touching disk, so the dialog can react immediately. */
    fun validatePhrase(
        editingId: String?,
        text: String,
        shortcut: String = "",
    ): PhraseInputError? {
        val state = mutableState.value
        return PhraseValidation.check(
            text = text,
            editingId = editingId,
            phrases = state.phrases,
            canAdd = state.canAddPhrase,
            shortcut = shortcut,
        )
    }

    /**
     * Stores a new phrase or the edited one; the caller has already validated it.
     *
     * [onResult] is called on the main thread with the storage failure, or null once the phrase is
     * stored, so the editor can stay open until the write is known to have succeeded.
     */
    fun savePhrase(
        editingId: String?,
        text: String,
        shortcut: String,
        onResult: (ContentErrorCode?) -> Unit,
    ) {
        val phrase =
            CommonPhrase(
                text = text.trim(),
                shortcut = shortcut.trim(),
                id = editingId ?: UUID.randomUUID().toString(),
            )
        viewModelScope.launch {
            val failure =
                try {
                    apply(io { stores.quickContent.savePhrase(phrase) })
                    null
                } catch (error: ContentException) {
                    report(error)
                    error.code
                }
            onResult(failure)
        }
    }

    fun deletePhrase(
        id: String,
        onDeleted: () -> Unit,
    ) {
        viewModelScope.launch {
            try {
                apply(io { stores.quickContent.deletePhrase(id) })
                onDeleted()
            } catch (error: ContentException) {
                report(error)
            }
        }
    }

    /** Puts a deleted clipboard entry back at the front, where it was copied last. */
    fun restoreClipboardEntry(text: String) {
        viewModelScope.launch {
            try {
                // Only an entry that was in the list can be undone, so history is on; the store
                // refuses the write otherwise, which is the right outcome.
                io { stores.quickContent.recordExplicitPaste(text) }
                load()
                notifyContentChanged()
            } catch (error: ContentException) {
                report(error)
            }
        }
    }

    /** Undo keeps the original order while preserving any newer version on disk. */
    fun restorePhrase(
        phrase: CommonPhrase,
        index: Int,
    ) {
        viewModelScope.launch {
            try {
                apply(io { stores.quickContent.restorePhrase(phrase, index) })
            } catch (error: ContentException) {
                report(error)
            }
        }
    }

    fun clearRecentSymbols() {
        viewModelScope.launch {
            try {
                io { stores.recents.clear() }
                load()
            } catch (error: ContentException) {
                report(error)
            }
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private suspend fun load() {
        withContext(Dispatchers.IO) {
            try {
                val content = stores.quickContent.read()
                var symbolError: ContentErrorCode? = null
                val catalog = stores.symbols.loadActive { symbolError = it.code }
                mutableState.value =
                    stateOf(content).copy(
                        loading = false,
                        symbolItemCount = catalog.items.size,
                        symbolCategoryCount = catalog.categories.size,
                        customSymbolCatalog = stores.symbols.hasCustomCatalog,
                        symbolCatalogError = symbolError,
                        recentSymbolCount = stores.recents.read().size,
                    )
            } catch (error: ContentException) {
                mutableState.value = mutableState.value.copy(loading = false, error = error.code)
            }
        }
    }

    private fun apply(content: QuickContentState) {
        mutableState.value = stateOf(content)
    }

    private fun stateOf(content: QuickContentState): QuickContentUiState =
        mutableState.value.copy(
            loading = false,
            clipboardEnabled = content.clipboardEnabled,
            clipboard = content.clipboard,
            phrases = content.phrases,
            error = null,
        )

    private fun report(error: ContentException) {
        mutableState.value = mutableState.value.copy(loading = false, error = error.code)
    }
}
