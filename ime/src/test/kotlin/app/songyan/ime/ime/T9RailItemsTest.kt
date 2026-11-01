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
    fun theWindowScrollsAndClamps() {
        val items = (1..5).map { RailItem("r$it", RailKind.READING) }
        assertEquals(listOf("r1", "r2", "r3"), T9RailPolicy.window(items, 0).map { it.label })
        assertEquals(listOf("r3", "r4", "r5"), T9RailPolicy.window(items, 2).map { it.label })
        assertEquals(listOf("r3", "r4", "r5"), T9RailPolicy.window(items, 9).map { it.label })
        assertEquals(listOf("r1", "r2", "r3"), T9RailPolicy.window(items, -4).map { it.label })
        assertEquals(2, T9RailPolicy.window(items.take(2), 3).size)
    }
}
