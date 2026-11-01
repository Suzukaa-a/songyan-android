package app.songyan.ime.ime.palette

import app.songyan.ime.appearance.AppearanceDocument
import app.songyan.ime.appearance.AppearancePresets
import app.songyan.ime.appearance.HostKeyboardAppearance
import app.songyan.ime.appearance.KeyboardAppearancePolicy

/**
 * The appearance the keyboard paints with, decided from plain values so the rule set is testable
 * without a device: which document, which variant, and whether the wallpaper palette wins.
 */
data class PaletteSelection(
    val document: AppearanceDocument,
    val dark: Boolean,
    val dynamic: Boolean,
) {
    companion object {
        /** Android 12 is the first version that publishes the wallpaper palettes. */
        const val DYNAMIC_COLOR_MIN_API: Int = 31

        /**
         * The live keyboard: the user's explicit light/dark choice wins, then the appearance
         * document's own choice, and a system-following appearance follows [systemDark]. Fails
         * closed to the Android default appearance (`builtin-songyan`) when the app graph has not
         * delivered an appearance yet.
         */
        fun of(
            settings: KeyboardAppearanceSettings?,
            sdkInt: Int,
            systemDark: Boolean,
        ): PaletteSelection = variant(settings, sdkInt, darkOf(settings, systemDark))

        /** An explicit light/dark variant, for previews that must ignore the theme choice. */
        fun variant(
            settings: KeyboardAppearanceSettings?,
            sdkInt: Int,
            dark: Boolean,
        ): PaletteSelection =
            PaletteSelection(
                document = settings?.document ?: AppearancePresets.androidDefault,
                dark = dark,
                dynamic = settings?.dynamicColor == true && sdkInt >= DYNAMIC_COLOR_MIN_API,
            )

        private fun darkOf(
            settings: KeyboardAppearanceSettings?,
            systemDark: Boolean,
        ): Boolean {
            settings?.forcedDark?.let { return it }
            val document = settings?.document ?: AppearancePresets.androidDefault
            return KeyboardAppearancePolicy.resolvedDark(
                skinIsSystemDefault = document.isSystemDefault,
                skinAppearance = document.appearance,
                hostAppearance = HostKeyboardAppearance.UNSPECIFIED,
                systemDark = systemDark,
                // The keyboard window is part of the app's own surface, so its night mode is
                // already the authoritative trait; the field's request cannot lag behind it.
                systemTraitKnown = true,
            )
        }
    }
}
