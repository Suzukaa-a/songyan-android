package app.songyan.ime.ime.palette

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songyan.ime.ime.ColorMode
import app.songyan.ime.ime.KeyboardPalette
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A theme choice that differs from the system must reach the actual palette, not only the settings
 * app: dark in a light system paints a dark keyboard, light in a dark system paints a light one.
 * SYSTEM keeps following the platform. Each case forces the configuration relevant to the branch,
 * so the result does not depend on the test device's night mode.
 */
@RunWith(AndroidJUnit4::class)
class KeyboardPalettesModeDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun resetThemeChoice() {
        KeyboardPalettes.setColorMode(ColorMode.SYSTEM)
    }

    @Test
    fun aDarkChoicePaintsADarkKeyboardInALightSystem() {
        KeyboardPalettes.setColorMode(ColorMode.DARK)
        assertFalse(KeyboardPalette.from(contextWithNight(dark = false)).isLight)
    }

    @Test
    fun aLightChoicePaintsALightKeyboardInADarkSystem() {
        KeyboardPalettes.setColorMode(ColorMode.LIGHT)
        assertTrue(KeyboardPalette.from(contextWithNight(dark = true)).isLight)
    }

    @Test
    fun systemChoiceFollowsThePlatform() {
        KeyboardPalettes.setColorMode(ColorMode.SYSTEM)
        assertTrue(KeyboardPalette.from(contextWithNight(dark = false)).isLight)
        assertFalse(KeyboardPalette.from(contextWithNight(dark = true)).isLight)
    }

    private fun contextWithNight(dark: Boolean): Context {
        val configuration = Configuration(context.resources.configuration)
        configuration.uiMode =
            (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        return context.createConfigurationContext(configuration)
    }
}
