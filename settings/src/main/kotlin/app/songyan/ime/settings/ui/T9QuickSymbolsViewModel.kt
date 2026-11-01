package app.songyan.ime.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.T9QuickSymbolStore
import app.songyan.ime.settings.state.SettingsPreferences
import app.songyan.ime.settings.state.SettingsPreferencesHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why the quick-symbol list as typed cannot be saved; the screen maps each to copy. */
enum class T9QuickSymbolsProblem {
    /** Empty, too long, or holding a space, control or non-BMP character. */
    INVALID,
    DUPLICATE,
}

/** The quick-symbol rules and the keyboard preview, pure so the screen and its tests share them. */
object T9QuickSymbolsDraft {
    /** The first reason [symbols] cannot be saved, or null when it can. */
    fun check(symbols: String): T9QuickSymbolsProblem? =
        when {
            !T9QuickSymbolStore.isValid(symbols) -> T9QuickSymbolsProblem.INVALID
            symbols.toSet().size != symbols.length -> T9QuickSymbolsProblem.DUPLICATE
            else -> null
        }

    /** The keys the nine-key keyboard draws for [symbols]: the first three on the side rail, the rest on the first key. */
    fun preview(symbols: String): Pair<List<String>, List<String>> {
        val values = BuiltinLayouts.t9QuickSymbolValues(symbols, ascii = false)
        return values.take(RAIL_SLOTS) to values.drop(RAIL_SLOTS)
    }

    private const val RAIL_SLOTS = 3
}

/** [saved] is empty while the user keeps the default sequence. */
data class T9QuickSymbolsUiState(
    val loaded: Boolean = false,
    val saved: String = "",
) {
    /** What the editor starts from: the saved list, or the default sequence. */
    val effective: String get() = saved.ifEmpty { T9QuickSymbolStore.DEFAULT_SYMBOLS }
}

/** The 九键快捷符号 list; the keyboard follows the same stored value, so a save shows on its next render. */
class T9QuickSymbolsViewModel(
    private val preferences: SettingsPreferences,
) : ViewModel() {
    private val mutableState = MutableStateFlow(T9QuickSymbolsUiState())
    val uiState: StateFlow<T9QuickSymbolsUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.t9QuickSymbols.collect { saved ->
                mutableState.update { T9QuickSymbolsUiState(loaded = true, saved = saved) }
            }
        }
    }

    /** Stores [symbols] after the same validation the editor shows; returns whether it was stored. */
    fun save(symbols: String): Boolean {
        if (T9QuickSymbolsDraft.check(symbols) != null) return false
        viewModelScope.launch {
            // The default sequence is the absence of a choice, not a copy of it, so a later change of
            // the default reaches users who never edited the list.
            preferences.setT9QuickSymbols(symbols.takeIf { it != T9QuickSymbolStore.DEFAULT_SYMBOLS })
        }
        return true
    }

    fun restoreDefault() {
        viewModelScope.launch { preferences.setT9QuickSymbols(null) }
    }

    class Factory(
        private val preferences: SettingsPreferences = SettingsPreferencesHolder.current,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = T9QuickSymbolsViewModel(preferences) as T
    }
}
