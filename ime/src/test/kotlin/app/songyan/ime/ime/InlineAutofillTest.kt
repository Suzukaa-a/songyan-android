package app.songyan.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineAutofillTest {
    @Test
    fun theBatchWaitsForEverySuggestionAndKeepsTheirOrder() {
        val batch = InlineBatch<String>(3)
        assertNull(batch.put(2, "c"))
        assertNull(batch.put(0, "a"))
        assertEquals(listOf("a", "b", "c"), batch.put(1, "b"))
    }

    @Test
    fun aSuggestionThatCannotBeShownIsLeftOutNotWaitedFor() {
        val batch = InlineBatch<String>(2)
        assertNull(batch.put(1, null))
        assertEquals(listOf("a"), batch.put(0, "a"))
    }

    @Test
    fun aBatchWhereNothingCouldBeShownIsEmpty() {
        val batch = InlineBatch<String>(1)
        assertEquals(emptyList<String>(), batch.put(0, null))
    }

    @Test
    fun anAnswerForAnUnknownSlotIsIgnored() {
        assertNull(InlineBatch<String>(1).put(5, "x"))
    }

    @Test
    fun aLateAnswerForAnOlderResponseOrFieldIsStale() {
        val generation = InlineGeneration()
        val first = generation.next()
        assertTrue(generation.isCurrent(first))
        val second = generation.next()
        assertFalse(generation.isCurrent(first))
        generation.invalidate()
        assertFalse(generation.isCurrent(second))
    }

    @Test
    fun theStripIsKeptOnlyWhileAChipHasRoom() {
        assertFalse(InlineChipCheck.keepsStrip(emptyList()))
        assertFalse(InlineChipCheck.keepsStrip(listOf(0 to 126, 210 to 0)))
        assertTrue(InlineChipCheck.keepsStrip(listOf(0 to 126, 210 to 126)))
        assertEquals(listOf(1), InlineChipCheck.visible(listOf(0 to 126, 210 to 126)))
    }
}
