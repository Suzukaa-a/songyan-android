package app.songyan.ime.ime.palette

import app.songyan.ime.ime.DynamicColorRoles
import app.songyan.ime.ime.KeyboardDesignTokens

/**
 * Maps the wallpaper-derived tonal palettes onto the keyboard's colour roles.
 *
 * Role selection follows the Material 3 dynamic scheme (primary at tone 40 in light and 80 in dark,
 * surfaces from the neutral palettes, and so on). The resulting tokens then pass through a contrast
 * gate: any text role that does not reach WCAG AA on every surface it is drawn on is replaced,
 * because a wallpaper palette is user data and may sit anywhere on the luminance scale.
 */
object DynamicAppearancePalette {
    fun roles(
        palettes: TonalPalettes,
        dark: Boolean,
    ): DynamicColorRoles =
        if (dark) {
            darkRoles(palettes)
        } else {
            lightRoles(palettes)
        }

    /** The keyboard design tokens for [palettes], with every text role readable by construction. */
    fun tokens(
        palettes: TonalPalettes,
        dark: Boolean,
    ): KeyboardDesignTokens {
        val base = KeyContrast.separate(KeyboardDesignTokens.fromDynamic(roles(palettes, dark), dark))
        return base.copy(
            text =
                Argb.readableOn(
                    base.text,
                    base.keySurface,
                    base.background,
                    base.controlKey,
                    base.candidateBarBackground,
                ),
            mutedText =
                Argb.readableOn(
                    base.mutedText,
                    base.keySurface,
                    base.background,
                    base.controlKey,
                    base.popupBackground,
                ),
            accentText = Argb.readableOn(base.accentText, base.accent),
            candidateSelectedText = Argb.readableOn(base.candidateSelectedText, base.candidateSelectedBackground),
        )
    }

    /**
     * Whether every text role reads on every surface it is painted on.
     *
     * A wallpaper palette is user data: a fully saturated one can leave surfaces whose luminance is
     * so central that neither black nor white reaches WCAG AA. The resolver refuses such palettes and
     * keeps the applied appearance instead, so the keyboard is never shipped unreadable colours.
     */
    fun isReadable(tokens: KeyboardDesignTokens): Boolean {
        val surfaces =
            listOf(
                tokens.keySurface,
                tokens.background,
                tokens.controlKey,
                tokens.candidateBarBackground,
            )

        fun reads(
            foreground: Long,
            background: Long,
        ): Boolean = Argb.contrast(foreground, background) >= Argb.MINIMUM_CONTRAST

        return surfaces.all { reads(tokens.text, it) && reads(tokens.mutedText, it) } &&
            reads(tokens.mutedText, tokens.popupBackground) &&
            reads(tokens.accentText, tokens.accent) &&
            reads(tokens.candidateSelectedText, tokens.candidateSelectedBackground)
    }

    private fun lightRoles(palettes: TonalPalettes): DynamicColorRoles {
        val accent = palettes.accent1
        val secondaryAccent = palettes.accent2
        val tertiaryAccent = palettes.accent3
        val neutral = palettes.neutral1
        val neutralVariant = palettes.neutral2
        return DynamicColorRoles(
            primary = accent.tone(40),
            onPrimary = accent.tone(100),
            primaryContainer = accent.tone(90),
            onPrimaryContainer = accent.tone(10),
            secondary = secondaryAccent.tone(40),
            onSecondary = secondaryAccent.tone(100),
            secondaryContainer = secondaryAccent.tone(90),
            onSecondaryContainer = secondaryAccent.tone(10),
            tertiary = tertiaryAccent.tone(40),
            onTertiary = tertiaryAccent.tone(100),
            tertiaryContainer = tertiaryAccent.tone(90),
            onTertiaryContainer = tertiaryAccent.tone(10),
            background = neutral.tone(99),
            onBackground = neutral.tone(10),
            surface = neutral.tone(99),
            onSurface = neutral.tone(10),
            surfaceVariant = neutralVariant.tone(90),
            onSurfaceVariant = neutralVariant.tone(30),
            surfaceContainerLowest = neutral.tone(100),
            surfaceContainerLow = neutral.tone(96),
            surfaceContainer = neutral.tone(94),
            surfaceContainerHigh = neutral.tone(92),
            surfaceContainerHighest = neutral.tone(90),
            outline = neutralVariant.tone(50),
            outlineVariant = neutralVariant.tone(80),
        )
    }

    private fun darkRoles(palettes: TonalPalettes): DynamicColorRoles {
        val accent = palettes.accent1
        val secondaryAccent = palettes.accent2
        val tertiaryAccent = palettes.accent3
        val neutral = palettes.neutral1
        val neutralVariant = palettes.neutral2
        return DynamicColorRoles(
            primary = accent.tone(80),
            onPrimary = accent.tone(20),
            primaryContainer = accent.tone(30),
            onPrimaryContainer = accent.tone(90),
            secondary = secondaryAccent.tone(80),
            onSecondary = secondaryAccent.tone(20),
            secondaryContainer = secondaryAccent.tone(30),
            onSecondaryContainer = secondaryAccent.tone(90),
            tertiary = tertiaryAccent.tone(80),
            onTertiary = tertiaryAccent.tone(20),
            tertiaryContainer = tertiaryAccent.tone(30),
            onTertiaryContainer = tertiaryAccent.tone(90),
            background = neutral.tone(10),
            onBackground = neutral.tone(90),
            surface = neutral.tone(10),
            onSurface = neutral.tone(90),
            surfaceVariant = neutralVariant.tone(30),
            onSurfaceVariant = neutralVariant.tone(80),
            surfaceContainerLowest = neutral.tone(4),
            surfaceContainerLow = neutral.tone(10),
            surfaceContainer = neutral.tone(12),
            surfaceContainerHigh = neutral.tone(17),
            surfaceContainerHighest = neutral.tone(22),
            outline = neutralVariant.tone(60),
            outlineVariant = neutralVariant.tone(30),
        )
    }
}
