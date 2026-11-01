package app.songyan.ime.appearance

import app.songyan.ime.settings.ui.theme.ThemeMode

/** The appearance the keyboard and the settings screen work from. */
data class AppearanceState(
    /** The applied appearance; never null, an unreadable store yields the Android default. */
    val document: AppearanceDocument,
    /** Whether the keyboard follows the wallpaper palette (effective on Android 12+). */
    val dynamicColor: Boolean,
    /** The user's light/dark choice; the keyboard follows it too. */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** The rejection that degraded the read; null on a clean read. */
    val error: AppearanceError? = null,
)
