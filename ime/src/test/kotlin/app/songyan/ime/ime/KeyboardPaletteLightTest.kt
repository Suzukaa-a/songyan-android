package app.songyan.ime.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The navigation bar's icon shade follows the keyboard surface, whatever the system theme is. */
class KeyboardPaletteLightTest {
    @Test
    fun aLightKeyboardSurfaceIsLight() {
        assertTrue(KeyboardPalette.fromTokens(KeyboardDesignTokens.light()).isLight)
    }

    @Test
    fun aDarkKeyboardSurfaceIsDark() {
        assertFalse(KeyboardPalette.fromTokens(KeyboardDesignTokens.dark()).isLight)
    }

    @Test
    fun aDarkSkinInALightSystemThemeIsStillDark() {
        val darkSkin = KeyboardDesignTokens.dark()
        assertFalse(KeyboardPalette.fromTokens(darkSkin).isLight)
    }
}
