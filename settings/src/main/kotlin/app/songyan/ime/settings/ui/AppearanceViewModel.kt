package app.songyan.ime.settings.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import app.songyan.ime.candidate.CandidateBarDefinition
import app.songyan.ime.settings.state.AppearanceFailure
import app.songyan.ime.settings.state.AppearanceImportOutcome
import app.songyan.ime.settings.state.AppearanceSettingsHolder
import app.songyan.ime.settings.state.AppearanceSettingsSource
import app.songyan.ime.settings.state.AppearanceSettingsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Actions of the appearance screen. Every write goes through the app module's bridge, so the
 * keyboard and the settings screen always read the same applied appearance.
 */
class AppearanceViewModel(
    private val source: AppearanceSettingsSource,
) : ViewModel() {
    val uiState: StateFlow<AppearanceSettingsState> = source.state

    private val mutableFailure = MutableStateFlow<AppearanceFailure?>(null)

    /** The failure to surface once, or null; the screen shows it and consumes it. */
    val failure: StateFlow<AppearanceFailure?> = mutableFailure.asStateFlow()

    fun refresh() {
        source.refresh()
    }

    fun select(id: String) {
        viewModelScope.launch {
            // The bridge turns the wallpaper palette off when a colour scheme is picked; both live in
            // one list, so the list itself shows the change.
            if (!source.select(id)) mutableFailure.value = AppearanceFailure.APPLY_FAILED
        }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch {
            if (!source.setDynamicColor(enabled)) mutableFailure.value = AppearanceFailure.APPLY_FAILED
        }
    }

    private val mutableImport = MutableStateFlow<AppearanceImportOutcome?>(null)

    /** The result of the last import, shown once. */
    val importOutcome: StateFlow<AppearanceImportOutcome?> = mutableImport.asStateFlow()

    fun importFrom(uri: Uri) {
        viewModelScope.launch { mutableImport.value = source.importFrom(uri) }
    }

    fun consumeImport() {
        mutableImport.value = null
    }

    fun exportTo(
        id: String,
        uri: Uri,
    ) {
        viewModelScope.launch {
            if (!source.exportTo(id, uri)) mutableFailure.value = AppearanceFailure.EXPORT_FAILED
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            if (!source.delete(id)) mutableFailure.value = AppearanceFailure.DELETE_FAILED
        }
    }

    fun exportFileName(id: String): String = source.exportFileName(id)

    private val candidateBarEdits = Mutex()

    fun updateCandidateBar(edit: (CandidateBarDefinition) -> CandidateBarDefinition) {
        viewModelScope.launch {
            // Each control edits the latest saved bar, including changes still being written.
            candidateBarEdits.withLock {
                if (!source.updateCandidateBar(edit(uiState.value.candidateBar))) {
                    mutableFailure.value = AppearanceFailure.APPLY_FAILED
                }
            }
        }
    }

    fun consumeFailure() {
        mutableFailure.value = null
    }

    /** Builds the view model against the process-wide bridge holder. */
    class Factory : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(
            modelClass: Class<T>,
            extras: CreationExtras,
        ): T {
            @Suppress("UNCHECKED_CAST")
            return AppearanceViewModel(AppearanceSettingsHolder.current) as T
        }
    }
}
