package app.songyan.ime.appearance

import app.songyan.ime.candidate.CandidateTypography

/**
 * The canonical runtime palette a keyboard surface paints with.
 *
 * The persisted [SkinDefinition] keeps the iOS-compatible colour roles only, because adding a role
 * there would make Android-exported skins unreadable by iOS. This type is the *derived* view: the
 * iOS roles are resolved from the skin, and the Android-only surfaces the iOS format has no field
 * for (candidate bar, dividers, long-press popups, gaps) are derived from those same roles, so a skin
 * author never has to enumerate them.
 *
 * One resolved palette serves both consumers: the self-drawn Canvas keyboard (`ime`) and the Compose
 * settings preview (`settings`). Colours are ARGB ints so a Canvas `Paint` takes them directly and
 * Compose wraps them in `Color(argb)`. Dynamic colour (Android 12+) is produced by the UI layer; this
 * model only carries the resolved result.
 */
data class KeyboardPalette(
    /** Keyboard window / canvas surface behind the keys. */
    val background: Int,
    /** Standard letter and digit keycap fill. */
    val keySurface: Int,
    /** Subtle key perimeter stroke; transparent when the skin draws borderless keys. */
    val keyBorder: Int,
    /** State layer drawn over a pressed key. */
    val pressed: Int,
    /** Control and functional keycap fill (shift, backspace, 123, layout switch). */
    val controlKey: Int,
    /** Perimeter stroke of a control key; transparent unless the skin declares a border. */
    val controlKeyBorder: Int,
    /** Fill of a control key in its selected/toggled state. */
    val controlKeySelected: Int,
    /** Primary glyph and letter text on keys. */
    val text: Int,
    /** Secondary labels, hints and preedit text. */
    val mutedText: Int,
    /** High-emphasis action colour (enter, active toggles). */
    val accent: Int,
    /** Glyph colour on the accent key. */
    val accentText: Int,
    /** Candidate bar background. */
    val candidateBarBackground: Int,
    /** Candidate chip fill for the highlighted candidate. */
    val candidateSelectedBackground: Int,
    /** Candidate text colour for the highlighted candidate. */
    val candidateSelectedText: Int,
    /** Structural separators and dividers. */
    val divider: Int,
    /** Background of the long-press alternates popup. */
    val popupBackground: Int,
    /** Selected cell in the long-press popup. */
    val popupSelected: Int,
    /** Ambient/spot shadow colour of the floating popup, alpha included. */
    val popupShadow: Int,
    /** Keycap corner radius in dp. */
    val keyRadiusDp: Double,
    /** Keycap border width in dp (0 = borderless). */
    val keyBorderWidthDp: Double,
    /** Whether keycaps draw a shadow at all. */
    val drawsKeyShadow: Boolean,
    /** Horizontal blank gutter inside a keycap in dp; the drawn key is inset, the hit region is not. */
    val visualInsetXDp: Double,
    /** Vertical blank gutter inside a keycap in dp. */
    val visualInsetYDp: Double,
    /** Key label size in sp. */
    val keyTextSizeSp: Double,
    /** Secondary badge size on a key (long-press digit, hint) in sp. */
    val secondaryTextSizeSp: Double,
    /** Candidate text size in sp; the candidate bar geometry tops its height up for this value. */
    val candidateTextSizeSp: Double,
    /** Candidate comment/pronunciation size in sp. */
    val candidateCommentTextSizeSp: Double,
    /** Horizontal gutter between adjacent keys in dp. */
    val keyGapHorizontalDp: Double = DEFAULT_KEY_GAP_HORIZONTAL_DP,
    /** Vertical gutter between key rows in dp. */
    val keyGapVerticalDp: Double = DEFAULT_KEY_GAP_VERTICAL_DP,
    /** Minimum accessibility touch target in dp. */
    val minTouchTargetDp: Double = DEFAULT_MIN_TOUCH_TARGET_DP,
    /** Preedit / composing text size in sp. */
    val pinyinTextSizeSp: Double = DEFAULT_PINYIN_TEXT_SIZE_SP,
    /** Size of multi-character key labels ("123", "中英") in sp. */
    val keyLabelSmallTextSizeSp: Double = DEFAULT_KEY_LABEL_SMALL_TEXT_SIZE_SP,
    /** Cell width in the long-press popup in dp. */
    val popupCellWidthDp: Double = DEFAULT_POPUP_CELL_WIDTH_DP,
    /** Cell height in the long-press popup in dp. */
    val popupCellHeightDp: Double = DEFAULT_POPUP_CELL_HEIGHT_DP,
    /** Corner radius of the long-press popup in dp. */
    val popupRadiusDp: Double = DEFAULT_POPUP_RADIUS_DP,
    /** Elevation/shadow radius of the long-press popup in dp. */
    val popupElevationDp: Double = DEFAULT_POPUP_ELEVATION_DP,
    /** Alpha of the touch state layer drawn over a pressed key. */
    val pressStateLayerAlpha: Double = DEFAULT_PRESS_STATE_LAYER_ALPHA,
    /** The skin's font family (`system`, `rounded`, `serif`, `monospaced`). */
    val fontFamily: String = "system",
    /** The skin's font weight (`regular`, `medium`, `semibold`). */
    val fontWeight: String = "regular",
    /** What the variant draws behind the keys; null leaves the plain [background] colour. */
    val backgroundFill: BackgroundFill? = null,
) {
    /**
     * Short aliases matching the current `ime` Canvas palette field names. They exist so the P5
     * switch from the IME's local palette to this type is a field-for-field copy rather than a
     * rename pass; prefer the canonical names in new code.
     */
    val key: Int get() = keySurface
    val control: Int get() = controlKey
    val muted: Int get() = mutedText
    val candidateBackground: Int get() = candidateBarBackground

    /** The candidate-area typography this palette implies. */
    val candidateTypography: CandidateTypography
        get() = CandidateTypography(candidateSize = candidateTextSizeSp, secondarySize = candidateCommentTextSizeSp)

    companion object {
        const val DEFAULT_KEY_GAP_HORIZONTAL_DP: Double = 6.0
        const val DEFAULT_KEY_GAP_VERTICAL_DP: Double = 8.0
        const val DEFAULT_MIN_TOUCH_TARGET_DP: Double = 48.0
        const val DEFAULT_PINYIN_TEXT_SIZE_SP: Double = 13.0
        const val DEFAULT_KEY_LABEL_SMALL_TEXT_SIZE_SP: Double = 14.0
        const val DEFAULT_POPUP_CELL_WIDTH_DP: Double = 48.0
        const val DEFAULT_POPUP_CELL_HEIGHT_DP: Double = 52.0
        const val DEFAULT_POPUP_RADIUS_DP: Double = 12.0
        const val DEFAULT_POPUP_ELEVATION_DP: Double = 6.0
        const val DEFAULT_PRESS_STATE_LAYER_ALPHA: Double = 0.12

        private const val TRANSPARENT: Int = 0x00000000
        private const val SHADOW_ALPHA_LIGHT: Int = 0x24000000
        private const val SHADOW_ALPHA_DARK: Int = 0x59000000

        private fun backgroundFillOf(
            skin: SkinDefinition,
            dark: Boolean,
        ): BackgroundFill? {
            val background = (if (dark) skin.variants.dark else skin.variants.light)?.background ?: return null

            fun argb(hex: String?): Int? = hex?.let { SkinColor.fromHex(it) }?.toArgb()
            return when (background.type) {
                "solid" -> argb(background.color)?.let { BackgroundFill.Solid(it) }
                "linearGradient" -> {
                    val from = argb(background.from)
                    val to = argb(background.to)
                    if (from != null && to != null) BackgroundFill.VerticalGradient(from, to) else null
                }
                else -> null
            }
        }

        /** Resolves the palette of [document] for the current light/dark state. */
        fun of(
            document: AppearanceDocument,
            dark: Boolean,
        ): KeyboardPalette = of(document.skin, dark)

        /**
         * Resolves one skin. Every Android-only role is derived from the iOS roles:
         *  - the candidate bar floats on the panel colour and its chip uses the skin's `selected`;
         *  - the popup sits on the keycap colour with the accent as its selected cell;
         *  - a border stroke is drawn only when the skin declares a border width.
         */
        fun of(
            skin: SkinDefinition,
            dark: Boolean,
        ): KeyboardPalette {
            val colors = skin.resolvedColors(dark)

            fun role(name: String): SkinColor = colors.getValue(name)

            fun argb(name: String): Int = role(name).toArgb()

            val borderWidth = skin.keycap.borderWidth
            val hasBorder = borderWidth > 0.0
            val selected = role("selected")
            return KeyboardPalette(
                background = argb("panel"),
                keySurface = argb("key"),
                keyBorder = if (hasBorder) argb("border") else TRANSPARENT,
                pressed = argb("pressed"),
                controlKey = argb("control"),
                controlKeyBorder = if (hasBorder) argb("border") else TRANSPARENT,
                controlKeySelected = argb("selected"),
                text = argb("text"),
                mutedText = argb("secondaryText"),
                accent = argb("accent"),
                accentText = argb("accentText"),
                candidateBarBackground = argb("panel"),
                candidateSelectedBackground = argb("selected"),
                // The chip's glyph must read on the chip, which is why it is re-derived rather than
                // reusing the keycap-corrected text colour.
                candidateSelectedText = role("text").contrasting(selected).toArgb(),
                divider = argb("border"),
                popupBackground = argb("key"),
                popupSelected = argb("selected"),
                popupShadow = if (dark) SHADOW_ALPHA_DARK else SHADOW_ALPHA_LIGHT,
                keyRadiusDp = skin.keycap.cornerRadius,
                keyBorderWidthDp = borderWidth,
                drawsKeyShadow = skin.keycap.shadow != "none",
                visualInsetXDp = skin.spacing.visualInsetX,
                visualInsetYDp = skin.spacing.visualInsetY,
                keyTextSizeSp = skin.typography.keySize,
                secondaryTextSizeSp = skin.typography.secondarySize,
                candidateTextSizeSp = skin.typography.candidateSize,
                candidateCommentTextSizeSp = skin.typography.secondarySize,
                fontFamily = skin.typography.family,
                fontWeight = skin.typography.weight,
                backgroundFill = backgroundFillOf(skin, dark),
            )
        }
    }
}

/** What a skin variant draws behind the keys, as ARGB ints. */
sealed interface BackgroundFill {
    data class Solid(
        val color: Int,
    ) : BackgroundFill

    /** [from] at the top edge, [to] at the bottom edge. */
    data class VerticalGradient(
        val from: Int,
        val to: Int,
    ) : BackgroundFill
}
