package app.songyan.ime.settings.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import app.songyan.ime.script.ScriptEntry
import app.songyan.ime.script.ScriptGuide
import app.songyan.ime.script.ScriptRefusedException
import app.songyan.ime.script.ScriptRegistry
import app.songyan.ime.script.ScriptRegistryHolder
import app.songyan.ime.script.ScriptRegistryUnavailable
import app.songyan.ime.script.ScriptRunOutcome
import app.songyan.ime.script.ScriptSourceOrigin
import app.songyan.ime.settings.R
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Everything the Settings → Scripts screen renders for its list and empty states. */
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

/**
 * What a failed store call means for the user. A refusal the host classified keeps its code and
 * line; anything else (a dead proxy, an unreadable file, a protocol error) is "failed" with no
 * message, because exception text is an English diagnostic and goes to logcat instead.
 */
internal fun scriptFailureOf(e: Exception): ScriptFailure =
    when (e) {
        is ScriptRegistryUnavailable -> ScriptFailure("unavailable", 0)
        is ScriptRefusedException -> {
            Log.w("SongyanScript", "script action refused: " + e.code, e)
            ScriptFailure(e.code, e.line)
        }
        else -> {
            Log.w("SongyanScript", "script action failed", e)
            ScriptFailure("failed", 0)
        }
    }

/** A refusal the importer surfaces: the machine [code] and the source line it names, or 0. */
data class ScriptFailure(
    val code: String,
    val line: Int,
)

/** How a failure is worded: the fixed sentence [text], filled with [scriptWords] when the words
 *  are the script's own, and the source [line] to append, or 0. */
internal data class ScriptFailureCopy(
    @param:StringRes val text: Int,
    val scriptWords: String? = null,
    val line: Int = 0,
)

/**
 * Known codes have their own sentence. The only free text that reaches the user is what a script
 * itself threw (code "script"); every other message is an English diagnostic from the engine or the
 * host and stays in logcat, so an unknown code gets the generic sentence without it.
 */
internal fun scriptFailureCopy(
    code: String,
    message: String?,
    line: Int,
): ScriptFailureCopy {
    val text =
        when (code) {
            "timeout" -> R.string.scripts_error_timeout
            "outOfMemory" -> R.string.scripts_error_out_of_memory
            "addressNotAllowed" -> R.string.scripts_error_address_not_allowed
            "networkFailure" -> R.string.scripts_error_network_failure
            "badHeader" -> R.string.scripts_error_bad_header
            "unsupportedApiVersion" -> R.string.scripts_error_unsupported_api
            "capabilityUnavailable" -> R.string.scripts_error_capability
            "unavailable" -> R.string.scripts_error_unavailable
            "downloadFailed" -> R.string.scripts_error_download
            "script" -> null
            else -> R.string.scripts_error_generic
        }
    if (text != null) {
        // Only a header refusal names a line the user can look for; a run's line is the script's.
        return ScriptFailureCopy(text, line = if (code == "badHeader") line else 0)
    }
    val words = message?.trim()?.takeIf { it.isNotEmpty() }
    return if (words != null) {
        ScriptFailureCopy(R.string.scripts_error_script, words, line)
    } else {
        ScriptFailureCopy(R.string.scripts_error_generic)
    }
}

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

    /** The detail page's settings form was saved. */
    data object SettingsSaved : ScriptsNotice

    /** An edit was saved and its declarations did not change. */
    data object Saved : ScriptsNotice

    /** The imported file is a script that is already there. */
    data object AlreadyAdded : ScriptsNotice

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
 * State of the Settings → Scripts screen. The catalog comes from [ScriptRegistryHolder.current], whose
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
        // SAF exposes the picked file's name through OpenableColumns; lastPathSegment is the
        // provider's document id ("msf:16" for the Files app), which is meaningless in the list.
        val name =
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
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
            val known = registry.entries.map { it.id }.toSet()
            val entry =
                try {
                    val bytes = withContext(ioDispatcher) { read() }
                    registry.add(bytes, origin)
                } catch (e: Exception) {
                    mutable.update { it.copy(busy = false, importFailure = failureOf(e)) }
                    return@launch
                }
            mutable.update {
                // The same file again opens the existing script's confirmation if it still needs one,
                // and otherwise just says it is already there.
                if (entry.id in known && !entry.confirmationPending) {
                    it.copy(busy = false, entries = registry.entries, notice = ScriptsNotice.AlreadyAdded)
                } else {
                    it.copy(busy = false, pendingConfirmation = entry, entries = registry.entries)
                }
            }
        }
    }

    /** Records the user's confirmation and the filled settings; the script becomes usable. A
     *  blank secret field keeps the saved secret; [clearSecrets] names the ones to delete. */
    fun confirm(
        id: String,
        settings: Map<String, String>,
        clearSecrets: Set<String> = emptySet(),
    ) {
        viewModelScope.launch {
            mutable.update { it.copy(busy = true) }
            try {
                registry.confirm(id, settings, clearSecrets)
                mutable.update { it.copy(busy = false, pendingConfirmation = null, entries = registry.entries) }
            } catch (e: Exception) {
                mutable.update { it.copy(busy = false, importFailure = failureOf(e)) }
            }
        }
    }

    /** Saves the settings form of an already confirmed script; secrets keep their saved value
     *  unless [clearSecrets] names them. */
    fun updateSettings(
        id: String,
        settings: Map<String, String>,
        clearSecrets: Set<String> = emptySet(),
    ) {
        viewModelScope.launch {
            mutable.update { it.copy(busy = true) }
            try {
                registry.updateSettings(id, settings, clearSecrets)
                mutable.update {
                    it.copy(busy = false, entries = registry.entries, notice = ScriptsNotice.SettingsSaved)
                }
            } catch (e: Exception) {
                mutable.update { it.copy(busy = false, notice = ScriptsNotice.ActionFailed(failureOf(e))) }
            }
        }
    }

    /** The saved non-secret settings, for prefilling the confirmation and settings forms. */
    suspend fun savedSettings(id: String): Map<String, String> =
        try {
            registry.settings(id)
        } catch (e: Exception) {
            emptyMap()
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

    /** Saves the editor's text. A new script is added and confirmed; an edit replaces the
     *  stored source in place and asks for confirmation only when its declarations changed. */
    fun applyEdit(
        id: String,
        text: String,
    ) {
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, importFailure = null) }
            val bytes = text.toByteArray(Charsets.UTF_8)
            val entry =
                try {
                    if (id.isEmpty()) {
                        registry.add(
                            bytes,
                            ScriptSourceOrigin.Edited,
                        )
                    } else {
                        registry.replaceSource(id, bytes)
                    }
                } catch (e: Exception) {
                    mutable.update { it.copy(busy = false, importFailure = failureOf(e)) }
                    return@launch
                }
            mutable.update {
                if (id.isEmpty() || entry.confirmationPending) {
                    it.copy(busy = false, pendingConfirmation = entry, entries = registry.entries)
                } else {
                    it.copy(busy = false, entries = registry.entries, notice = ScriptsNotice.Saved)
                }
            }
        }
    }

    /** The trial box belongs to one script; typing into it opens it, and the text stays across
     *  runs so the user can run the same input again. */
    fun editTestInput(
        entryId: String,
        text: String,
    ) {
        mutableTest.update { state ->
            if (state?.entryId ==
                entryId
            ) {
                state.copy(input = text)
            } else {
                ScriptTestState(entryId = entryId, input = text)
            }
        }
    }

    fun runTest(entryId: String) {
        val current = mutableTest.value?.takeIf { it.entryId == entryId } ?: ScriptTestState(entryId = entryId)
        if (current.running) return
        mutableTest.value = current.copy(running = true, outcome = null)
        viewModelScope.launch {
            val outcome =
                try {
                    registry.runTrial(entryId, current.input)
                } catch (e: Exception) {
                    ScriptRunOutcome.Failed(failureOf(e).code, "", 0)
                }
            mutableTest.update { state ->
                state?.takeIf { it.entryId == entryId }?.copy(running = false, outcome = outcome)
            }
        }
    }

    /** Stops a trial that is still waiting; the box keeps its text and says the run was cancelled. */
    fun cancelTest(entryId: String) {
        viewModelScope.launch { runCatching { registry.cancel(entryId) } }
        mutableTest.update { state ->
            state?.takeIf { it.entryId == entryId }?.copy(running = false, outcome = ScriptRunOutcome.Cancelled)
        }
    }

    private fun failureOf(e: Exception): ScriptFailure = scriptFailureOf(e)

    private suspend fun readAiGuide(context: Context): String? =
        withContext(ioDispatcher) {
            try {
                ScriptGuide.read(context.assets, registry.hostCapabilities)
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
}
