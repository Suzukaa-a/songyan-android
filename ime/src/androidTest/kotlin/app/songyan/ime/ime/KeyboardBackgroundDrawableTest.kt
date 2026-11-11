package app.songyan.ime.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songyan.ime.appearance.AppearancePresets
import app.songyan.ime.appearance.SkinDefinition
import app.songyan.ime.ime.palette.ImePalettes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import app.songyan.ime.appearance.KeyboardPalette as AppearancePalette

/** The navigation bar's icons follow what is drawn at the keyboard's bottom, not only the palette. */
@RunWith(AndroidJUnit4::class)
class KeyboardBackgroundDrawableTest {
    private fun lightPaletteWith(background: SkinDefinition.Background): KeyboardPalette {
        val base = AppearancePresets.androidDefault.skin
        val light = base.variants.light!!
        val skin = base.copy(variants = base.variants.copy(light = light.copy(background = background)))
        return ImePalettes.of(AppearancePalette.of(skin.normalized(), false))
    }

    @Test
    fun aGradientThatEndsDarkGivesLightIcons() {
        val palette =
            lightPaletteWith(
                SkinDefinition.Background(
                    type = "linearGradient",
                    colors = listOf("#FFFFFF", "#101010"),
                    direction = "vertical",
                ),
            )
        assertTrue(palette.isLight)
        val surface = KeyboardBackgroundDrawable(palette)
        var changes = 0
        surface.onBottomToneChanged = { changes++ }
        surface.setBounds(0, 0, 1080, 900)
        assertFalse(surface.bottomIsLight)
        assertTrue(changes >= 1)
    }

    @Test
    fun aPlainPaletteKeepsItsOwnTone() {
        val palette = ImePalettes.of(AppearancePalette.of(AppearancePresets.androidDefault, false))
        val surface = KeyboardBackgroundDrawable(palette)
        surface.setBounds(0, 0, 1080, 900)
        assertTrue(surface.bottomIsLight)
    }
}
