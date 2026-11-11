package app.songyan.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelFadeTest {
    @Test
    fun enteringTheDragFiresOnceAndHidesLabels() {
        val fade = LabelFade()
        assertEquals(1f, fade.value(0L, 1f), 0f)
        assertTrue(fade.set(true, 0L, 1f))
        assertFalse(fade.set(true, 10L, 1f))
        assertEquals(0.5f, fade.value(75L, 1f), 0.001f)
        assertEquals(0f, fade.value(LabelFade.DURATION_MS, 1f), 0f)
        assertFalse(fade.animating(LabelFade.DURATION_MS, 1f))
    }

    @Test
    fun releaseFadesBackFromWhereItWas() {
        val fade = LabelFade()
        fade.set(true, 0L, 1f)
        assertTrue(fade.set(false, 300L, 1f))
        assertEquals(0f, fade.value(300L, 1f), 0f)
        assertEquals(1f, fade.value(450L, 1f), 0f)
        // A cancel halfway starts from the current opacity, not from fully hidden.
        val half = LabelFade()
        half.set(true, 0L, 1f)
        half.set(false, 75L, 1f)
        assertEquals(0.5f, half.value(75L, 1f), 0.001f)
    }

    @Test
    fun animatorScaleZeroJumps() {
        val fade = LabelFade()
        fade.set(true, 0L, 0f)
        assertEquals(0f, fade.value(0L, 0f), 0f)
        assertFalse(fade.animating(0L, 0f))
        fade.set(false, 1L, 0f)
        assertEquals(1f, fade.value(1L, 0f), 0f)
    }
}
