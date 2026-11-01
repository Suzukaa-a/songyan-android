package app.songyan.ime.ime.palette

import app.songyan.ime.ime.KeyboardDesignTokens
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The inputs are the 13 anchor values the platform publishes for its default wallpaper palette,
 * i.e. exactly what [SystemTonalPalettes] reads on Android 12.
 */
class DynamicAppearancePaletteTest {
    @Test
    fun everyTextRoleReadsOnEverySurfaceInLightMode() {
        assertReadable(DynamicAppearancePalette.tokens(platformPalettes, dark = false))
    }

    @Test
    fun everyTextRoleReadsOnEverySurfaceInDarkMode() {
        assertReadable(DynamicAppearancePalette.tokens(platformPalettes, dark = true))
    }

    @Test
    fun lightAndDarkVariantsDiffer() {
        val light = DynamicAppearancePalette.tokens(platformPalettes, dark = false)
        val dark = DynamicAppearancePalette.tokens(platformPalettes, dark = true)
        assertNotEquals(light.background, dark.background)
        assertNotEquals(light.text, dark.text)
    }

    @Test
    fun surfacesKeepTheWallpaperHue() {
        val tokens = DynamicAppearancePalette.tokens(platformPalettes, dark = false)
        // The platform anchors are a cool blue palette; a hard-coded scheme would not stay blue.
        // The light letter key is the lightest container (pure white); the other surfaces carry the hue.
        assertTrue(blue(tokens.controlKey) > red(tokens.controlKey))
        assertTrue(blue(tokens.background) > red(tokens.background))
    }

    @Test
    fun aCompressedMidTonePaletteIsRepairedInsteadOfShipped() {
        val compressed =
            TonalPalettes(
                accent1 = midTonePalette(),
                accent2 = midTonePalette(),
                accent3 = midTonePalette(),
                neutral1 = midTonePalette(),
                neutral2 = midTonePalette(),
            )
        assertReadable(DynamicAppearancePalette.tokens(compressed, dark = false))
        assertReadable(DynamicAppearancePalette.tokens(compressed, dark = true))
    }

    private fun assertReadable(tokens: KeyboardDesignTokens) {
        val textSurfaces =
            listOf(tokens.keySurface, tokens.background, tokens.controlKey, tokens.candidateBarBackground)
        for (surface in textSurfaces) {
            assertReadable(tokens.text, surface)
            assertReadable(tokens.mutedText, surface)
        }
        assertReadable(tokens.mutedText, tokens.popupBackground)
        assertReadable(tokens.accentText, tokens.accent)
        assertReadable(tokens.candidateSelectedText, tokens.candidateSelectedBackground)
    }

    private fun assertReadable(
        foreground: Long,
        background: Long,
    ) {
        val contrast = Argb.contrast(foreground, background)
        assertTrue(
            "contrast " + contrast + " for " + foreground + " on " + background,
            contrast >= Argb.MINIMUM_CONTRAST,
        )
    }

    private fun red(color: Long): Long = (color shr 16) and 0xFF

    private fun blue(color: Long): Long = color and 0xFF

    /** Every anchor a slightly different mid grey: the worst case a wallpaper palette can hand us. */
    private fun midTonePalette(): TonalPalette =
        TonalPalette(
            TonalPalette.TONES.mapIndexed { index, _ ->
                val channel = (0x58 + index) / 255.0
                Argb.of(channel, channel, channel)
            },
        )

    private fun hex(value: String): Long = 0xFF000000L or value.toLong(16)

    private fun palette(vararg values: String): TonalPalette = TonalPalette(values.map(::hex))

    private val platformPalettes =
        TonalPalettes(
            accent1 = palette(*ACCENT1),
            accent2 = palette(*ACCENT2),
            accent3 = palette(*ACCENT3),
            neutral1 = palette(*NEUTRAL1),
            neutral2 = palette(*NEUTRAL2),
        )

    private companion object {
        private val ACCENT1 =
            arrayOf(
                "FFFFFF",
                "FEFBFF",
                "EEF0FF",
                "D9E2FF",
                "B0C6FF",
                "94AAE4",
                "7A90C8",
                "6076AC",
                "475D92",
                "2F4578",
                "152E60",
                "001945",
                "000000",
            )
        private val ACCENT2 =
            arrayOf(
                "FFFFFF",
                "FEFBFF",
                "EEF0FF",
                "DCE2F9",
                "C0C6DC",
                "A4ABC1",
                "8A90A5",
                "70778B",
                "575E71",
                "404659",
                "2A3042",
                "151B2C",
                "000000",
            )
        private val ACCENT3 =
            arrayOf(
                "FFFFFF",
                "FFFBFF",
                "FFEBFA",
                "FDD7FA",
                "E0BBDD",
                "C3A0C1",
                "A886A6",
                "8C6D8C",
                "725572",
                "593D59",
                "412742",
                "2A122C",
                "000000",
            )
        private val NEUTRAL1 =
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
            )
        private val NEUTRAL2 =
            arrayOf(
                "FFFFFF",
                "FEFBFF",
                "F0F0FA",
                "E1E2EC",
                "C5C6D0",
                "A9ABB4",
                "8F9099",
                "757780",
                "5C5E67",
                "44464F",
                "2E3038",
                "191B23",
                "000000",
            )
    }
}
