package app.songyan.ime.ime

/**
 * Production-ready visual design tokens for the Songyan on-screen keyboard.
 *
 * Implemented in pure Kotlin (zero Android SDK / Compose dependencies) so it can be compiled,
 * tested, and shared across modules (e.g. core-appearance, ime, unit tests).
 *
 * Colors are 64-bit ARGB Longs (e.g. 0xFF1E2B24L).
 * To pass to [android.graphics.Paint.setColor], simply call [toArgbInt] or [Long.toInt].
 */
data class KeyboardDesignTokens(
    /** Keyboard window / canvas surface background. */
    val background: Long,
    /** Standard letter / digit keycap fill. */
    val keySurface: Long,
    /** Subtle key perimeter stroke (0x00000000 if borderless). */
    val keyBorder: Long,
    /** State layer / fill when a key is touched. */
    val pressed: Long,
    /** Control / functional keycap fill (Shift, Backspace, 123, Globe). */
    val controlKey: Long,
    /** Subtle control key perimeter stroke. */
    val controlKeyBorder: Long,
    /** Primary glyph and letter text on keys. */
    val text: Long,
    /** Secondary labels, hints, and preedit text. */
    val mutedText: Long,
    /** High-emphasis action color (Enter key, active toggles). */
    val accent: Long,
    /** Text/glyph on the accent key (Enter). */
    val accentText: Long,
    /** Candidate bar background (matches or gently floats over keyboard background). */
    val candidateBarBackground: Long,
    /** Candidate chip highlight fill for the top-ranked candidate. */
    val candidateSelectedBackground: Long,
    /** Candidate text color for the top-ranked candidate. */
    val candidateSelectedText: Long,
    /** Structural separator / divider lines. */
    val divider: Long,
    /** Background surface for the long-press alternates popup. */
    val popupBackground: Long,
    /** Selected cell highlight in the long-press popup. */
    val popupSelected: Long,
    /** Ambient + spot shadow color for the floating popup. */
    val popupShadow: Long,
    /** Vertical pitch of one key row, its share of the gutter included (dp). */
    val rowHeightDp: Float = 56f,
    /** Horizontal gutter between adjacent keys (dp). */
    val keyGapHorizontalDp: Float = 5.5f,
    /** Vertical gutter between adjacent key rows (dp). */
    val keyGapVerticalDp: Float = 9f,
    /** Horizontal padding outside the key area, so the edge keys do not touch the screen (dp). */
    val keyAreaHorizontalPaddingDp: Float = 3f,
    /** Empty band kept below the last key row (dp); it is the phone's bottom breathing room. */
    val keyAreaBottomGapDp: Float = 8f,
    /**
     * The user's extra empty band below [keyAreaBottomGapDp] (dp). Separate from it so the rows keep
     * their place; only the keyboard's height grows.
     */
    val keyAreaExtraBottomDp: Float = 0f,
    /** Corner radius of every keycap (dp). */
    val keyRadiusDp: Float = 8f,
    /** Minimum accessibility touch target bounding box (dp). */
    val minTouchTargetDp: Float = 48f,
    /**
     * Height of the candidate / toolbar row (dp): one row in every state, so a composition, a page
     * switch or the idle toolbar never resizes the keyboard window under the editor. It is also the
     * candidate touch-target height.
     */
    val candidateBarHeightDp: Float = 48f,
    /** Font size of primary candidate Hanzi characters (sp). */
    val candidateTextSizeSp: Float = 18f,
    /** Font size of candidate comments / pronunciation labels (sp). */
    val candidateCommentTextSizeSp: Float = 12f,
    /** Font size of preedit / composing pinyin string (sp). */
    val pinyinTextSizeSp: Float = 13f,
    /** Font size of every character key label, a merged two-letter key included (sp). */
    val keyLabelTextSizeSp: Float = 22f,
    /** Font size of multi-character control key labels (sp). */
    val keyLabelSmallTextSizeSp: Float = 14f,
    /** Font size of secondary corner badges (e.g. long-press digits on top row) (sp). */
    val keySecondaryLabelTextSizeSp: Float = 11f,
    /** Font size of a nine-key digit group label such as "ABC" (sp). */
    val phoneDigitTextSizeSp: Float = 18f,
    /** Font size of the Space key label (sp). */
    val spaceKeyTextSizeSp: Float = 16f,
    /** Font size of the 中/英 key label (sp). */
    val languageKeyTextSizeSp: Float = 14f,
    /** Font size of the 123 / 符号 key labels (sp). */
    val pageKeyTextSizeSp: Float = 14f,
    /** Font size of the Return / editor-action key label (sp). */
    val enterKeyTextSizeSp: Float = 15f,
    /** Inset of a corner hint from the keycap's top-right corner (dp). */
    val secondaryLabelInsetDp: Float = 3f,
    /** Corner radius of a candidate chip and of the selected candidate pill (dp). */
    val candidateChipRadiusDp: Float = 10f,
    /**
     * Gap between a chip's pill and its cell edge, doubled (dp); also the space between a candidate
     * and its reading comment, halved.
     */
    val candidateChipGapDp: Float = 8f,
    /**
     * Space between two cells of the collapsed strip (dp). None: cells touch, so every point of the
     * row commits a candidate, and the visible gap between two words is their two paddings.
     */
    val candidateCellSpacingDp: Float = 0f,
    /** Left padding of the preedit line and of the candidate row (dp). */
    val candidateStartPaddingDp: Float = 12f,
    /**
     * Horizontal padding inside a candidate cell, around its text (dp). Two of them separate two
     * words of the strip: 20 dp, a little more than one 18 sp Han character, so words read apart
     * without the row looking spread out.
     */
    val candidateChipPaddingDp: Float = 10f,
    /**
     * Room a key label leaves at its keycap's sides, both together, before it is shrunk to fit (dp).
     * Its own value, so a candidate bar's padding from an appearance file never shrinks key labels.
     */
    val keyLabelPaddingDp: Float = 10f,
    /**
     * A candidate cell is never narrower than this (dp): the iOS candidate cell minimum. With the
     * 48 dp row height and no space between cells, a one-character candidate keeps a 44 x 48 dp
     * target that touches its neighbours, so no tap lands between two words.
     */
    val candidateMinChipWidthDp: Float = 44f,
    /** Cell width in long-press alternate popup (dp). */
    val popupCellWidthDp: Float = 48f,
    /** Cell height in long-press alternate popup (dp). */
    val popupCellHeightDp: Float = 52f,
    /** Corner radius of the long-press alternate popup (dp). */
    val popupRadiusDp: Float = 12f,
    /** Elevation / shadow radius of the long-press alternate popup (dp). */
    val popupElevationDp: Float = 6f,
    /** Alpha for touch ripple / highlight layer overlay. */
    val pressStateLayerAlpha: Float = 0.12f,
    /** Stroke width of a keycap border (dp), when the key has a border colour. */
    val keyBorderWidthDp: Float = 1f,
    /** Opacity of the black 1 dp drop shadow under a resting keycap; 0 draws none. */
    val keyShadowOpacity: Float = 0f,
    /** The skin's font family and weight, resolved to a typeface by [KeyboardTypefaces]. */
    val fontFamily: String = "system",
    val fontWeight: String = "regular",
    /** What the keyboard draws behind the keys instead of the plain [background]; null for none. */
    val backgroundFill: app.songyan.ime.appearance.BackgroundFill? = null,
) {
    /** Helper to convert a 64-bit ARGB Long to a 32-bit signed Int for Android Canvas Paint. */
    fun toArgbInt(color: Long): Int = color.toInt()

    /**
     * Total keyboard content height (dp): candidate bar plus the four key rows plus the bands kept
     * below them. Derived, so a row pitch or bar change can never leave a second number behind.
     */
    val totalHeightDp: Float get() =
        candidateBarHeightDp + KEY_ROWS * rowHeightDp + keyAreaBottomGapDp +
            keyAreaExtraBottomDp

    /**
     * The compact variant for a phone landscape or a multi-window pane with a short height: 40 dp
     * rows, a shorter candidate bar and slightly smaller type, so the keyboard stays near half of a
     * phone turned sideways instead of covering most of it.
     */
    fun forCompactWindow(): KeyboardDesignTokens =
        copy(
            rowHeightDp = 40f,
            keyGapVerticalDp = 5f,
            keyAreaBottomGapDp = 5f,
            candidateBarHeightDp = 40f,
            candidateTextSizeSp = 16f,
            candidateCommentTextSizeSp = 10f,
            pinyinTextSizeSp = 11f,
            keyLabelTextSizeSp = 18f,
            keyLabelSmallTextSizeSp = 12f,
            keySecondaryLabelTextSizeSp = 9f,
            phoneDigitTextSizeSp = 15f,
            spaceKeyTextSizeSp = 14f,
            languageKeyTextSizeSp = 12f,
            pageKeyTextSizeSp = 12f,
            enterKeyTextSizeSp = 13f,
            candidateChipRadiusDp = 8f,
            candidateChipGapDp = 6f,
            candidateStartPaddingDp = 10f,
            candidateChipPaddingDp = 8f,
            keyLabelPaddingDp = 8f,
            candidateMinChipWidthDp = 40f,
            popupCellWidthDp = 44f,
            popupCellHeightDp = 44f,
            popupRadiusDp = 10f,
            popupElevationDp = 4f,
        )

    companion object {
        /** Key rows of every page; the nine-key shell shares the same four bands. */
        const val KEY_ROWS = 4

        // =========================================================================
        // Fallback Palette: Songyan Pine-Soot Ink and Xuan Paper
        // Dignified, calm, high-contrast, free from glare and visual noise.
        // =========================================================================

        /**
         * Light theme: Inspired by fresh pine ink on fine Xuan paper.
         * Soft sage-tinted neutral background, crisp white key surfaces,
         * deep charcoal ink typography, and deep pine-green accent.
         */
        fun light(): KeyboardDesignTokens =
            KeyboardDesignTokens(
                background = 0xFFECEFEAL,
                keySurface = 0xFFFFFFFFL,
                keyBorder = 0x00000000L, // Borderless in light mode; subtle elevation through contrast
                pressed = 0xFFD2DDD3L,
                controlKey = 0xFFDDE3DAL,
                controlKeyBorder = 0x00000000L,
                text = 0xFF191D1AL, // Rich pine soot ink black (Contrast > 14:1)
                mutedText = 0xFF586159L, // Calm slate grey (Contrast > 5.5:1)
                accent = 0xFF2A593FL, // Songyan Pine Green
                accentText = 0xFFFFFFFFL, // Crisp white on accent (Contrast > 6.5:1)
                candidateBarBackground = 0xFFECEFEAL,
                candidateSelectedBackground = 0xFFD8E6DBL,
                candidateSelectedText = 0xFF133824L,
                divider = 0xFFD5DDD4L,
                popupBackground = 0xFFFAFCF9L,
                popupSelected = 0xFFD8E6DBL,
                popupShadow = 0x24000000L,
            )

        /**
         * Dark theme: Inspired by nocturnal pine woods and deep ink blocks.
         * Elevated charcoal surfaces (not harsh pure black), luminous sage accent,
         * and silvery ink text with comfortable eye strain relief.
         */
        fun dark(): KeyboardDesignTokens =
            KeyboardDesignTokens(
                background = 0xFF121513L,
                keySurface = 0xFF232724L,
                keyBorder = 0x1AFFFFFFL, // 10% white perimeter highlight for key separation
                pressed = 0xFF3B433DL,
                controlKey = 0xFF1B1E1CL,
                controlKeyBorder = 0x0FFFFFFFL,
                text = 0xFFE2E5E0L, // Silvery ink white (Contrast > 10:1)
                mutedText = 0xFF959F96L, // Muted sage slate (Contrast > 5.5:1)
                accent = 0xFF8FD7AAL, // Luminous pine jade
                accentText = 0xFF003820L, // Deep forest green on accent (Contrast > 8:1)
                candidateBarBackground = 0xFF121513L,
                candidateSelectedBackground = 0xFF1C3426L,
                candidateSelectedText = 0xFF8FD7AAL,
                divider = 0xFF272C28L,
                popupBackground = 0xFF282D29L,
                popupSelected = 0xFF3B483EL,
                popupShadow = 0x59000000L,
            )

        /**
         * Dynamic Color (Material You, Android 12+ / API 31+).
         * Derives all visual roles directly from system wallpaper-generated tonal palettes.
         */
        fun fromDynamic(
            roles: DynamicColorRoles,
            isDark: Boolean,
        ): KeyboardDesignTokens =
            if (isDark) {
                KeyboardDesignTokens(
                    background = roles.surfaceContainerLowest,
                    keySurface = roles.surfaceContainerHighest,
                    keyBorder = 0x00000000L,
                    pressed = roles.secondaryContainer,
                    controlKey = roles.surfaceContainerHigh,
                    controlKeyBorder = 0x00000000L,
                    text = roles.onSurface,
                    mutedText = roles.onSurfaceVariant,
                    accent = roles.primary,
                    accentText = roles.onPrimary,
                    candidateBarBackground = roles.surfaceContainerLowest,
                    candidateSelectedBackground = roles.primaryContainer,
                    candidateSelectedText = roles.onPrimaryContainer,
                    divider = roles.outlineVariant,
                    popupBackground = roles.surfaceContainerHighest,
                    popupSelected = roles.primaryContainer,
                    popupShadow = 0x66000000L,
                )
            } else {
                KeyboardDesignTokens(
                    background = roles.surfaceContainerHigh,
                    keySurface = roles.surfaceContainerLowest,
                    keyBorder = 0x00000000L,
                    pressed = roles.secondaryContainer,
                    // Tone 80, clearly darker than the tone 92 background; the container tones sit only
                    // two apart, too close to read as a key.
                    controlKey = roles.outlineVariant,
                    controlKeyBorder = 0x00000000L,
                    text = roles.onSurface,
                    mutedText = roles.onSurfaceVariant,
                    accent = roles.primary,
                    accentText = roles.onPrimary,
                    candidateBarBackground = roles.surfaceContainerHigh,
                    candidateSelectedBackground = roles.primaryContainer,
                    candidateSelectedText = roles.onPrimaryContainer,
                    divider = roles.outlineVariant,
                    popupBackground = roles.surfaceContainerHighest,
                    popupSelected = roles.primaryContainer,
                    popupShadow = 0x26000000L,
                )
            }
    }
}

/**
 * Standard Material 3 color roles extracted dynamically from the system palette.
 * Colors are represented as 64-bit ARGB Longs.
 */
data class DynamicColorRoles(
    val primary: Long,
    val onPrimary: Long,
    val primaryContainer: Long,
    val onPrimaryContainer: Long,
    val secondary: Long,
    val onSecondary: Long,
    val secondaryContainer: Long,
    val onSecondaryContainer: Long,
    val tertiary: Long,
    val onTertiary: Long,
    val tertiaryContainer: Long,
    val onTertiaryContainer: Long,
    val background: Long,
    val onBackground: Long,
    val surface: Long,
    val onSurface: Long,
    val surfaceVariant: Long,
    val onSurfaceVariant: Long,
    val surfaceContainerLowest: Long,
    val surfaceContainerLow: Long,
    val surfaceContainer: Long,
    val surfaceContainerHigh: Long,
    val surfaceContainerHighest: Long,
    val outline: Long,
    val outlineVariant: Long,
)
