package app.songyan.ime.settings.ui

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import app.songyan.ime.settings.state.RimeConfigEditResult
import app.songyan.ime.settings.state.RimeConfigFileResult
import app.songyan.ime.settings.state.RimeConfigGateway
import app.songyan.ime.settings.state.RimeConfigHolder
import app.songyan.ime.settings.state.RimeConfigSaveResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why a save did not happen; the text stays as typed. */
sealed interface RimeFileSaveError {
    /** The text is not valid; [line] is the line the reader stopped at, when known. */
    data class Invalid(
        val line: Int?,
    ) : RimeFileSaveError

    /** [text] is already localized, null for generic copy. */
    data class Failed(
        val text: String?,
    ) : RimeFileSaveError
}

data class RimeFileEditorState(
    val loading: Boolean = true,
    /** The file could not be opened; [loadFailure] is already localized, null for generic copy. */
    val failed: Boolean = false,
    val loadFailure: String? = null,
    /** The input method's name; null for the settings every input method shares. */
    val title: String? = null,
    val fileName: String = "",
    val text: String = "",
    /** What the file holds on disk (or opened with); [text] differs by the unsaved edits. */
    val baseline: String = "",
    val saving: Boolean = false,
    val error: RimeFileSaveError? = null,
    /** The last save went through, until the next edit. */
    val saved: Boolean = false,
    /** Intents to open the file in another app, until the screen has tried them. */
    val externalIntents: List<Intent>? = null,
    /** Something said once: no app to open the file with, or why it could not be made ready. */
    val notice: EditorNotice? = null,
) {
    val dirty: Boolean get() = text != baseline
}

sealed interface EditorNotice {
    data object NoEditor : EditorNotice

    data class Failure(
        val text: String?,
    ) : EditorNotice
}

/**
 * The in-app editor for one of the user's configuration files (an input method's `xxx.custom.yaml`
 * or the shared `default.custom.yaml`). The file is read and saved by the app module off the main
 * thread; a save the reader refuses leaves the text as typed and names the line.
 */
class RimeFileEditorViewModel(
    private val gateway: RimeConfigGateway,
    private val key: String,
    private val grammarExample: Boolean,
) : ViewModel() {
    private val mutable = MutableStateFlow(RimeFileEditorState())
    val uiState: StateFlow<RimeFileEditorState> = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            when (val result = gateway.loadFile(key, grammarExample)) {
                is RimeConfigFileResult.Loaded ->
                    mutable.update {
                        it.copy(
                            loading = false,
                            title = result.title,
                            fileName = result.fileName,
                            text = result.text,
                            baseline = result.text,
                        )
                    }
                is RimeConfigFileResult.Failure ->
                    mutable.update { it.copy(loading = false, failed = true, loadFailure = result.message) }
            }
        }
    }

    fun edit(text: String) = mutable.update { it.copy(text = text, error = null, saved = false) }

    fun save() {
        val current = mutable.value
        if (current.loading || current.failed || current.saving || !current.dirty) return
        mutable.update { it.copy(saving = true, error = null) }
        val text = current.text
        viewModelScope.launch {
            val result = gateway.saveFile(key, text)
            mutable.update {
                when (result) {
                    RimeConfigSaveResult.Saved -> it.copy(saving = false, baseline = text, saved = true)
                    is RimeConfigSaveResult.Invalid ->
                        it.copy(saving = false, error = RimeFileSaveError.Invalid(result.line))
                    is RimeConfigSaveResult.Failure ->
                        it.copy(saving = false, error = RimeFileSaveError.Failed(result.message))
                }
            }
        }
    }

    /** Opens the file in another app; the saved file is what opens, not the unsaved text. */
    fun openElsewhere() {
        viewModelScope.launch {
            when (val result = gateway.externalEditIntents(key)) {
                is RimeConfigEditResult.Ready -> mutable.update { it.copy(externalIntents = result.intents) }
                is RimeConfigEditResult.Failure ->
                    mutable.update { it.copy(notice = EditorNotice.Failure(result.message)) }
            }
        }
    }

    /** The screen tried the intents; [opened] says whether one worked. */
    fun externalLaunched(opened: Boolean) =
        mutable.update {
            it.copy(externalIntents = null, notice = if (opened) it.notice else EditorNotice.NoEditor)
        }

    fun noticeShown() = mutable.update { it.copy(notice = null) }

    class Factory(
        private val key: String,
        private val grammarExample: Boolean,
        private val gateway: RimeConfigGateway = RimeConfigHolder.current,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(
            modelClass: Class<T>,
            extras: CreationExtras,
        ): T {
            @Suppress("UNCHECKED_CAST")
            return RimeFileEditorViewModel(gateway, key, grammarExample) as T
        }
    }
}
