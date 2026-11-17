package app.songyan.ime.settings.ui

import android.graphics.Typeface
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import app.songyan.ime.appearance.font.FontCascade
import app.songyan.ime.appearance.font.FontRole
import app.songyan.ime.settings.state.FontEntry
import app.songyan.ime.settings.state.FontGateway
import app.songyan.ime.settings.state.FontGatewayHolder
import app.songyan.ime.settings.state.FontImportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The Fonts page: imported fonts, the two choices and the typefaces the previews draw with. */
data class FontsUiState(
    val loaded: Boolean = false,
    val fonts: List<FontEntry> = emptyList(),
    /** The chosen file per role; empty is the system font. A choice whose file is gone reads as the system font. */
    val candidates: String = FontCascade.SYSTEM,
    val keys: String = FontCascade.SYSTEM,
    /** What each role is drawn with, fallback fonts included; null is the system font. */
    val candidateTypeface: Typeface? = null,
    val keyTypeface: Typeface? = null,
    /** Each readable font drawn alone, for its own row. */
    val rowTypefaces: Map<String, Typeface> = emptyMap(),
    val result: FontImportResult? = null,
) {
    fun chosen(role: FontRole): String = if (role == FontRole.CANDIDATES) candidates else keys
}

class FontsViewModel(
    private val gateway: FontGateway,
) : ViewModel() {
    private val state = MutableStateFlow(FontsUiState())
    val uiState: StateFlow<FontsUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                gateway.fonts,
                gateway.choice(FontRole.CANDIDATES),
                gateway.choice(FontRole.KEYS),
            ) { fonts, candidates, keys ->
                val order = fonts.map { it.file }
                val readable = fonts.filter { it.readable }.mapTo(HashSet()) { it.file }
                val rows = HashMap<String, Typeface>()
                for (font in fonts) gateway.previewTypeface(listOf(font.file))?.let { rows[font.file] = it }
                FontsUiState(
                    loaded = true,
                    fonts = fonts,
                    candidates = candidates,
                    keys = keys,
                    candidateTypeface = gateway.previewTypeface(FontCascade.files(candidates, order, readable)),
                    keyTypeface = gateway.previewTypeface(FontCascade.files(keys, order, readable)),
                    rowTypefaces = rows,
                )
            }.flowOn(Dispatchers.IO).collect { next -> state.update { next.copy(result = it.result) } }
        }
    }

    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val result = gateway.import(uris)
            state.update { it.copy(result = result) }
        }
    }

    fun consumeResult() = state.update { it.copy(result = null) }

    fun choose(
        role: FontRole,
        file: String,
    ) {
        viewModelScope.launch { gateway.choose(role, file) }
    }

    fun delete(file: String) {
        viewModelScope.launch { gateway.delete(file) }
    }

    class Factory : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(
            modelClass: Class<T>,
            extras: CreationExtras,
        ): T {
            @Suppress("UNCHECKED_CAST")
            return FontsViewModel(FontGatewayHolder.current) as T
        }
    }
}
