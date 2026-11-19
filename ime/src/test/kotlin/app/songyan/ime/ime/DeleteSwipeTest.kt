package app.songyan.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeleteSwipeTest {
    private val startX = 1000f
    private val startPx = 14f * 3
    private val step = 24f * 3

    private fun move(
        state: DeleteSwipe.State,
        x: Float,
        stepPx: Float = step,
        max: Int = Int.MAX_VALUE,
    ) = DeleteSwipe.move(state, x, startX, startPx, stepPx, max)

    private val begun = DeleteSwipe.State(0, startX)

    @Test
    fun startsOnlyLeftwardsPastTheThresholdAndMostlyHorizontal() {
        assertFalse(DeleteSwipe.starts(-12f, 0f))
        assertTrue(DeleteSwipe.starts(-14f, 0f))
        assertTrue(DeleteSwipe.starts(-30f, 29f))
        assertFalse("more vertical than horizontal", DeleteSwipe.starts(-30f, 31f))
        assertFalse("rightwards never starts", DeleteSwipe.starts(30f, 0f))
    }

    @Test
    fun oneCharacterPerStep() {
        assertEquals(0, move(begun, startX - startPx - 1f).count)
        assertEquals(1, move(begun, startX - step - 1f).count)
        assertEquals(3, move(begun, startX - step * 3 - 1f).count)
    }

    @Test
    fun fastDragHalvesTheStep() {
        val fastStep = 12f * 3
        assertEquals(1, move(begun, startX - startPx - 1f, fastStep).count)
        assertEquals(3, move(begun, startX - fastStep * 3 - 1f, fastStep).count)
    }

    @Test
    fun backAtTheStartIsZero() {
        val deep = move(begun, startX - step * 3 - 1f)
        assertEquals(0, move(deep, startX - startPx + 1f).count)
        assertEquals(startX, move(deep, startX - 2f).anchorX, 0f)
    }

    @Test
    fun rightOfTheStartNeverSelects() {
        assertEquals(0, move(begun, startX + 500f).count)
        assertEquals(0, move(begun, startX).count)
    }

    @Test
    fun slidingBackShrinksOneCharacterPerStep() {
        val three = move(begun, startX - step * 3 - 1f)
        assertEquals(2, move(three, startX - step * 2).count)
    }

    @Test
    fun reversingAtTheClampReactsOnTheNextStep() {
        val clamped = move(begun, startX - step * 10 - 1f, max = 2)
        assertEquals(2, clamped.count)
        // The anchor kept following the finger: one step back shrinks at once.
        val back = move(clamped, startX - step * 9, max = 2)
        assertEquals(1, back.count)
    }
}
