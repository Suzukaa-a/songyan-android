// SPDX-License-Identifier: GPL-3.0-only
package app.songyan.ime.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import app.songyan.ime.settings.state.AddonInfo
import app.songyan.ime.settings.state.AddonSettingsGateway
import app.songyan.ime.settings.state.AddonSettingsHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the 设置 → 扩展 page renders. */
data class ExtensionsUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val available: Boolean = true,
    /** The installed extensions, in the order the app reports them. */
    val extensions: List<AddonInfo> = emptyList(),
    /** The scan failed as a whole; the list is then the last one that was read. */
    val scanFailed: Boolean = false,
    /** The last switch could not be saved and was rolled back. */
    val actionFailed: Boolean = false,
)

/**
 * State of 设置 → 扩展: the extensions installed on this device, each one's identity and
 * permissions, its compatibility, and the switch that lets the keyboard use it. The gateway is the
 * app module's: this page never binds to an extension by itself.
 */
class ExtensionsViewModel(
    private val gateway: AddonSettingsGateway = AddonSettingsHolder.current,
) : ViewModel() {
    private val mutable = MutableStateFlow(ExtensionsUiState(available = gateway.available))
    val uiState: StateFlow<ExtensionsUiState> = mutable.asStateFlow()

    init {
        refresh()
    }

    /** Reads the list again; called when the page opens and after a switch. */
    fun refresh() {
        mutable.update { it.copy(refreshing = !it.loading) }
        viewModelScope.launch {
            val previous = mutable.value.extensions
            val list = runCatching { gateway.list() }
            mutable.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    available = gateway.available,
                    extensions = list.getOrElse { previous },
                    scanFailed = list.isFailure,
                )
            }
        }
    }

    /** Re-scans after the user installed or removed an extension. */
    fun rescan() {
        mutable.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            val previous = mutable.value.extensions
            val list = runCatching { gateway.rescan() }
            mutable.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    available = gateway.available,
                    extensions = list.getOrElse { previous },
                    scanFailed = list.isFailure,
                )
            }
        }
    }

    /** Flips one extension; the switch is drawn from the answer, never optimistically. */
    fun setEnabled(
        packageName: String,
        enabled: Boolean,
    ) {
        val previous = mutable.value.extensions
        mutable.update { state ->
            state.copy(
                extensions =
                    state.extensions.map {
                        if (it.packageName == packageName) it.copy(enabled = enabled) else it
                    },
            )
        }
        viewModelScope.launch {
            val applied = runCatching { gateway.setEnabled(packageName, enabled) }.getOrDefault(false)
            if (!applied) {
                // Neither the file nor the registry changed: put the switch back where it was.
                mutable.update { state -> state.copy(extensions = previous, actionFailed = true) }
            }
        }
    }

    /** Removes the stale "scan failed" warning after the user saw it. */
    fun consumeScanFailed() {
        mutable.update { it.copy(scanFailed = false) }
    }

    /** Removes the stale "switch not saved" warning after the user saw it. */
    fun consumeActionFailed() {
        mutable.update { it.copy(actionFailed = false) }
    }

    class Factory(
        private val gateway: AddonSettingsGateway = AddonSettingsHolder.current,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(
            modelClass: Class<T>,
            extras: CreationExtras,
        ): T {
            @Suppress("UNCHECKED_CAST")
            return ExtensionsViewModel(gateway) as T
        }
    }
}
