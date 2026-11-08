package app.songyan.ime.settings.ui

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import app.songyan.ime.content.BuiltinToolDefinitions
import app.songyan.ime.content.ContentException
import app.songyan.ime.content.ToolCatalog
import app.songyan.ime.content.ToolDefinition
import app.songyan.ime.content.ToolDefinitionError
import app.songyan.ime.content.ToolDefinitionLimits
import app.songyan.ime.content.ToolStep
import app.songyan.ime.content.ToolStore
import app.songyan.ime.content.ToolStoreError
import app.songyan.ime.settings.state.IncomingToolHolder
import app.songyan.ime.settings.state.KeyboardToolsMessage
import app.songyan.ime.settings.state.KeyboardToolsUiState
import app.songyan.ime.settings.state.ProcessTextApp
import app.songyan.ime.settings.state.ProcessTextToolHolder
import app.songyan.ime.settings.state.ProcessTextToolSource
import app.songyan.ime.settings.state.SettingsAppStateHolder
import app.songyan.ime.settings.state.SpeechToolHolder
import app.songyan.ime.settings.state.ToolDraft
import app.songyan.ime.settings.state.ToolEditorState
import app.songyan.ime.settings.state.ToolProblem
import app.songyan.ime.settings.state.ToolStepKind
import app.songyan.ime.settings.state.simplificationConfigOf
import app.songyan.ime.settings.state.toolProblemOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * The 键盘工具 page: the tools the keyboard offers, which of them are enabled or pinned, and the
 * editor for the user's own tools.
 *
 * The store is the one the keyboard reads (the app's files directory), so a change here applies
 * without a reload, and every file operation runs on the IO dispatcher because the settings UI must
 * stay off disk on the main thread.
 */
class KeyboardToolsViewModel(
    private val application: Application,
    /** The other apps that handle text; null when this build has no source installed. */
    private val processTextSource: () -> ProcessTextToolSource? = { ProcessTextToolHolder.current },
    /** The speech recognizers; null when this build has no source installed. */
    private val speechSource: () -> ProcessTextToolSource? = { SpeechToolHolder.current },
    /**
     * Resolved on first use rather than in the constructor: reading `filesDir` touches the file
     * system, and this view model is built on the main thread.
     */
    private val storeProvider: () -> ToolStore = {
        ToolStore(
            File(application.filesDir, ToolStore.DIRECTORY_NAME).toPath(),
            BuiltinToolDefinitions.all(
                simplificationConfigOf(SettingsAppStateHolder.current.state.value),
            ),
        )
    },
) : ViewModel() {
    private val mutable = MutableStateFlow(KeyboardToolsUiState())
    val uiState: StateFlow<KeyboardToolsUiState> = mutable.asStateFlow()

    init {
        refresh()
    }

    /** Re-reads the catalog; called when the page opens and whenever it comes back to the front. */
    fun refresh() {
        viewModelScope.launch { load() }
    }

    private suspend fun load(): Boolean {
        val catalog =
            try {
                withContext(Dispatchers.IO) { storeProvider().load { error -> throw error } }
            } catch (error: ContentException) {
                report(toolProblemOf(error))
                return false
            } catch (error: ToolStoreError) {
                report(toolProblemOf(error))
                return false
            }
        mutable.update {
            it.copy(
                loading = false,
                tools = catalog.tools,
            )
        }
        loadProcessText()
        loadSpeech()
        return true
    }

    /**
     * Reads the other apps that handle text and the ones the user turned on.
     *
     * The section is left out (null) while there is no source, so a build without the keyboard's
     * relay does not show an empty list of apps.
     */
    private suspend fun loadProcessText() {
        val source = processTextSource() ?: return
        val list = readApps(source)
        mutable.update {
            if (list == null) {
                it.copy(processTextUnavailable = true)
            } else {
                it.copy(
                    processTextApps = list.first,
                    processTextEnabled = list.second,
                    processTextUnavailable = false,
                )
            }
        }
    }

    /** The speech recognizers, read the same way as the other apps. */
    private suspend fun loadSpeech() {
        val source = speechSource() ?: return
        val list = readApps(source)
        mutable.update {
            if (list == null) {
                it.copy(speechUnavailable = true)
            } else {
                it.copy(speechApps = list.first, speechEnabled = list.second, speechUnavailable = false)
            }
        }
    }

    /** The listed apps and the enabled components; null when the apps could not be listed at all. */
    private suspend fun readApps(source: ProcessTextToolSource): Pair<List<ProcessTextApp>, Set<String>>? {
        val apps =
            try {
                withContext(Dispatchers.IO) { source.available() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return null
            }
        val enabled =
            try {
                withContext(Dispatchers.IO) { source.enabled() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptySet()
            }
        return apps to enabled
    }

    /** Turns one app's text handling on or off; the keyboard reads the same list on its next panel. */
    fun setProcessTextEnabled(
        component: String,
        enabled: Boolean,
    ) {
        viewModelScope.launch {
            val components = writeEnabled(processTextSource(), component, enabled) ?: return@launch
            mutable.update { it.copy(processTextEnabled = components) }
        }
    }

    /** Turns one speech recognizer on or off. */
    fun setSpeechEnabled(
        component: String,
        enabled: Boolean,
    ) {
        viewModelScope.launch {
            val components = writeEnabled(speechSource(), component, enabled) ?: return@launch
            mutable.update { it.copy(speechEnabled = components) }
        }
    }

    private suspend fun writeEnabled(
        source: ProcessTextToolSource?,
        component: String,
        enabled: Boolean,
    ): Set<String>? {
        if (source == null) return null
        return try {
            withContext(Dispatchers.IO) { source.setEnabled(component, enabled) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            report(ToolProblem.StorageUnavailable)
            null
        }
    }

    fun setEnabled(
        id: String,
        enabled: Boolean,
    ) {
        catalogAction { storeProvider().setEnabled(id, enabled) }
    }

    fun setPinned(
        id: String,
        pinned: Boolean,
    ) {
        catalogAction { storeProvider().setPinned(id, pinned) }
    }

    /** Moves [id] one place earlier in the list, which is also the toolbar order. */
    fun moveEarlier(id: String) {
        val position = mutable.value.tools.indexOfFirst { it.id == id }
        if (position <= 0) return
        catalogAction { storeProvider().move(id, position - 1) }
    }

    /** Moves [id] one place later in the list. */
    fun moveLater(id: String) {
        val position = mutable.value.tools.indexOfFirst { it.id == id }
        if (position < 0 || position >= mutable.value.tools.lastIndex) return
        catalogAction { storeProvider().move(id, position + 1) }
    }

    /** Deletes a user tool; the store keeps one undo, offered on the snackbar. */
    fun delete(id: String) {
        val definition =
            mutable.value.tools
                .firstOrNull { it.id == id }
                ?.definition ?: return
        catalogAction(onUpdated = { KeyboardToolsMessage.Deleted(definition) }) {
            storeProvider().delete(id)
        }
    }

    fun undoDelete() {
        catalogAction { storeProvider().undoDelete() }
    }

    // Import

    /** Reads and imports the file the user picked with the system file picker. */
    fun importFile(
        resolver: ContentResolver,
        uri: Uri,
    ) {
        viewModelScope.launch {
            when (val read = readPickedFile(resolver, uri)) {
                is PickedFile.Read -> importBytes(read.bytes)
                is PickedFile.Refused -> report(read.problem)
            }
        }
    }

    /**
     * Imports one decoded document; [onDone] runs on the main thread with the reason when the file
     * is refused and with null once it is stored.
     */
    fun importBytes(
        bytes: ByteArray,
        onDone: (ToolProblem?) -> Unit = {},
    ) {
        viewModelScope.launch {
            val stored =
                try {
                    withContext(Dispatchers.IO) { storeProvider().importDefinition(bytes) }
                } catch (error: ToolDefinitionError) {
                    val problem = toolProblemOf(error)
                    report(problem)
                    onDone(problem)
                    return@launch
                } catch (error: ToolStoreError) {
                    val problem = toolProblemOf(error)
                    report(problem)
                    onDone(problem)
                    return@launch
                } catch (error: ContentException) {
                    val problem = toolProblemOf(error)
                    report(problem)
                    onDone(problem)
                    return@launch
                }
            load()
            mutable.update { it.copy(message = KeyboardToolsMessage.Imported(stored.definition)) }
            onDone(null)
        }
    }

    /** The user agreed to import the file another app handed over. */
    fun importPending() {
        val uri = IncomingToolHolder.uri.value ?: return
        IncomingToolHolder.clear()
        importFile(application.contentResolver, uri)
    }

    /** The user declined the file another app handed over. */
    fun dismissPendingImport() = IncomingToolHolder.clear()

    // Export

    /** Writes the tool's canonical file to the document the user picked. */
    fun export(
        id: String,
        resolver: ContentResolver,
        uri: Uri,
    ) {
        val definition =
            mutable.value.tools
                .firstOrNull { it.id == id }
                ?.definition ?: return
        viewModelScope.launch {
            val bytes =
                try {
                    exportedBytes(id)
                } catch (error: ToolStoreError) {
                    report(toolProblemOf(error))
                    return@launch
                } catch (error: ContentException) {
                    report(toolProblemOf(error))
                    return@launch
                }
            val written = withContext(Dispatchers.IO) { writeDocument(resolver, uri, bytes) }
            if (written) {
                mutable.update { it.copy(message = KeyboardToolsMessage.Exported(definition)) }
            } else {
                report(ToolProblem.StorageUnavailable)
            }
        }
    }

    /** The canonical songyantool.json bytes of one tool; the same bytes [export] writes. */
    suspend fun exportedBytes(id: String): ByteArray = withContext(Dispatchers.IO) { storeProvider().export(id) }

    // Copying an example

    /**
     * Creates a user tool from [example] and reports its new identifier, so the page can open the
     * editor on it. The copy keeps the example's steps until the user edits them.
     */
    fun copyExample(
        example: ToolDefinition,
        onCreated: (String) -> Unit,
    ) {
        viewModelScope.launch {
            val stored =
                try {
                    withContext(Dispatchers.IO) {
                        val taken =
                            storeProvider()
                                .load()
                                .tools
                                .map { it.id }
                                .toSet()
                        var candidate: String
                        do {
                            candidate = "user-" +
                                UUID
                                    .randomUUID()
                                    .toString()
                                    .replace("-", "")
                                    .take(12)
                        } while (candidate in taken)
                        storeProvider().copyExample(example, candidate)
                    }
                } catch (error: ToolDefinitionError) {
                    report(toolProblemOf(error))
                    return@launch
                } catch (error: ToolStoreError) {
                    report(toolProblemOf(error))
                    return@launch
                } catch (error: ContentException) {
                    report(toolProblemOf(error))
                    return@launch
                }
            load()
            onCreated(stored.id)
        }
    }

    // Editor

    /** Opens the editor on the stored tool [id]; the page shows "not found" when it is gone. */
    fun openEditor(id: String) {
        mutable.update { it.copy(loading = true, editor = null) }
        viewModelScope.launch {
            // Files may have changed since this model first loaded, including a newly copied tool.
            if (!load()) return@launch
            val definition =
                mutable.value.tools
                    .firstOrNull { it.id == id }
                    ?.definition
            mutable.update {
                it.copy(editor = definition?.let { definition -> ToolEditorState(ToolDraft.of(definition)) })
            }
        }
    }

    fun updateName(
        tag: String,
        value: String,
    ) {
        editDraft { it.withName(tag, value) }
    }

    fun updateStep(
        index: Int,
        step: ToolStep,
    ) {
        editDraft { it.withStep(index, step) }
    }

    fun addStep(kind: ToolStepKind) {
        editDraft { it.withAddedStep(kind) }
    }

    fun removeStep(index: Int) {
        editDraft { it.withoutStep(index) }
    }

    fun dismissEditorProblem() {
        mutable.update { state -> state.copy(editor = state.editor?.copy(problem = null)) }
    }

    /** Validates and stores the draft; [onSaved] runs once the store accepted it. */
    fun saveEditor(onSaved: () -> Unit) {
        val editor = mutable.value.editor ?: return
        val definition = editor.draft.toDefinition()
        if (definition.name.isEmpty()) {
            mutable.update { it.copy(editor = it.editor?.copy(problem = ToolProblem.InvalidName)) }
            return
        }
        mutable.update { it.copy(editor = it.editor?.copy(saving = true, problem = null)) }
        viewModelScope.launch {
            val problem =
                try {
                    withContext(Dispatchers.IO) { storeProvider().importDefinition(definition.encoded()) }
                    null
                } catch (error: ToolDefinitionError) {
                    toolProblemOf(error)
                } catch (error: ToolStoreError) {
                    toolProblemOf(error)
                } catch (error: ContentException) {
                    toolProblemOf(error)
                }
            if (problem == null) {
                load()
                mutable.update { it.copy(editor = null) }
                onSaved()
            } else {
                mutable.update { it.copy(editor = it.editor?.copy(saving = false, problem = problem)) }
            }
        }
    }

    fun consumeMessage() {
        mutable.update { it.copy(message = null) }
    }

    private fun editDraft(change: (ToolDraft) -> ToolDraft) {
        mutable.update { state ->
            val editor = state.editor ?: return@update state
            state.copy(editor = editor.copy(draft = change(editor.draft)))
        }
    }

    /** Runs one store transaction and republishes the resulting catalog. */
    private fun catalogAction(
        onUpdated: ((ToolCatalog) -> KeyboardToolsMessage?)? = null,
        block: suspend () -> ToolCatalog,
    ) {
        viewModelScope.launch {
            val catalog =
                try {
                    withContext(Dispatchers.IO) { block() }
                } catch (error: ToolStoreError) {
                    report(toolProblemOf(error))
                    return@launch
                } catch (error: ContentException) {
                    report(toolProblemOf(error))
                    return@launch
                }
            mutable.update {
                it.copy(
                    loading = false,
                    tools = catalog.tools,
                    message = onUpdated?.invoke(catalog) ?: it.message,
                )
            }
        }
    }

    private fun report(problem: ToolProblem) {
        mutable.update { it.copy(loading = false, message = KeyboardToolsMessage.Failed(problem)) }
    }

    private sealed interface PickedFile {
        class Read(
            val bytes: ByteArray,
        ) : PickedFile

        class Refused(
            val problem: ToolProblem,
        ) : PickedFile
    }

    /** At most one byte over the budget, so an oversized file is named instead of being read whole. */
    private suspend fun readPickedFile(
        resolver: ContentResolver,
        uri: Uri,
    ): PickedFile =
        withContext(Dispatchers.IO) {
            val bytes =
                try {
                    resolver.openInputStream(uri)?.use { input ->
                        readUpTo(input, ToolDefinitionLimits.MAXIMUM_FILE_BYTES + 1)
                    }
                } catch (error: IOException) {
                    null
                } catch (error: SecurityException) {
                    null
                } ?: return@withContext PickedFile.Refused(ToolProblem.Unreadable)
            if (bytes.size > ToolDefinitionLimits.MAXIMUM_FILE_BYTES) {
                PickedFile.Refused(ToolProblem.TooLarge(ToolDefinitionLimits.MAXIMUM_FILE_BYTES / 1024))
            } else {
                PickedFile.Read(bytes)
            }
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

    private fun writeDocument(
        resolver: ContentResolver,
        uri: Uri,
        bytes: ByteArray,
    ): Boolean =
        try {
            // "wt" truncates: overwriting a longer document must not leave its tail behind.
            resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } != null
        } catch (error: IOException) {
            false
        } catch (error: SecurityException) {
            false
        }

    /** Builds the view model against the app's own tools directory. */
    class Factory : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(
            modelClass: Class<T>,
            extras: CreationExtras,
        ): T {
            val application =
                checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]) {
                    "The tools page needs the application"
                }
            @Suppress("UNCHECKED_CAST")
            return KeyboardToolsViewModel(application) as T
        }
    }
}
