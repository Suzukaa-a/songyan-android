package app.songyan.ime.prefs

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardFeelPreferencesTest {
    @Test
    fun theBottomGapSnapsToZeroThroughFortyEightInFours() {
        assertEquals(0, KeyboardPreferences.clampBottomGap(-4))
        assertEquals(8, KeyboardPreferences.clampBottomGap(11))
        assertEquals(48, KeyboardPreferences.clampBottomGap(49))
    }

    @Test
    fun theLongPressDelaySnapsToTheSliderSteps() {
        assertEquals(150, KeyboardPreferences.clampLongPress(0))
        assertEquals(300, KeyboardPreferences.clampLongPress(320))
        assertEquals(1000, KeyboardPreferences.clampLongPress(4000))
    }
}
