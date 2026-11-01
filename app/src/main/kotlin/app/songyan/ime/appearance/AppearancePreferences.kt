package app.songyan.ime.appearance

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.songyan.ime.prefs.orDefaultsOnIoError
import app.songyan.ime.prefs.preferencesCorruptionHandler
import app.songyan.ime.settings.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.appearanceDataStore: DataStore<Preferences> by preferencesDataStore(
    "appearance",
    corruptionHandler = preferencesCorruptionHandler("appearance"),
)

/**
 * Appearance choices that are not part of a skin file.
 *
 * The applied appearance itself is persisted by [AppearanceStore] in
 * `filesDir/appearance/active.json`; only the wallpaper-palette switch and the light/dark choice
 * live here.
 */
class AppearancePreferences(
    context: Context,
) {
    private val appContext = context.applicationContext

    /** Whether the keyboard follows the wallpaper palette; only effective on Android 12+. */
    val dynamicColor: Flow<Boolean> =
        appContext.appearanceDataStore.data
            .orDefaultsOnIoError()
            .map { it[KEY_DYNAMIC_COLOR] ?: defaultDynamicColor() }
            .distinctUntilChanged()

    /** The light/dark choice, shared by the settings app and the keyboard. */
    val themeMode: Flow<ThemeMode> =
        appContext.appearanceDataStore.data
            .orDefaultsOnIoError()
            .map(::storedThemeMode)
            .distinctUntilChanged()

    suspend fun setDynamicColor(value: Boolean) {
        appContext.appearanceDataStore.edit { it[KEY_DYNAMIC_COLOR] = value }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        appContext.appearanceDataStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    private fun storedThemeMode(preferences: Preferences): ThemeMode =
        ThemeMode.entries.firstOrNull { it.name == preferences[KEY_THEME_MODE] } ?: ThemeMode.SYSTEM

    companion object {
        /** Dynamic colour is the Android default appearance from Android 12 on. */
        fun defaultDynamicColor(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= Build.VERSION_CODES.S

        private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
    }
}
