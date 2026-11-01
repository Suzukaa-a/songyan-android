package app.songyan.ime.appearance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearancePreferencesTest {
    @Test
    fun dynamicColourIsTheDefaultFromAndroidTwelve() {
        assertFalse(AppearancePreferences.defaultDynamicColor(sdkInt = 30))
        assertTrue(AppearancePreferences.defaultDynamicColor(sdkInt = 31))
    }
}
