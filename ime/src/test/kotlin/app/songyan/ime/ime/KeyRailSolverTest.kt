package app.songyan.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyRailSolverTest {
    @Test
    fun spansDivideTheColumnAndTileItWithoutAGap() {
        val slots = KeyRailSolver.slots(10f, 80f, 5f, 200f, listOf(1.0, 1.0, 2.0), minimumKeyHeight = 10f)
        assertEquals(listOf(50f, 50f, 100f), slots.map { it.bottom - it.top })
        assertEquals(5f, slots.first().top, 0.01f)
        assertEquals(slots[0].bottom, slots[1].top, 0.01f)
        assertEquals(205f, slots.last().bottom, 0.01f)
        assertTrue(slots.all { it.left == 10f && it.right == 90f })
    }

    @Test
    fun aRailThatCannotHoldItsMinimumsShrinksToFitInsteadOfOverflowing() {
        val slots = KeyRailSolver.slots(0f, 50f, 0f, 100f, List(5) { 1.0 }, minimumKeyHeight = 40f)
        assertEquals(100f, slots.last().bottom, 0.01f)
        assertEquals(5, slots.size)
    }

    @Test
    fun anEmptyRailHasNoSlots() {
        assertTrue(KeyRailSolver.slots(0f, 50f, 0f, 100f, emptyList(), 10f).isEmpty())
    }
}
