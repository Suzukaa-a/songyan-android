package app.songyan.ime.ime.palette

import app.songyan.ime.ime.KeyboardDesignTokens
import app.songyan.ime.ime.KeyboardPalette
import app.songyan.ime.appearance.KeyboardPalette as AppearancePalette

/**
 * Adapts the shared appearance model's resolved palette to the palette the Canvas keyboard paints
 * with. Field for field, so nothing a view reads changes shape; only the source of the values does.
 */
internal object ImePalettes {
    fun of(palette: AppearancePalette): KeyboardPalette = KeyboardPalette.fromTokens(tokensOf(palette))

    /**
     * The design tokens of [palette].
     *
     * The skin's numbers are taken literally, so one file means the same on both apps: 1 pt is 1 dp
     * (1 sp for text). The visible gap between two keys is twice the skin's inset. Row height stays
     * the user's key-height setting, and the platform still adds a hairline outline where a dark
     * control key would otherwise vanish.
     */
    fun tokensOf(palette: AppearancePalette): KeyboardDesignTokens =
        ensureControlSeparation(
            KeyContrast.separate(
                KeyboardDesignTokens(
                    background = argb(palette.background),
                    keySurface = argb(palette.keySurface),
                    keyBorder = argb(palette.keyBorder),
                    pressed = argb(palette.pressed),
                    controlKey = argb(palette.controlKey),
                    controlKeyBorder = argb(palette.controlKeyBorder),
                    text = argb(palette.text),
                    mutedText = argb(palette.mutedText),
                    accent = argb(palette.accent),
                    accentText = argb(palette.accentText),
                    candidateBarBackground = argb(palette.candidateBarBackground),
                    candidateSelectedBackground = argb(palette.candidateSelectedBackground),
                    candidateSelectedText = argb(palette.candidateSelectedText),
                    divider = argb(palette.divider),
                    popupBackground = argb(palette.popupBackground),
                    popupSelected = argb(palette.popupSelected),
                    popupShadow = argb(palette.popupShadow),
                    keyRadiusDp = palette.keyRadiusDp.toFloat(),
                    keyBorderWidthDp = if (palette.keyBorderWidthDp > 0.0) palette.keyBorderWidthDp.toFloat() else 1f,
                    keyShadowOpacity = if (palette.drawsKeyShadow) KEY_SHADOW_OPACITY else 0f,
                    keyGapHorizontalDp = (2 * palette.visualInsetXDp).toFloat(),
                    keyGapVerticalDp = (2 * palette.visualInsetYDp).toFloat(),
                    keyLabelTextSizeSp = palette.keyTextSizeSp.toFloat(),
                    keySecondaryLabelTextSizeSp = palette.secondaryTextSizeSp.toFloat(),
                    candidateTextSizeSp = palette.candidateTextSizeSp.toFloat(),
                    fontFamily = palette.fontFamily,
                    fontWeight = palette.fontWeight,
                    backgroundFill = palette.backgroundFill,
                ),
            ),
        )

    /**
     * On a dark palette a control key can sit within a few luminance steps of the canvas and read
     * as a hole rather than a key. The palette's own divider is then used as the key outline, which
     * is a colour the appearance already declares, so no new colour is invented here.
     */
    private fun ensureControlSeparation(tokens: KeyboardDesignTokens): KeyboardDesignTokens {
        val dark = luminance(tokens.background) < DARK_LUMINANCE
        if (!dark || tokens.controlKeyBorder != 0L || (tokens.controlKey shr 24) and 0xFFL != 0xFFL) return tokens
        if (contrast(tokens.controlKey, tokens.background) >= MINIMUM_CONTROL_CONTRAST) return tokens
        return tokens.copy(controlKeyBorder = tokens.divider)
    }

    /** WCAG relative luminance of a 64-bit ARGB colour; the unit tests run without Android. */
    private fun luminance(color: Long): Double {
        fun channel(shift: Int): Double {
            val value = ((color shr shift) and 0xFF).toDouble() / 255.0
            return if (value <= 0.03928) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private fun contrast(
        a: Long,
        b: Long,
    ): Double {
        val first = luminance(a)
        val second = luminance(b)
        return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
    }

    /** The iOS keycap's resting shadow opacity. */
    private const val KEY_SHADOW_OPACITY = 0.18f

    /** Canvas luminance below which the palette is treated as the dark theme. */
    private const val DARK_LUMINANCE = 0.2

    /** Minimum fill contrast a control key needs against the canvas before it gets an outline. */
    private const val MINIMUM_CONTROL_CONTRAST = 1.3

    /**
     * [colors] with the metrics of [appearance]: dynamic colour replaces the wallpaper-independent
     * colours only, so a skin's key geometry and type sizes survive the switch.
     */
    fun withMetricsOf(
        colors: KeyboardDesignTokens,
        appearance: KeyboardDesignTokens,
    ): KeyboardDesignTokens =
        colors.copy(
            keyGapHorizontalDp = appearance.keyGapHorizontalDp,
            keyGapVerticalDp = appearance.keyGapVerticalDp,
            keyRadiusDp = appearance.keyRadiusDp,
            keyBorderWidthDp = appearance.keyBorderWidthDp,
            keyShadowOpacity = appearance.keyShadowOpacity,
            fontFamily = appearance.fontFamily,
            fontWeight = appearance.fontWeight,
            minTouchTargetDp = appearance.minTouchTargetDp,
            candidateTextSizeSp = appearance.candidateTextSizeSp,
            candidateCommentTextSizeSp = appearance.candidateCommentTextSizeSp,
            pinyinTextSizeSp = appearance.pinyinTextSizeSp,
            keyLabelTextSizeSp = appearance.keyLabelTextSizeSp,
            keyLabelSmallTextSizeSp = appearance.keyLabelSmallTextSizeSp,
            keySecondaryLabelTextSizeSp = appearance.keySecondaryLabelTextSizeSp,
            popupCellWidthDp = appearance.popupCellWidthDp,
            popupCellHeightDp = appearance.popupCellHeightDp,
            popupRadiusDp = appearance.popupRadiusDp,
            popupElevationDp = appearance.popupElevationDp,
            pressStateLayerAlpha = appearance.pressStateLayerAlpha,
        )

    /** The appearance model stores ARGB ints; the design tokens keep them as 64-bit ARGB. */
    private fun argb(color: Int): Long = color.toLong() and 0xFFFFFFFFL
}
