package app.songyan.ime.ime

import android.view.HapticFeedbackConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CursorDragTest {
    private val step = CursorDrag.STEP_DP * 3f

    @Test
    fun aCharacterCostsMoreTravelThanBefore() {
        // 12 dp per character was too fine for a slow, precise drag.
        assertTrue(CursorDrag.STEP_DP >= 20f)
    }

    @Test
    fun theCaretMovesOneCharacterPerStepOfTravel() {
        assertEquals(0, CursorDrag.wholeSteps(step * 0.9f, step))
        assertEquals(1, CursorDrag.wholeSteps(step, step))
        assertEquals(1, CursorDrag.wholeSteps(step * 1.6f, step))
        assertEquals(-1, CursorDrag.wholeSteps(-step * 1.2f, step))
        assertEquals(3, CursorDrag.wholeSteps(step * 3.1f, step))
    }

    @Test
    fun aFastDragCostsLessTravelPerCharacterButNeverSkipsOne() {
        assertTrue(CursorDrag.FAST_STEP_DP < CursorDrag.STEP_DP)
        // One step's travel in one batched touch event is one character, fast or not: the two-
        // characters-per-step rule made a quick flick jump by two.
        val fastStep = CursorDrag.FAST_STEP_DP
        assertEquals(1, CursorDrag.wholeSteps(fastStep * 1.6f, fastStep))
    }

    @Test
    fun speedSwitchesWithHysteresis() {
        // 12 dp a frame (750 dp/s) is still a deliberate drag; 20 dp a frame is a fling.
        assertFalse(CursorDrag.fast(false, distanceDp = 12f, elapsedMs = 16L))
        assertTrue(CursorDrag.fast(false, distanceDp = 20f, elapsedMs = 16L))
        // Slowing a little stays fast; only a real slowdown returns to the fine step.
        assertTrue(CursorDrag.fast(true, distanceDp = 12f, elapsedMs = 16L))
        assertFalse(CursorDrag.fast(true, distanceDp = 6f, elapsedMs = 16L))
        // Two events at the same instant say nothing about speed.
        assertTrue(CursorDrag.fast(true, distanceDp = 0f, elapsedMs = 0L))
    }

    @Test
    fun ticksAreThrottledTo30Ms() {
        assertFalse(CursorDrag.tickDue(now = 1_020L, lastTick = 1_000L))
        assertTrue(CursorDrag.tickDue(now = 1_030L, lastTick = 1_000L))
    }
}

class KeyHapticsTest {
    @Test
    fun pressUsesTheKeyboardPressTickWhereThePlatformHasIt() {
        assertEquals(HapticFeedbackConstants.KEYBOARD_PRESS, KeyHaptics.press(29))
        assertEquals(HapticFeedbackConstants.KEYBOARD_PRESS, KeyHaptics.press(36))
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, KeyHaptics.press(26))
    }

    @Test
    fun releaseUsesTheKeyboardReleaseTickOnlyWhereItExists() {
        assertEquals(HapticFeedbackConstants.KEYBOARD_RELEASE, KeyHaptics.release(29))
        assertNull(KeyHaptics.release(26))
    }

    @Test
    fun repeatsAndHoldsUseTheirOwnLightConstants() {
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, KeyHaptics.REPEAT)
        assertEquals(HapticFeedbackConstants.LONG_PRESS, KeyHaptics.HOLD)
    }
}
