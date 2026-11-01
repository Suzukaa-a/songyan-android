package app.songyan.ime.ime.panels

import app.songyan.ime.content.SymbolCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class SymbolRailSelectionTest {
    private val math = SymbolCategory(id = "math", name = "数学", order = 1)
    private val arrows = SymbolCategory(id = "arrow", name = "箭头", order = 2)

    @Test
    fun theOpenCategoryIsFoundByIdWhenTheRecentSlotAppears() {
        // Before the first insert the rail starts with the categories themselves.
        assertEquals(0, SymbolRailSelection.reopenIndex(listOf(math, arrows), false, "math"))
        // After an insert the recent slot takes index 0, so the same category moved to 1.
        assertEquals(1, SymbolRailSelection.reopenIndex(listOf(null, math, arrows), false, "math"))
        assertEquals(2, SymbolRailSelection.reopenIndex(listOf(null, math, arrows), false, "arrow"))
    }

    @Test
    fun theRecentSlotIsAlwaysPositional() {
        assertEquals(0, SymbolRailSelection.reopenIndex(listOf(null, math, arrows), true, "arrows"))
        // An empty recent list has no slot; the first category is the honest fallback.
        assertEquals(0, SymbolRailSelection.reopenIndex(listOf(math, arrows), true, null))
    }

    @Test
    fun aRemovedCategoryFallsBackToTheFirstSlot() {
        assertEquals(0, SymbolRailSelection.reopenIndex(listOf(null, arrows), false, "math"))
        assertEquals(0, SymbolRailSelection.reopenIndex(emptyList(), false, "math"))
        assertEquals(0, SymbolRailSelection.reopenIndex(emptyList(), true, null))
    }
}
