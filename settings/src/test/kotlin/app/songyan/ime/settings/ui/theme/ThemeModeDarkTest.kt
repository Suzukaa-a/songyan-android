package app.songyan.ime.settings.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The settings app resolves its dark theme from the stored choice, not from the system alone. */
class ThemeModeDarkTest {
    @Test
    fun lightWinsOverADarkSystem() {
        assertFalse(resolveDarkTheme(ThemeMode.LIGHT, systemDark = true))
    }

    @Test
    fun darkWinsOverALightSystem() {
        assertTrue(resolveDarkTheme(ThemeMode.DARK, systemDark = false))
    }

    @Test
    fun systemFollowsThePlatform() {
        assertTrue(resolveDarkTheme(ThemeMode.SYSTEM, systemDark = true))
        assertFalse(resolveDarkTheme(ThemeMode.SYSTEM, systemDark = false))
    }
}
