package app.songyan.ime.ime.palette

import app.songyan.ime.appearance.AppearanceDocument

/** Appearance inputs the app graph hands to the keyboard's palette resolver. */
data class KeyboardAppearanceSettings(
    /** The applied appearance; null until the app graph has loaded it. */
    val document: AppearanceDocument?,
    /** Whether the keyboard follows the wallpaper palette (Android 12+ only). */
    val dynamicColor: Boolean,
    /**
     * The user's explicit light/dark choice; null follows the appearance document, and the
     * keyboard surface's night mode when the document follows the system.
     */
    val forcedDark: Boolean? = null,
)

/**
 * Process-wide hand-off from the app graph to the keyboard.
 *
 * The keyboard never reads appearance storage itself: the app loads the applied appearance off the
 * main thread and installs it here, so resolving a palette on the show path touches no disk.
 */
object KeyboardAppearanceRegistry {
    @Volatile
    private var settings: KeyboardAppearanceSettings? = null

    /** Bumped on every install so resolvers can drop cached palettes. */
    @Volatile
    var revision: Long = 0L
        private set

    fun install(value: KeyboardAppearanceSettings) {
        settings = value
        revision++
    }

    /** The installed settings, or null before the app graph has loaded them. */
    fun current(): KeyboardAppearanceSettings? = settings
}
