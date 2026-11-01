package app.songyan.ime.ime.palette

import app.songyan.ime.appearance.AppearancePresets
import app.songyan.ime.ime.KeyboardDesignTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import app.songyan.ime.appearance.KeyboardPalette as AppearancePalette

/** The keycaps must read as keys: contrast floors between the background, letter keys and function keys. */
class KeyContrastTest {
    private fun assertSeparated(tokens: KeyboardDesignTokens) {
        val keyOnBackground = Argb.contrast(tokens.keySurface, tokens.background)
        val functionOnKey = Argb.contrast(tokens.controlKey, tokens.keySurface)
        assertTrue("key/background $keyOnBackground", keyOnBackground >= KeyContrast.KEY_AGAINST_BACKGROUND - 0.005)
        assertTrue("function/key $functionOnKey", functionOnKey >= KeyContrast.FUNCTION_AGAINST_KEY - 0.005)
        val functionOnBackground = Argb.contrast(tokens.controlKey, tokens.background)
        assertTrue(
            "function/background $functionOnBackground",
            functionOnBackground >= KeyContrast.FUNCTION_AGAINST_BACKGROUND - 0.005,
        )
        val capsuleOnBar = Argb.contrast(tokens.candidateSelectedBackground, tokens.candidateBarBackground)
        assertTrue("capsule/bar $capsuleOnBar", capsuleOnBar >= KeyContrast.CAPSULE_AGAINST_BAR - 0.005)
        val capsuleText = Argb.contrast(tokens.candidateSelectedText, tokens.candidateSelectedBackground)
        assertTrue("capsule text $capsuleText", capsuleText >= Argb.MINIMUM_CONTRAST)
    }

    @Test
    fun theDefaultInkPaletteIsSeparatedInBothThemes() {
        for (dark in listOf(false, true)) {
            val source = AppearancePalette.of(AppearancePresets.androidDefault, dark)
            assertSeparated(ImePalettes.of(source).tokens)
        }
    }

    @Test
    fun everyBuiltInAppearanceIsSeparatedInBothThemes() {
        for (document in AppearancePresets.ALL_BUILT_IN) {
            for (dark in listOf(false, true)) {
                assertSeparated(ImePalettes.of(AppearancePalette.of(document, dark)).tokens)
            }
        }
    }

    @Test
    fun aWallpaperPaletteIsSeparatedInBothThemes() {
        val grey =
            TonalPalette(TonalPalette.TONES.map { tone -> Argb.of(tone / 100.0, tone / 100.0, tone / 100.0) })
        val palettes = TonalPalettes(grey, grey, grey, grey, grey)
        for (dark in listOf(false, true)) {
            assertSeparated(DynamicAppearancePalette.tokens(palettes, dark))
        }
    }

    @Test
    fun aWashedOutWallpaperPaletteIsStillSeparated() {
        // A vendor palette that ignores tone: every step a near-white tint.
        val pale = TonalPalette(TonalPalette.TONES.map { Argb.of(0.97, 0.96, 0.99) })
        val palettes = TonalPalettes(pale, pale, pale, pale, pale)
        for (dark in listOf(false, true)) {
            assertSeparated(DynamicAppearancePalette.tokens(palettes, dark))
        }
    }

    @Test
    fun aLightFunctionKeyIsDarkerThanTheBackground() {
        val grey =
            TonalPalette(TonalPalette.TONES.map { tone -> Argb.of(tone / 100.0, tone / 100.0, tone / 100.0) })
        val tokens = DynamicAppearancePalette.tokens(TonalPalettes(grey, grey, grey, grey, grey), dark = false)
        assertTrue(Argb.luminance(tokens.controlKey) < Argb.luminance(tokens.background))
        assertTrue(Argb.luminance(tokens.background) < Argb.luminance(tokens.keySurface))
    }

    @Test
    fun aFunctionKeyOnTheBackgroundIsPushedOut() {
        val tokens =
            KeyboardDesignTokens.light().copy(
                background = 0xFFE2E1E5L,
                keySurface = 0xFFFFFFFFL,
                controlKey = 0xFFE3E2E6L,
            )
        assertSeparated(KeyContrast.separate(tokens))
    }

    @Test
    fun aDarkFunctionKeyIsNeverLighterThanTheLetterKey() {
        val grey =
            TonalPalette(TonalPalette.TONES.map { tone -> Argb.of(tone / 100.0, tone / 100.0, tone / 100.0) })
        val tokens = DynamicAppearancePalette.tokens(TonalPalettes(grey, grey, grey, grey, grey), dark = true)
        assertTrue(Argb.luminance(tokens.controlKey) < Argb.luminance(tokens.keySurface))
        assertTrue(Argb.luminance(tokens.background) < Argb.luminance(tokens.controlKey))
    }

    @Test
    fun anAlreadySeparatedPaletteIsLeftAlone() {
        val tokens =
            KeyboardDesignTokens.light().copy(
                background = 0xFF808080L,
                keySurface = 0xFFFFFFFFL,
                controlKey = 0xFF303030L,
                candidateSelectedBackground = 0xFFB0C8B6L,
            )
        assertEquals(tokens, KeyContrast.separate(tokens))
    }

    /** Primary container on a surface container (tones 90 and 92): the capsule must not vanish. */
    @Test
    fun aCapsuleTwoTonesFromTheBarIsPushedOut() {
        val light =
            KeyContrast.separate(
                KeyboardDesignTokens.light().copy(
                    candidateBarBackground = 0xFFE6E8E2L,
                    candidateSelectedBackground = 0xFFDDE5DCL,
                ),
            )
        assertTrue(Argb.luminance(light.candidateSelectedBackground) < Argb.luminance(0xFFDDE5DCL))
        assertSeparated(light)
        val dark =
            KeyContrast.separate(
                KeyboardDesignTokens.dark().copy(
                    candidateBarBackground = 0xFF2B2D2AL,
                    candidateSelectedBackground = 0xFF2E322EL,
                ),
            )
        assertTrue(Argb.luminance(dark.candidateSelectedBackground) > Argb.luminance(0xFF2E322EL))
        assertSeparated(dark)
    }

    @Test
    fun theBarFollowsTheBackgroundWhenItMoves() {
        val tokens = KeyboardDesignTokens.light().copy(background = 0xFFF0F0F0L, candidateBarBackground = 0xFFF0F0F0L)
        val separated = KeyContrast.separate(tokens)
        assertEquals(separated.background, separated.candidateBarBackground)
    }
}
