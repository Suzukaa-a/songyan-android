package app.songyan.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardGeometryTest {
    @Test
    fun theThreePresetsAre56_60_64() {
        assertEquals(listOf(56f, 60f, 64f), KeyHeight.entries.map { it.pitchDp })
        assertEquals(KeyHeight.STANDARD, KeyHeight.resolve(null))
        assertEquals(KeyHeight.STANDARD, KeyHeight.resolve("huge"))
        for (preset in KeyHeight.entries) assertEquals(preset, KeyHeight.resolve(preset.wire))
    }

    @Test
    fun aTallPhoneGetsTheChosenPitch() {
        for (preset in KeyHeight.entries) {
            assertEquals(preset.pitchDp, KeyboardGeometry.rowPitchDp(preset, 890, 48f, 8f))
        }
    }

    @Test
    fun aCommonPhoneGetsEachPresetOneStepTallerThanBefore() {
        // A 6.4" phone reports about 751 dp; the window without the system bars about 704 dp. The
        // presets are 56/60/64 dp scaled from an 800 dp reference height, so a 6.4" phone gets each
        // one step taller than a 750 dp reference would give.
        assertEquals(
            listOf(224f, 240f, 256f),
            KeyHeight.entries.map { KeyboardGeometry.portraitAreaDp(it, 751) },
        )
        val scale = 704f / 750f
        val before = listOf(52f, 56f, 60f).map { it * 4 * scale }
        val now = KeyHeight.entries.map { KeyboardGeometry.portraitAreaDp(it, 704) }
        assertEquals(before[1], now[0], 0.01f)
        assertEquals(before[2], now[1], 0.01f)
        assertTrue("$now", now[2] - now[1] >= 14f)
    }

    @Test
    fun onlyAShortLandscapeWindowGetsTheFullscreenEditor() {
        // A phone in landscape is about 360-430 dp tall; an unfolded inner screen or a tablet in
        // landscape 600 dp and more, with room for the app above the keyboard.
        assertTrue(KeyboardGeometry.fullscreenEditor(systemWants = true, screenHeightDp = 392))
        assertFalse(KeyboardGeometry.fullscreenEditor(systemWants = true, screenHeightDp = 690))
        assertFalse(KeyboardGeometry.fullscreenEditor(systemWants = true, screenHeightDp = 800))
        assertFalse(KeyboardGeometry.fullscreenEditor(systemWants = false, screenHeightDp = 392))
    }

    @Test
    fun theKeyAreaStaysWithin200And256Dp() {
        for (screen in listOf(640, 700, 780, 890, 960, 1280)) {
            for (preset in KeyHeight.entries) {
                val area = KeyboardGeometry.portraitAreaDp(preset, screen)
                assertTrue("$screen $preset $area", area in 200f..256f)
            }
        }
    }

    @Test
    fun theThreePresetsStayDistinctOnEveryPortraitWindowFromAFoldCoverUp() {
        // 751 dp is a Galaxy Z Fold cover screen; 700 a small phone; 1000+ a tablet.
        for (screen in listOf(700, 751, 800, 890, 1000, 1280)) {
            val pitches = KeyHeight.entries.map { KeyboardGeometry.rowPitchDp(it, screen, 48f, 8f) }
            assertEquals("$screen $pitches", 3, pitches.distinct().size)
            assertEquals("$screen $pitches", pitches.sorted(), pitches)
        }
        assertEquals(
            listOf(56f, 60f, 64f),
            KeyHeight.entries.map { KeyboardGeometry.rowPitchDp(it, 800, 48f, 8f) },
        )
    }

    @Test
    fun thePresetsStayOrderedAndAtLeastOneAndAHalfDpApartOnEveryPortraitHeight() {
        for (screen in KeyboardGeometry.SHORT_WINDOW_DP + 1..1400) {
            val pitches = KeyHeight.entries.map { KeyboardGeometry.rowPitchDp(it, screen, 48f, 8f) }
            for ((low, high) in pitches.zipWithNext()) {
                assertTrue("$screen $pitches", high - low >= 1.5f - 0.001f)
            }
            for (preset in KeyHeight.entries) {
                val area = KeyboardGeometry.portraitAreaDp(preset, screen)
                assertTrue("$screen $preset $area", area in 200f..256f)
            }
        }
    }

    @Test
    fun shortWindowsKeepThePresetsDistinctAtThreeQuartersOfTheirPitch() {
        // Phone landscape and split-screen panes: the choice still counts, at a shorter pitch.
        val pitches = KeyHeight.entries.map { KeyboardGeometry.rowPitchDp(it, 480, 48f, 8f) }
        listOf(42f, 45f, 48f).zip(pitches).forEach { (want, got) -> assertEquals(want, got, 0.01f) }
        // A shorter window caps the tallest preset at its share of the height.
        assertEquals(46.5f, KeyboardGeometry.rowPitchDp(KeyHeight.TALL, 440, 48f, 8f), 0.01f)
        // Just above the threshold the portrait rule applies again.
        assertEquals(
            listOf(56f, 60f, 64f),
            KeyHeight.entries.map { KeyboardGeometry.rowPitchDp(it, KeyboardGeometry.SHORT_WINDOW_DP + 281, 48f, 8f) },
        )
    }

    @Test
    fun aShortPhoneKeepsTheMinimumArea() {
        assertEquals(200f, KeyboardGeometry.portraitAreaDp(KeyHeight.COMPACT, 560))
        assertEquals(50f, KeyboardGeometry.rowPitchDp(KeyHeight.COMPACT, 560, 48f, 8f))
    }

    @Test
    fun landscapeStaysWithinTheShortWindowShare() {
        for (screen in listOf(360, 411, 440, 520)) {
            for (preset in KeyHeight.entries) {
                val pitch = KeyboardGeometry.rowPitchDp(preset, screen, 48f, 8f)
                val keyboard = 48f + 4 * pitch + 8f
                assertTrue("$screen $preset $pitch", keyboard <= screen * 0.55f + 0.01f || pitch == 32f)
                assertTrue("$screen $preset $pitch", screen - keyboard >= 180f - 0.01f || pitch == 32f)
            }
        }
    }

    @Test
    fun theShortestPhonesKeepTheirAppRoom() {
        // A 360 dp landscape window keeps the old half-window budget; taller ones get up to 55%.
        assertEquals(180f, KeyboardGeometry.shortKeyboardMaxDp(360), 0.01f)
        assertEquals(242f, KeyboardGeometry.shortKeyboardMaxDp(440), 0.01f)
    }

    @Test
    fun aVeryShortWindowShrinksTheRowsButNotBelowTheFloor() {
        val pitch = KeyboardGeometry.rowPitchDp(KeyHeight.STANDARD, 300, 40f, 5f)
        assertEquals(32f, pitch)
    }

    @Test
    fun theRowFloorWinsOverTheAppRoomBudgetOnTheShortestWindows() {
        // At 300 dp the budget leaves 120 dp for the keyboard, less than bar, gap and four floor rows.
        val pitch = KeyboardGeometry.rowPitchDp(KeyHeight.COMPACT, 300, 48f, 8f)
        assertEquals(32f, pitch)
        val keyboard = 48f + 4 * pitch + 8f
        assertTrue(keyboard > KeyboardGeometry.shortKeyboardMaxDp(300))
        assertTrue(300 - keyboard < 180f)
    }

    @Test
    fun fontScaleStopsAtOnePointThree() {
        assertEquals(2.75f * 1.3f, ImeText.scale(2.75f, 2.0f), 0.0001f)
        assertEquals(2.75f * 1.15f, ImeText.scale(2.75f, 1.15f), 0.0001f)
        assertEquals(2.75f * 0.85f, ImeText.scale(2.75f, 0.85f), 0.0001f)
    }

    @Test
    fun theBottomGapSnapsToTheRangeAndTheStep() {
        assertEquals(0, KeyboardGeometry.clampBottomGapDp(-8))
        assertEquals(0, KeyboardGeometry.clampBottomGapDp(3))
        assertEquals(4, KeyboardGeometry.clampBottomGapDp(7))
        assertEquals(48, KeyboardGeometry.clampBottomGapDp(500))
        assertEquals(400, KeyboardGeometry.clampSplitWidthDp(0))
        assertEquals(600, KeyboardGeometry.clampSplitWidthDp(609))
        assertEquals(1200, KeyboardGeometry.clampSplitWidthDp(5000))
        assertEquals(0, KeyboardGeometry.clampSideMarginPerMille(-3))
        assertEquals(10, KeyboardGeometry.clampSideMarginPerMille(13))
        assertEquals(100, KeyboardGeometry.clampSideMarginPerMille(500))
    }

    @Test
    fun aTallWindowTakesTheWholeBottomGap() {
        for (screen in listOf(700, 890, 1280)) {
            assertEquals(0f, KeyboardGeometry.bottomExtraDp(0, screen, 48f, 60f, 8f))
            assertEquals(24f, KeyboardGeometry.bottomExtraDp(24, screen, 48f, 60f, 8f))
            assertEquals(48f, KeyboardGeometry.bottomExtraDp(48, screen, 48f, 60f, 8f))
        }
    }

    @Test
    fun aShortWindowCutsTheBottomGapToTheBudgetLeft() {
        // 440 dp: budget 242, bar 40 + 4 rows of 45 + gap 5 = 225, and the reading row no longer fits.
        assertEquals(16f, KeyboardGeometry.bottomExtraDp(16, 440, 40f, 45f, 5f))
        assertEquals(17f, KeyboardGeometry.bottomExtraDp(48, 440, 40f, 45f, 5f))
        // 480 dp: budget 264; the reading row still fits, so its 32 dp stays free.
        assertEquals(7f, KeyboardGeometry.bottomExtraDp(48, 480, 40f, 45f, 5f))
        // 360 dp: the keyboard already fills the budget.
        assertEquals(0f, KeyboardGeometry.bottomExtraDp(48, 360, 40f, 33.75f, 5f))
        // Below the floor the keyboard is over budget already; no room, never negative.
        assertEquals(0f, KeyboardGeometry.bottomExtraDp(48, 300, 40f, 32f, 5f))
    }

    @Test
    fun theBottomGapNeverPushesAShortKeyboardPastItsBudget() {
        for (screen in listOf(400, 440, 480, 520)) {
            val pitch = KeyboardGeometry.rowPitchDp(KeyHeight.STANDARD, screen, 40f, 5f)
            val extra = KeyboardGeometry.bottomExtraDp(48, screen, 40f, pitch, 5f)
            assertTrue("$screen", 40f + 4 * pitch + 5f + extra <= KeyboardGeometry.shortKeyboardMaxDp(screen) + 0.01f)
        }
    }

    @Test
    fun theHoldDelayFollowsTheSystemUnlessTheUserPicksOne() {
        // No choice: the longer of the system's delay and the layout's.
        assertEquals(400L, HoldDelay.resolve(null, 400L, 0.35))
        assertEquals(900L, HoldDelay.resolve(null, 900L, 0.35))
        assertEquals(500L, HoldDelay.resolve(null, 400L, 0.5))
        // A choice replaces both, even when it is shorter.
        assertEquals(200L, HoldDelay.resolve(200, 400L, 0.35))
        assertEquals(1000L, HoldDelay.resolve(1000, 400L, 0.35))
        // Out-of-range and off-step values snap into the choosable set.
        assertEquals(150L, HoldDelay.resolve(10, 400L, 0.35))
        assertEquals(1000L, HoldDelay.resolve(5000, 400L, 0.35))
        assertEquals(300L, HoldDelay.resolve(349, 400L, 0.35))
        assertEquals(null, HoldDelay.clamp(null))
    }
}
