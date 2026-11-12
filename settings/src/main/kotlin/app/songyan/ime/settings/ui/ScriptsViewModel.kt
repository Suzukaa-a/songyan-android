package app.songyan.ime.settings.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import app.songyan.ime.script.ScriptEntry
import app.songyan.ime.script.ScriptRegistry
import app.songyan.ime.script.ScriptRegistryHolder
import app.songyan.ime.script.ScriptRegistryUnavailable
import app.songyan.ime.script.ScriptRunOutcome
import app.songyan.ime.script.ScriptSourceOrigin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Everything the 脚本 screen renders for its list and empty states. */
data class ScriptsUiState(
    val loading: Boolean = true,
    val entries: List<ScriptEntry> = emptyList(),
    val refreshing: Boolean = false,
    /** The guide copied to the clipboard; null until the user asks and it succeeds. */
    val guideCopied: Boolean = false,
    /** The guide could not be read from assets; the retry offers itself again. */
    val guideFailed: Boolean = false,
    /** An import or a list action is running; the buttons wait for it. */
    val busy: Boolean = false,
    /** The script just stored, waiting for the user's confirmation; navigates to the confirm page. */
    val pendingConfirmation: ScriptEntry? = null,
    /** What an import attempt refused, shown as the import dialog's error. */
    val importFailure: ScriptFailure? = null,
    /** A one-off outcome of a list action, shown once as a snackbar. */
    val notice: ScriptsNotice? = null,
)

/** A refusal the importer surfaces: the machine [code], its line and the engine's own message. */
data class ScriptFailure(
    val code: String,
    val line: Int,
    val message: String?,
)

/** One-off messages of the list actions. */
sealed interface ScriptsNotice {
    data object Deleted : ScriptsNotice

    /** The update check ran and the declarations did not change. */
    data object UpToDate : ScriptsNotice

    /** The update brought new declarations; the entry asks for confirmation again. */
    data class Updated(
        val entry: ScriptEntry,
    ) : ScriptsNotice

    data object Exported : ScriptsNotice

    data object ExportFailed : ScriptsNotice

    data class ActionFailed(
        val failure: ScriptFailure,
    ) : ScriptsNotice
}

/** The trial-run box state, owned by the entry the user opened. */
data class ScriptTestState(
    val entryId: String,
    val input: String = "",
    val running: Boolean = false,
    val outcome: ScriptRunOutcome? = null,
)

/**
 * State of the 设置 → 脚本 screen. The catalog comes from [ScriptRegistryHolder.current], whose
 * cached entries never start the proxy process; the AI guide ships as an asset of the script
 * module and is read once on demand.
 *
 * The mutating flows — add (paste, file, link, editor), confirm, settings, enable, reorder,
 * delete, export and update check — all land here so their outcome survives navigation.
 */
class ScriptsViewModel(
    private val registry: ScriptRegistry,
    /** The disk and network work an import does; tests hand in their own dispatcher. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutable = MutableStateFlow(ScriptsUiState())
    val uiState: StateFlow<ScriptsUiState> = mutable.asStateFlow()

    private val mutableTest = MutableStateFlow<ScriptTestState?>(null)
    val test: StateFlow<ScriptTestState?> = mutableTest.asStateFlow()

    init {
        refresh()
    }

    /** Re-reads the catalog; the keyboard may have changed it while this screen was away. */
    fun refresh() {
        mutable.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            registry.reload()
            mutable.update { it.copy(loading = false, refreshing = false, entries = registry.entries) }
        }
    }

    fun consumeGuideCopied() = mutable.update { it.copy(guideCopied = false) }

    fun consumeGuideFailed() = mutable.update { it.copy(guideFailed = false) }

    fun consumeImportFailure() = mutable.update { it.copy(importFailure = null) }

    fun consumeNotice() = mutable.update { it.copy(notice = null) }

    /** The confirm page has shown what it needed to; the entry stays pending until confirmed. */
    fun consumePendingConfirmation() = mutable.update { it.copy(pendingConfirmation = null) }

    /** Copies the AI guide asset to the clipboard; the empty state's button leads here. */
    fun copyAiGuide(
        context: Context,
        label: String,
    ) {
        viewModelScope.launch {
            val guide = readAiGuide(context)
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            if (guide == null || clipboard == null) {
                mutable.update { it.copy(guideFailed = true) }
                return@launch
            }
            clipboard.setPrimaryClip(
                ClipData.newPlainText(label, guide),
            )
            mutable.update { it.copy(guideCopied = true) }
        }
    }

    /** Stores pasted text; the confirm page opens when the header parses. */
    fun addFromPaste(text: String) {
        if (text.isEmpty()) return
        importSource(ScriptSourceOrigin.Pasted) { text.toByteArray(Charsets.UTF_8) }
    }

    /** Stores the picked file's bytes; the picker's display name names the origin. */
    fun addFromFile(
        context: Context,
        uri: Uri,
    ) {
        val name =
            uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
                ?: uri.toString()
        importSource(ScriptSourceOrigin.File(name)) {
            context.contentResolver.openInputStream(uri)?.use { stream -> stream.readBytes() }
                ?: throw IOException("the picked file cannot be read")
        }
    }

    /** Downloads an HTTPS link once and stores what came back. */
    fun addFromLink(url: String) {
        if (url.isBlank()) return
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, importFailure = null) }
            val entry =
                try {
                    registry.addFromLink(url.trim())
                } catch (e: Exception) {
                    mutable.update { it.copy(busy = false, importFailure = failureOf(e)) }
                    return@launch
                }
            mutable.update {
                it.copy(busy = false, pendingConfirmation = entry, entries = registry.entries)
            }
        }
    }

    /** Stores what [read] returns under [origin]; the paste and file imports share it, and the
     *  test hands in bytes directly instead of a picked file. */
    internal fun importSource(
        origin: ScriptSourceOrigin,
        read: suspend () -> ByteArray,
    ) {
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, importFailure = null) }
            val entry =
                try {
                    val bytes = withContext(ioDispatcher) { read() }
                    registry.add(bytes, origin)
                } catch (e: Exception) {
                    mutable.update { it.copy(busy = false, importFailure = failureOf(e)) }
                    return@launch
                }
            mutable.update {
                it.copy(busy = false, pendingConfirmation = entry, entries = registry.entries)
            }
        }
    }

    /** Records the user's confirmation and the filled settings; the script becomes usable. */
    fun confirm(
        id: String,
        settings: Map<String, String>,
    ) {
        viewModelScope.launch {
            mutable.update { it.copy(busy = true) }
            try {
                registry.confirm(id, settings)
                mutable.update { it.copy(busy = false, pendingConfirmation = null, entries = registry.entries) }
            } catch (e: Exception) {
                mutable.update { it.copy(busy = false, importFailure = failureOf(e)) }
            }
        }
    }

    fun setEnabled(
        id: String,
        enabled: Boolean,
    ) {
        viewModelScope.launch {
            try {
                registry.setEnabled(id, enabled)
                mutable.update { it.copy(entries = registry.entries) }
            } catch (e: Exception) {
                mutable.update { it.copy(notice = ScriptsNotice.ActionFailed(failureOf(e))) }
            }
        }
    }

    /** Moves [id] one slot [up]; the registry rewrites the whole order. */
    fun move(
        id: String,
        up: Boolean,
    ) {
        val current = mutable.value.entries.sortedBy { it.order }
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return
        val target = if (up) index - 1 else index + 1
        if (target !in current.indices) return
        val reordered =
            current.toMutableList().apply { add(target, removeAt(index)) }.map { it.id }
        viewModelScope.launch {
            try {
                registry.reorder(reordered)
                mutable.update { it.copy(entries = registry.entries) }
            } catch (e: Exception) {
                mutable.update { it.copy(notice = ScriptsNotice.ActionFailed(failureOf(e))) }
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            try {
                registry.delete(id)
                mutable.update { it.copy(entries = registry.entries, notice = ScriptsNotice.Deleted) }
                if (mutableTest.value?.entryId == id) mutableTest.value = null
            } catch (e: Exception) {
                mutable.update { it.copy(notice = ScriptsNotice.ActionFailed(failureOf(e))) }
            }
        }
    }

    /** Re-fetches a link origin; the entry says whether the declarations changed. */
    fun checkUpdate(id: String) {
        viewModelScope.launch {
            mutable.update { it.copy(busy = true) }
            val entry =
                try {
                    registry.checkUpdate(id)
                } catch (e: Exception) {
                    mutable.update { it.copy(busy = false, notice = ScriptsNotice.ActionFailed(failureOf(e))) }
                    return@launch
                }
            mutable.update {
                it.copy(
                    busy = false,
                    entries = registry.entries,
                    notice =
                        if (entry.confirmationPending) {
                            ScriptsNotice.Updated(entry)
                        } else {
                            ScriptsNotice.UpToDate
                        },
                )
            }
        }
    }

    /** The stored source, for the in-app editor and the export. */
    suspend fun sourceOf(id: String): ByteArray? =
        try {
            registry.source(id)
        } catch (e: Exception) {
            null
        }

    /** Shares the exported bytes — the original source, never the secrets — through the system
     *  share sheet. A script is a single text file, so text/plain carries it everywhere. */
    fun export(
        context: Context,
        id: String,
        fileName: String,
    ) {
        viewModelScope.launch {
            val bytes =
                withContext(ioDispatcher) {
                    try {
                        registry.export(id)
                    } catch (e: Exception) {
                        null
                    }
                }
            if (bytes == null) {
                mutable.update { it.copy(notice = ScriptsNotice.ExportFailed) }
                return@launch
            }
            val intent =
                android.content
                    .Intent(android.content.Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(
                        android.content.Intent.EXTRA_SUBJECT,
                        fileName,
                    ).putExtra(android.content.Intent.EXTRA_TEXT, bytes.toString(Charsets.UTF_8))
            val launch =
                if (context !is android.app.Activity) {
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                } else {
                    intent
                }
            val shared =
                try {
                    context.startActivity(android.content.Intent.createChooser(launch, fileName))
                    true
                } catch (e: android.content.ActivityNotFoundException) {
                    false
                } catch (e: java.lang.SecurityException) {
                    false
                }
            mutable.update {
                it.copy(notice = if (shared) ScriptsNotice.Exported else ScriptsNotice.ExportFailed)
            }
        }
    }

    /** Replaces the stored source from the editor: the edited text is stored first, then the
     *  previous entry is deleted, so a failure in between loses nothing the user still had. */
    fun applyEdit(
        id: String,
        text: String,
    ) {
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, importFailure = null) }
            val entry =
                try {
                    registry.add(text.toByteArray(Charsets.UTF_8), ScriptSourceOrigin.Edited)
                } catch (e: Exception) {
                    mutable.update { it.copy(busy = false, importFailure = failureOf(e)) }
                    return@launch
                }
            if (id.isNotEmpty() && id != entry.id) {
                runCatching { registry.delete(id) }
            }
            mutable.update {
                it.copy(busy = false, pendingConfirmation = entry, entries = registry.entries)
            }
        }
    }

    fun beginTest(entryId: String) {
        mutableTest.value = ScriptTestState(entryId = entryId)
    }

    fun editTestInput(text: String) {
        mutableTest.update { state -> state?.copy(input = text) }
    }

    fun runTest() {
        val state = mutableTest.value ?: return
        viewModelScope.launch {
            mutableTest.update { it?.copy(running = true) }
            val outcome =
                try {
                    registry.runTrial(state.entryId, state.input)
                } catch (e: Exception) {
                    ScriptRunOutcome.Failed(failureOf(e).code, e.message ?: "", 0)
                }
            mutableTest.update { it?.copy(running = false, outcome = outcome) }
        }
    }

    fun cancelTest() {
        val state = mutableTest.value ?: return
        viewModelScope.launch { runCatching { registry.cancel(state.entryId) } }
        mutableTest.value = null
    }

    private fun failureOf(e: Exception): ScriptFailure =
        when (e) {
            is ScriptRegistryUnavailable -> ScriptFailure("unavailable", 0, null)
            else -> ScriptFailure("script", 0, e.message)
        }

    private suspend fun readAiGuide(context: Context): String? =
        withContext(ioDispatcher) {
            try {
                context.assets
                    .open(AI_GUIDE_ASSET)
                    .bufferedReader()
                    .use { it.readText() }
            } catch (e: IOException) {
                null
            }
        }

    class Factory(
        private val registry: ScriptRegistry = ScriptRegistryHolder.current,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(
            modelClass: Class<T>,
            extras: CreationExtras,
        ): T {
            @Suppress("UNCHECKED_CAST")
            return ScriptsViewModel(registry) as T
        }
    }

    private companion object {
        const val AI_GUIDE_ASSET = "script/ai-guide.txt"
    }
}
