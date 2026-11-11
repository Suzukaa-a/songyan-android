package app.songyan.ime.ime

import android.content.Context
import app.songyan.ime.ime.palette.KeyboardPalettes

data class KeyboardPalette(
    val background: Int,
    val key: Int,
    val control: Int,
    val text: Int,
    val muted: Int,
    val pressed: Int,
    val accent: Int,
    val accentText: Int,
    val candidateBackground: Int,
    val candidateSelectedBackground: Int,
    val candidateSelectedText: Int,
    val keyBorder: Int,
    val controlBorder: Int,
    val divider: Int,
    val popupBackground: Int,
    val popupSelected: Int,
    val popupShadow: Int,
    val tokens: KeyboardDesignTokens,
) {
    /**
     * Whether the keyboard surface is light. The navigation bar's icons follow this, not the system
     * theme: a keyboard themed dark in a light system (or the reverse) must not leave dark icons on
     * a dark bar.
     */
    val isLight: Boolean get() = luminance(background) > LIGHT_LUMINANCE

    /**
     * What a view inside the keyboard paints behind itself: the panel colour, or nothing when the
     * skin has a gradient or picture, which [KeyboardBackgroundDrawable] paints once for the window.
     */
    val surface: Int get() = if (tokens.backgroundFill != null) 0 else background

    companion object {
        private const val LIGHT_LUMINANCE = 0.5

        /** WCAG relative luminance of an opaque ARGB int, without platform classes. */
        private fun luminance(color: Int): Double {
            fun channel(shift: Int): Double {
                val value = ((color shr shift) and 0xFF) / 255.0
                return if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        }

        /**
         * The palette for the keyboard surface of [context]'s current configuration: the wallpaper
         * palette when dynamic colour applies, otherwise the applied appearance, otherwise the
         * Android default appearance.
         */
        fun from(context: Context): KeyboardPalette {
            val palette = KeyboardPalettes.current(context)
            // The appearance's single-row size applies before the window budget, which may still
            // shrink the bar in a short window.
            val styled = CandidateBarStyle.current().applyTo(palette.tokens)
            val tokens = KeyboardGeometry.applyTo(styled, context)
            return if (tokens === palette.tokens) palette else palette.copy(tokens = tokens)
        }

        /** The palette for an explicit light/dark state; previews and tests use this. */
        fun resolve(
            context: Context,
            dark: Boolean,
        ): KeyboardPalette = KeyboardPalettes.resolve(context, dark)

        fun fromTokens(tokens: KeyboardDesignTokens) =
            KeyboardPalette(
                background = tokens.background.toInt(),
                key = tokens.keySurface.toInt(),
                control = tokens.controlKey.toInt(),
                text = tokens.text.toInt(),
                muted = tokens.mutedText.toInt(),
                pressed = tokens.pressed.toInt(),
                accent = tokens.accent.toInt(),
                accentText = tokens.accentText.toInt(),
                candidateBackground = tokens.candidateBarBackground.toInt(),
                candidateSelectedBackground = tokens.candidateSelectedBackground.toInt(),
                candidateSelectedText = tokens.candidateSelectedText.toInt(),
                keyBorder = tokens.keyBorder.toInt(),
                controlBorder = tokens.controlKeyBorder.toInt(),
                divider = tokens.divider.toInt(),
                popupBackground = tokens.popupBackground.toInt(),
                popupSelected = tokens.popupSelected.toInt(),
                popupShadow = tokens.popupShadow.toInt(),
                tokens = tokens,
            )
    }
}
