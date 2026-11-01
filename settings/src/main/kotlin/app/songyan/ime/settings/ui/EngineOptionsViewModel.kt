package app.songyan.ime.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import app.songyan.ime.settings.state.EngineOptionSetting
import app.songyan.ime.settings.state.EngineOptionsGateway
import app.songyan.ime.settings.state.EngineOptionsHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EngineOptionsUiState(
    val options: List<EngineOptionSetting> = emptyList(),
    /** A choice that could not be saved; the list shows what is really stored. */
    val saveFailed: Boolean = false,
)

/** The schema switches of the input method in use: shown as stored, changed one at a time. */
class EngineOptionsViewModel(
    private val gateway: EngineOptionsGateway,
) : ViewModel() {
    private val mutable = MutableStateFlow(EngineOptionsUiState())
    val uiState: StateFlow<EngineOptionsUiState> = mutable.asStateFlow()

    private var schemaId: String? = null

    /** Shows [id]'s switches; reads again when the input method in use changed. */
    fun show(id: String?) {
        if (id == schemaId) return
        schemaId = id
        mutable.value = EngineOptionsUiState()
        reload()
    }

    fun reload() {
        val id = schemaId ?: return
        viewModelScope.launch {
            val options = gateway.load(id)
            // A later show() for another input method owns the state now.
            if (schemaId == id) mutable.update { it.copy(options = options) }
        }
    }

    fun set(
        name: String,
        on: Boolean,
    ) {
        val id = schemaId ?: return
        mutable.update { state ->
            state.copy(
                saveFailed = false,
                options = state.options.map { if (it.name == name) it.copy(on = on) else it },
            )
        }
        viewModelScope.launch {
            val saved = gateway.set(id, name, on)
            if (schemaId != id) return@launch
            if (!saved) mutable.update { it.copy(saveFailed = true) }
            reload()
        }
    }

    class Factory : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(
            modelClass: Class<T>,
            extras: CreationExtras,
        ): T {
            @Suppress("UNCHECKED_CAST")
            return EngineOptionsViewModel(EngineOptionsHolder.current) as T
        }
    }
}
