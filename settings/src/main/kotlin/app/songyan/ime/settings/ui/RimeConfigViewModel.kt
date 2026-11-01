package app.songyan.ime.settings.ui

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import app.songyan.ime.settings.state.RimeConfigApplyResult
import app.songyan.ime.settings.state.RimeConfigGateway
import app.songyan.ime.settings.state.RimeConfigHolder
import app.songyan.ime.settings.state.RimeConfigInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A one-off result the screen shows once. */
sealed interface RimeConfigMessage {
    data object Applied : RimeConfigMessage

    /** No app opens the folder. */
    data object NoFilesApp : RimeConfigMessage

    /** Applying could not even be tried; [text] is already localized, null for generic copy. */
    data class Failure(
        val text: String?,
    ) : RimeConfigMessage
}

/** [firstTime]: the user has not yet been told what a script can do. */
data class ScriptConfirmation(
    val scripts: List<String>,
    val firstTime: Boolean,
)

data class RimeConfigUiState(
    val available: Boolean = false,
    /** Null until the folder was read once. */
    val info: RimeConfigInfo? = null,
    val applying: Boolean = false,
    val message: RimeConfigMessage? = null,
    /** Files the user has just applied that replace the scheme's own, told once; null when there is nothing to tell. */
    val overrideNotice: List<String>? = null,
    /** Scripts waiting for the user's yes before anything is applied; null when none. */
    val scriptConfirmation: ScriptConfirmation? = null,
) {
    /** Applying does something: there are changes, or the last attempt or deployment left them out. */
    val canApply: Boolean
        get() = available && !applying && info != null && (info.pending || info.failure != null || info.notice != null)
}

/**
 * The Rime configuration rows of the input scheme screen. Owned by the activity, so applying keeps
 * going, and its outcome still shows, when the user leaves the screen meanwhile.
 */
class RimeConfigViewModel(
    private val gateway: RimeConfigGateway,
) : ViewModel() {
    private val mutable = MutableStateFlow(RimeConfigUiState(available = gateway.available))
    val uiState: StateFlow<RimeConfigUiState> = mutable.asStateFlow()

    /** Reads the folder again; the user edits it in another app, so this runs on every return. */
    fun refresh() {
        if (!gateway.available) return
        viewModelScope.launch {
            val info = gateway.info()
            mutable.update { it.copy(info = info) }
        }
    }

    fun openFolderIntents(): List<Intent> = gateway.openFolderIntents()

    fun noFilesApp() = mutable.update { it.copy(message = RimeConfigMessage.NoFilesApp) }

    /** Gives up the user's version of [path]; the scheme's own file is used again after the next apply. */
    fun restore(path: String) {
        viewModelScope.launch {
            gateway.restore(path)
            refresh()
        }
    }

    /** The user has read the notice about files that replace the scheme's own. */
    fun dismissOverrideNotice() {
        val paths = mutable.value.overrideNotice ?: return
        mutable.update { it.copy(overrideNotice = null) }
        viewModelScope.launch {
            gateway.acknowledge(paths)
            refresh()
        }
    }

    fun apply() = applyWith(confirmScripts = false)

    /** The user agreed to use the scripts [RimeConfigUiState.scriptConfirmation] listed: apply for real. */
    fun confirmScripts() {
        mutable.update { it.copy(scriptConfirmation = null) }
        applyWith(confirmScripts = true)
    }

    /** The user declined: nothing is applied and the files stay as they are. */
    fun cancelScripts() = mutable.update { it.copy(scriptConfirmation = null) }

    private fun applyWith(confirmScripts: Boolean) {
        if (!mutable.value.canApply) return
        mutable.update { it.copy(applying = true, message = null) }
        viewModelScope.launch {
            val result = gateway.apply(confirmScripts)
            if (result is RimeConfigApplyResult.NeedsConfirmation) {
                mutable.update {
                    it.copy(applying = false, scriptConfirmation = ScriptConfirmation(result.scripts, result.firstTime))
                }
                return@launch
            }
            val info = gateway.info()
            mutable.update {
                it.copy(
                    applying = false,
                    info = info,
                    overrideNotice =
                        info.unacknowledged
                            .takeIf { result == RimeConfigApplyResult.Applied && it.isNotEmpty() },
                    message =
                        when (result) {
                            RimeConfigApplyResult.Applied -> RimeConfigMessage.Applied
                            is RimeConfigApplyResult.NeedsConfirmation -> null
                            // A refused file is shown on the row for as long as it stands.
                            is RimeConfigApplyResult.Failure ->
                                if (info.failure != null) null else RimeConfigMessage.Failure(result.message)
                        },
                )
            }
        }
    }

    fun dismissMessage() = mutable.update { it.copy(message = null) }

    class Factory(
        private val gateway: RimeConfigGateway = RimeConfigHolder.current,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(
            modelClass: Class<T>,
            extras: CreationExtras,
        ): T {
            @Suppress("UNCHECKED_CAST")
            return RimeConfigViewModel(gateway) as T
        }
    }
}
