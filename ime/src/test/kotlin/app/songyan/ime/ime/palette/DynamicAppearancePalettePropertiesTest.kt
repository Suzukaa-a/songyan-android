package app.songyan.ime.ime.palette

import app.songyan.ime.ime.KeyboardDesignTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Property checks for the dynamic palette: contrast, opacity and monotonic tones must hold for any
 * wallpaper palette, and a palette that cannot be made readable must be refused rather than shipped.
 */
class DynamicAppearancePalettePropertiesTest {
    @Test
    fun realisticWallpaperPalettesAreReadableForEveryHue() {
        for (seed in SEEDS) {
            val palettes = realisticPalettes(seed)
            for (dark in listOf(false, true)) {
                val tokens = DynamicAppearancePalette.tokens(palettes, dark)
                val label = "seed=" + java.lang.Long.toHexString(seed) + " dark=" + dark
                assertTrue(label, DynamicAppearancePalette.isReadable(tokens))
                assertReadable(tokens)
            }
        }
    }

    @Test
    fun aFullySaturatedNeutralPaletteIsRefusedByTheAudit() {
        // A wallpaper whose neutrals are as saturated as its accent leaves no readable text colour;
        // the resolver must fall back to the applied appearance instead of painting this.
        val ramp = ramp(0xFFE53935L)
        val palettes =
            TonalPalettes(
                accent1 = ramp,
                accent2 = ramp,
                accent3 = ramp,
                neutral1 = ramp,
                neutral2 = ramp,
            )
        assertFalse(DynamicAppearancePalette.isReadable(DynamicAppearancePalette.tokens(palettes, dark = false)))
    }

    @Test
    fun aGrayscalePaletteIsReadable() {
        val grey = ramp(0xFFE0E0E0L)
        val palettes =
            TonalPalettes(
                accent1 = grey,
                accent2 = grey,
                accent3 = grey,
                neutral1 = grey,
                neutral2 = grey,
            )
        assertTrue(DynamicAppearancePalette.isReadable(DynamicAppearancePalette.tokens(palettes, dark = false)))
        assertTrue(DynamicAppearancePalette.isReadable(DynamicAppearancePalette.tokens(palettes, dark = true)))
    }

    @Test
    fun everyColourRoleButTheBorderAndShadowIsOpaque() {
        for (dark in listOf(false, true)) {
            val tokens = DynamicAppearancePalette.tokens(realisticPalettes(0xFF1E88E5L), dark)
            val roles =
                listOf(
                    tokens.background,
                    tokens.keySurface,
                    tokens.controlKey,
                    tokens.pressed,
                    tokens.text,
                    tokens.mutedText,
                    tokens.accent,
                    tokens.accentText,
                    tokens.candidateBarBackground,
                    tokens.candidateSelectedBackground,
                    tokens.candidateSelectedText,
                    tokens.popupBackground,
                    tokens.popupSelected,
                    tokens.divider,
                )
            for (color in roles) {
                assertEquals("role " + color, 0xFFL, alpha(color))
            }
        }
    }

    @Test
    fun keysStayDistinguishableFromThePanel() {
        val palettes = realisticPalettes(0xFF00897BL)
        for (dark in listOf(false, true)) {
            val tokens = DynamicAppearancePalette.tokens(palettes, dark)
            assertNotEquals(tokens.keySurface, tokens.background)
            assertNotEquals(tokens.keySurface, tokens.controlKey)
            assertTrue(Argb.contrast(tokens.keySurface, tokens.background) > 1.0)
        }
    }

    @Test
    fun anchorsAndInterpolatedTonesFallMonotonically() {
        val neutral = platformNeutral1()
        var previous = Argb.luminance(neutral.tone(100))
        for (tone in 99 downTo 0) {
            val current = Argb.luminance(neutral.tone(tone))
            assertTrue("tone " + tone + " is brighter than the tone above it", current <= previous + 0.0001)
            previous = current
        }
    }

    /** A wallpaper palette: a saturated accent plus low-chroma neutrals, as the platform produces. */
    private fun realisticPalettes(seed: Long): TonalPalettes {
        val accent1 = ramp(seed)
        val accent2 = ramp(Argb.blend(seed, 0xFF808080L, 0.25))
        val accent3 = ramp(Argb.blend(seed, 0xFF808080L, 0.5))
        // Neutral palettes keep the hue only faintly; that is what keeps surfaces near-white/near-black.
        val neutralSeed = Argb.blend(0xFFE6E4E4L, seed, 0.04)
        val neutral1 = ramp(neutralSeed)
        val neutral2 = ramp(Argb.blend(0xFFE2E1E1L, seed, 0.08))
        return TonalPalettes(accent1, accent2, accent3, neutral1, neutral2)
    }

    private fun ramp(seed: Long): TonalPalette =
        TonalPalette(TonalPalette.TONES.map { tone -> Argb.blend(Argb.BLACK, seed, tone / 100.0) })

    private fun platformNeutral1(): TonalPalette =
        TonalPalette(
            arrayOf(
                "FFFFFF",
                "FEFBFF",
                "F1F0F7",
                "E2E2E9",
                "C6C6CD",
                "ABABB1",
                "909097",
                "76777D",
                "5D5E64",
                "45464C",
                "2F3036",
                "1A1B20",
                "000000",
            ).map { hex -> 0xFF000000L or hex.toLong(16) },
        )

    private fun alpha(color: Long): Long = (color shr 24) and 0xFF

    private fun assertReadable(tokens: KeyboardDesignTokens) {
        val surfaces = listOf(tokens.keySurface, tokens.background, tokens.controlKey, tokens.candidateBarBackground)
        for (surface in surfaces) {
            assertReadable("text", tokens.text, surface)
            assertReadable("mutedText", tokens.mutedText, surface)
        }
        assertReadable("mutedText", tokens.mutedText, tokens.popupBackground)
        assertReadable("accentText", tokens.accentText, tokens.accent)
        assertReadable("candidateSelectedText", tokens.candidateSelectedText, tokens.candidateSelectedBackground)
    }

    private fun assertReadable(
        role: String,
        foreground: Long,
        background: Long,
    ) {
        val contrast = Argb.contrast(foreground, background)
        assertTrue(
            role + ": contrast " + contrast + " for " + foreground + " on " + background,
            contrast >= Argb.MINIMUM_CONTRAST,
        )
    }

    private companion object {
        /** Saturated seeds across the hue circle, each a plausible wallpaper accent. */
        private val SEEDS =
            longArrayOf(0xFFE53935L, 0xFFFB8C00L, 0xFFFDD835L, 0xFF43A047L, 0xFF00897BL, 0xFF1E88E5L, 0xFF8E24AAL)
    }
}
