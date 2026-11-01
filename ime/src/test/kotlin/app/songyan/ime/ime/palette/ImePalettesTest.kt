package app.songyan.ime.ime.palette

import app.songyan.ime.appearance.AppearanceDocument
import app.songyan.ime.appearance.AppearancePresets
import app.songyan.ime.ime.KeyboardDesignTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import app.songyan.ime.appearance.KeyboardPalette as AppearancePalette

/** The Canvas palette must carry exactly what the shared appearance model resolved. */
class ImePalettesTest {
    @Test
    fun everyColourComesFromTheAppearancePalette() {
        for (dark in listOf(false, true)) {
            val source = AppearancePalette.of(AppearancePresets.androidDefault, dark)
            val tokens = ImePalettes.of(source).tokens
            // Background, key and capsule fills may be nudged apart by KeyContrast (and the capsule
            // text with its fill); they are checked there.
            assertEquals(argb(source.keyBorder), tokens.keyBorder)
            assertEquals(argb(source.pressed), tokens.pressed)
            assertEquals(argb(source.text), tokens.text)
            assertEquals(argb(source.mutedText), tokens.mutedText)
            assertEquals(argb(source.accent), tokens.accent)
            assertEquals(argb(source.accentText), tokens.accentText)
            assertEquals(argb(source.divider), tokens.divider)
            assertEquals(argb(source.popupBackground), tokens.popupBackground)
            assertEquals(argb(source.popupSelected), tokens.popupSelected)
            assertEquals(argb(source.popupShadow), tokens.popupShadow)
        }
    }

    @Test
    fun anOnTileOfTheSettingsPanelReadsOnTheAccentFill() {
        for (dark in listOf(false, true)) {
            val tokens = ImePalettes.of(AppearancePalette.of(AppearancePresets.androidDefault, dark)).tokens
            val ratio = Argb.contrast(tokens.accent, tokens.accentText)
            assertTrue("accent label contrast $ratio (dark=$dark)", ratio >= 4.5)
        }
    }

    @Test
    fun theAndroidDefaultAppearanceKeepsTheSongyanInkDesign() {
        val light = AppearancePalette.of(AppearancePresets.androidDefault, false)
        assertInkDesign(ImePalettes.of(light).tokens, KeyboardDesignTokens.light())
        val dark = AppearancePalette.of(AppearancePresets.androidDefault, true)
        assertInkDesign(ImePalettes.of(dark).tokens, KeyboardDesignTokens.dark())
    }

    @Test
    fun dynamicColourKeepsTheAppearanceMetrics() {
        val appearance = ImePalettes.tokensOf(AppearancePalette.of(AppearanceDocument.systemDefault, false))
        val colours = DynamicAppearancePalette.tokens(greyPalettes, dark = false)
        val merged = ImePalettes.withMetricsOf(colours, appearance)
        assertEquals(colours.background, merged.background)
        assertEquals(colours.text, merged.text)
        assertEquals(appearance.keyRadiusDp, merged.keyRadiusDp)
        assertEquals(appearance.keyGapHorizontalDp, merged.keyGapHorizontalDp)
        assertEquals(appearance.keyLabelTextSizeSp, merged.keyLabelTextSizeSp)
        assertEquals(appearance.candidateTextSizeSp, merged.candidateTextSizeSp)
        assertEquals(appearance.popupRadiusDp, merged.popupRadiusDp)
    }

    @Test
    fun metricsAreTheDesignTokensOwnNotTheSkins() {
        // Key geometry and type sizes are an Android keyboard spec: a skin may recolour the keys but
        // must not move the touch targets or the row rhythm.
        val tokens = ImePalettes.of(AppearancePalette.of(AppearancePresets.androidDefault, false)).tokens
        assertEquals(8f, tokens.keyRadiusDp)
        assertEquals(5f, tokens.keyGapHorizontalDp)
        assertEquals(56f, tokens.rowHeightDp)
        assertEquals(22f, tokens.keyLabelTextSizeSp)
        assertEquals(11f, tokens.keySecondaryLabelTextSizeSp)
        assertEquals(18f, tokens.candidateTextSizeSp)
        assertEquals(12f, tokens.candidateCommentTextSizeSp)
    }

    @Test
    fun aBorderlessAppearancePaintsNoBorder() {
        val tokens = ImePalettes.of(AppearancePalette.of(AppearanceDocument.systemDefault, false)).tokens
        assertEquals(0, tokens.keyBorder)
        assertEquals(0, tokens.controlKeyBorder)
    }

    private fun assertInkDesign(
        tokens: KeyboardDesignTokens,
        legacy: KeyboardDesignTokens,
    ) {
        assertEquals(legacy.pressed, tokens.pressed)
        assertEquals(legacy.text, tokens.text)
        assertEquals(legacy.mutedText, tokens.mutedText)
        assertEquals(legacy.accent, tokens.accent)
        assertEquals(legacy.accentText, tokens.accentText)
    }

    private val grey =
        TonalPalette(
            TonalPalette.TONES.map { tone -> Argb.of(tone / 100.0, tone / 100.0, tone / 100.0) },
        )

    /** Neutral greys for every role: enough to resolve a full token set. */
    private val greyPalettes =
        TonalPalettes(
            accent1 = grey,
            accent2 = grey,
            accent3 = grey,
            neutral1 = grey,
            neutral2 = grey,
        )

    private fun argb(color: Int): Long = color.toLong() and 0xFFFFFFFFL
}
