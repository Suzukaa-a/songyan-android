package app.songyan.ime.prefs

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardFeelPreferencesTest {
    @Test
    fun theBottomGapSnapsToZeroThroughFortyEightInFours() {
        assertEquals(0, KeyboardPreferences.clampBottomGap(-4))
        assertEquals(8, KeyboardPreferences.clampBottomGap(11))
        assertEquals(48, KeyboardPreferences.clampBottomGap(49))
        assertEquals(400, KeyboardPreferences.clampSplitWidth(10))
        assertEquals(620, KeyboardPreferences.clampSplitWidth(639))
        assertEquals(1200, KeyboardPreferences.clampSplitWidth(9999))
        assertEquals(0, KeyboardPreferences.clampSideMargin(-1))
        assertEquals(10, KeyboardPreferences.clampSideMargin(13))
        assertEquals(100, KeyboardPreferences.clampSideMargin(101))
    }

    @Test
    fun theLongPressDelaySnapsToTheSliderSteps() {
        assertEquals(150, KeyboardPreferences.clampLongPress(0))
        assertEquals(300, KeyboardPreferences.clampLongPress(320))
        assertEquals(1000, KeyboardPreferences.clampLongPress(4000))
    }
}
