package app.songyan.ime.ime

import app.songyan.ime.keyboard.engine.SpellingRailChoices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class T9RailItemsTest {
    @Test
    fun anEmptyChoiceSetHasNoItems() {
        assertTrue(T9RailPolicy.items(SpellingRailChoices()).isEmpty())
    }

    @Test
    fun locksComeBeforeReadingsAndAreTappable() {
        val items = T9RailPolicy.items(SpellingRailChoices(lockOptions = listOf("mi", "ni"), readings = listOf("ni2")))
        assertEquals(
            listOf("mi" to RailKind.LOCK, "ni" to RailKind.LOCK, "ni2" to RailKind.READING),
            items.map {
                it.label to
                    it.kind
            },
        )
        assertTrue(items.none { it.selected })
    }

    @Test
    fun anAppliedLockHidesTheOptionsAndShowsItselfFirst() {
        val items =
            T9RailPolicy.items(
                SpellingRailChoices(
                    canUndoLock = true,
                    lockedOption = "ni",
                    lockOptions = listOf("mi", "ni"),
                    readings = listOf("hao"),
                ),
            )
        assertEquals(listOf(RailKind.LOCKED, RailKind.READING), items.map { it.kind })
        val again =
            T9RailPolicy.items(
                SpellingRailChoices(canUndoLock = true, lockedOption = "xi", readings = listOf("xi")),
            )
        assertEquals(listOf(RailKind.LOCKED), again.map { it.kind })
        assertTrue(items[0].selected)
        assertFalse(items[1].selected)
    }

    @Test
    fun theChosenReadingsShowAsOneSelectedItem() {
        val items =
            T9RailPolicy.items(
                SpellingRailChoices(isFiltering = true, readingPrefix = listOf("ni", "hao"), readings = listOf("ma")),
            )
        assertEquals(listOf("ni hao" to RailKind.PREFIX, "ma" to RailKind.READING), items.map { it.label to it.kind })
    }

    @Test
    fun theColumnScrollsThroughEveryReadingByPixel() {
        // A 300 px column shows three 100 px readings of five.
        assertEquals(100f, RailColumn.pitch(300f))
        assertEquals(200f, RailColumn.maxOffset(5, 300f))
        assertEquals(0f, RailColumn.maxOffset(2, 300f))
        // Unscrolled, a touch reads the reading under it; scrolled 150 px, the list moved by 1.5 readings.
        assertEquals(0, RailColumn.itemAt(1050f, 0f, 5, 1000f, 300f))
        assertEquals(2, RailColumn.itemAt(1250f, 0f, 5, 1000f, 300f))
        assertEquals(1, RailColumn.itemAt(1000f, 150f, 5, 1000f, 300f))
        assertEquals(3, RailColumn.itemAt(1220f, 150f, 5, 1000f, 300f))
        // Below the last reading is nothing.
        assertEquals(-1, RailColumn.itemAt(1250f, 0f, 2, 1000f, 300f))
        // A slot is named after the reading at its centre.
        assertEquals(listOf(2, 3, 4), (0..2).map { RailColumn.itemAtSlot(it, 200f, 5, 300f) })
        assertEquals(listOf(1, 2, 3), (0..2).map { RailColumn.itemAtSlot(it, 120f, 5, 300f) })
    }
}
