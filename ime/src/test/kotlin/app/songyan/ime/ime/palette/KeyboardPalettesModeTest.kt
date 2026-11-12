package app.songyan.ime.ime.palette

import app.songyan.ime.ime.ColorMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored theme choice decides the keyboard surface: LIGHT and DARK win over the platform,
 * SYSTEM follows it. The settings app resolves its Compose theme from the same choice, so the two
 * never disagree when the user picks a theme that differs from the system one.
 */
class KeyboardPalettesModeTest {
    @Test
    fun lightWinsOverADarkSystem() {
        assertFalse(KeyboardPalettes.darkFor(ColorMode.LIGHT, systemDark = true))
    }

    @Test
    fun darkWinsOverALightSystem() {
        assertTrue(KeyboardPalettes.darkFor(ColorMode.DARK, systemDark = false))
    }

    @Test
    fun systemFollowsThePlatform() {
        assertTrue(KeyboardPalettes.darkFor(ColorMode.SYSTEM, systemDark = true))
        assertFalse(KeyboardPalettes.darkFor(ColorMode.SYSTEM, systemDark = false))
    }
}
