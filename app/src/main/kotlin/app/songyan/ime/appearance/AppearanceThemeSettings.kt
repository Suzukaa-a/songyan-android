package app.songyan.ime.appearance

import app.songyan.ime.settings.ui.theme.ThemeMode
import app.songyan.ime.settings.ui.theme.ThemePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The settings app's theme choices, backed by the appearance repository, so the settings app and
 * the keyboard follow one stored value instead of two.
 */
class AppearanceThemeSettings(
    private val repository: AppearanceRepository,
    private val scope: CoroutineScope,
) : ThemePreferences {
    override val dynamicColor: StateFlow<Boolean> =
        repository.state
            .map { it.dynamicColor }
            .stateIn(scope, SharingStarted.Eagerly, repository.state.value.dynamicColor)

    override val mode: StateFlow<ThemeMode> =
        repository.state
            .map { it.themeMode }
            .stateIn(scope, SharingStarted.Eagerly, repository.state.value.themeMode)

    override fun setDynamicColor(enabled: Boolean) {
        scope.launch { runCatching { repository.setDynamicColor(enabled) } }
    }

    override fun setMode(mode: ThemeMode) {
        scope.launch { runCatching { repository.setThemeMode(mode) } }
    }
}
