package app.songyan.ime.ime.palette

import app.songyan.ime.appearance.AppearanceDocument
import app.songyan.ime.appearance.AppearancePresets
import app.songyan.ime.appearance.BackgroundFill
import app.songyan.ime.appearance.SkinDefinition
import app.songyan.ime.ime.KeyboardDesignTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun everyBuiltInSkinCarriesTheMetricsTheKeyboardHadBeforeSkinsWereLiteral() {
        val legacy = KeyboardDesignTokens.light()
        for (document in AppearancePresets.ALL_BUILT_IN) {
            for (dark in listOf(false, true)) {
                val tokens = ImePalettes.tokensOf(AppearancePalette.of(document, dark))
                val label = document.id + " dark=" + dark
                assertEquals(label, legacy.keyRadiusDp, tokens.keyRadiusDp)
                assertEquals(label, legacy.keyGapHorizontalDp, tokens.keyGapHorizontalDp)
                assertEquals(label, legacy.keyGapVerticalDp, tokens.keyGapVerticalDp)
                assertEquals(label, legacy.keyLabelTextSizeSp, tokens.keyLabelTextSizeSp)
                assertEquals(label, legacy.keySecondaryLabelTextSizeSp, tokens.keySecondaryLabelTextSizeSp)
                assertEquals(label, legacy.candidateTextSizeSp, tokens.candidateTextSizeSp)
                assertEquals(label, legacy.candidateCommentTextSizeSp, tokens.candidateCommentTextSizeSp)
                assertEquals(label, 0f, tokens.keyShadowOpacity)
                assertEquals(label, "system", tokens.fontFamily)
                assertEquals(label, "regular", tokens.fontWeight)
                assertEquals(label, 1f, tokens.keyBorderWidthDp)
            }
        }
    }

    @Test
    fun aCustomSkinIsReadLiterallyInDpAndSp() {
        val skin =
            AppearancePresets.androidDefault.skin.run {
                copy(
                    keycap = SkinDefinition.Keycap(cornerRadius = 12.0, borderWidth = 2.0, shadow = "subtle"),
                    spacing = SkinDefinition.Spacing(visualInsetX = 4.0, visualInsetY = 3.0),
                    typography =
                        SkinDefinition.Typography(
                            family = "serif",
                            weight = "semibold",
                            keySize = 25.0,
                            secondarySize = 13.0,
                            candidateSize = 24.0,
                        ),
                )
            }
        val tokens = ImePalettes.tokensOf(AppearancePalette.of(skin, false))
        assertEquals(12f, tokens.keyRadiusDp)
        assertEquals(2f, tokens.keyBorderWidthDp)
        assertEquals(0.18f, tokens.keyShadowOpacity)
        assertEquals(8f, tokens.keyGapHorizontalDp)
        assertEquals(6f, tokens.keyGapVerticalDp)
        assertEquals(25f, tokens.keyLabelTextSizeSp)
        assertEquals(13f, tokens.keySecondaryLabelTextSizeSp)
        assertEquals(24f, tokens.candidateTextSizeSp)
        assertEquals("serif", tokens.fontFamily)
        assertEquals("semibold", tokens.fontWeight)
    }

    @Test
    fun theBackgroundOfTheVariantReachesTheTokens() {
        val variant =
            SkinDefinition.Variant(
                colors =
                    AppearancePresets.androidDefault.skin.variants.light
                        ?.colors,
                background =
                    SkinDefinition.Background(
                        type = "linearGradient",
                        from = "#112233",
                        to = "#445566",
                        direction = "vertical",
                    ),
            )
        val skin = AppearancePresets.androidDefault.skin.run { copy(variants = variants.copy(light = variant)) }
        val fill = ImePalettes.tokensOf(AppearancePalette.of(skin, false)).backgroundFill
        assertEquals(BackgroundFill.VerticalGradient(0xFF112233.toInt(), 0xFF445566.toInt()), fill)
        assertNull(ImePalettes.tokensOf(AppearancePalette.of(skin, true)).backgroundFill)
    }

    @Test
    fun aBorderlessAppearancePaintsNoBorder() {
        val tokens = ImePalettes.of(AppearancePalette.of(AppearanceDocument.systemDefault, false)).tokens
        assertEquals(0, tokens.keyBorder)
        assertEquals(0, tokens.controlKeyBorder)
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
