package app.songyan.ime.settings.ui.theme

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The light/dark choice the appearance settings offer. */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/**
 * Appearance choices the theme needs: the wallpaper-palette switch and the light/dark choice.
 * Both are persisted by the app module; the in-memory default keeps the settings app usable before
 * that implementation is installed.
 */
interface ThemePreferences {
    val dynamicColor: StateFlow<Boolean>

    val mode: StateFlow<ThemeMode>

    fun setDynamicColor(enabled: Boolean)

    fun setMode(mode: ThemeMode)
}

/** Defaults used before the app module installs its persisted implementation. */
class InMemoryThemePreferences(
    defaultDynamicColor: Boolean = true,
    defaultThemeMode: ThemeMode = ThemeMode.SYSTEM,
) : ThemePreferences {
    private val mutableDynamicColor = MutableStateFlow(defaultDynamicColor)
    private val mutableMode = MutableStateFlow(defaultThemeMode)

    override val dynamicColor: StateFlow<Boolean> = mutableDynamicColor.asStateFlow()
    override val mode: StateFlow<ThemeMode> = mutableMode.asStateFlow()

    override fun setDynamicColor(enabled: Boolean) {
        mutableDynamicColor.value = enabled
    }

    override fun setMode(mode: ThemeMode) {
        mutableMode.value = mode
    }
}

/** Process-wide holder; the app module installs the persisted implementation. */
object ThemePreferencesHolder {
    private val fallback = InMemoryThemePreferences()

    @Volatile
    private var installed: ThemePreferences? = null

    val current: ThemePreferences
        get() = installed ?: fallback

    fun install(preferences: ThemePreferences) {
        installed = preferences
    }
}
