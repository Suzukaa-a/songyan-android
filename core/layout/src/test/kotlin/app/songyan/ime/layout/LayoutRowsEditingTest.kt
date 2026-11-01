package app.songyan.ime.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutRowsEditingTest {
    private fun key(label: String) = LayoutKey(label, action = LayoutAction.literal(label))

    private fun rows(vararg row: String): List<LayoutRow> =
        row.map { text -> LayoutRow(text.map { key(it.toString()) }) }

    private fun labels(rows: List<LayoutRow>) = rows.map { row -> row.keys.joinToString("") { it.label } }

    @Test
    fun removingAKeyKeepsTheOthersInOrder() {
        assertEquals(listOf("ac", "de"), labels(LayoutRowsEditing.removeKey(rows("abc", "de"), 0, 1)!!))
    }

    @Test
    fun removingTheLastKeyOfARowRemovesTheRow() {
        assertEquals(listOf("ab"), labels(LayoutRowsEditing.removeKey(rows("ab", "c"), 1, 0)!!))
    }

    @Test
    fun theOnlyKeyOfTheOnlyRowStays() {
        val single = rows("a")
        assertFalse(LayoutRowsEditing.canRemoveKey(single, 0, 0))
        assertNull(LayoutRowsEditing.removeKey(single, 0, 0))
        assertNull(LayoutRowsEditing.removeKey(single, 3, 0))
    }

    @Test
    fun insertingPutsTheKeyAtTheIndexAndRespectsTheRowLimit() {
        assertEquals(listOf("axb"), labels(LayoutRowsEditing.insertKey(rows("ab"), 0, 1, key("x"))!!))
        assertEquals(listOf("abx"), labels(LayoutRowsEditing.insertKey(rows("ab"), 0, 2, key("x"))!!))
        assertNull(LayoutRowsEditing.insertKey(rows("ab"), 0, 3, key("x")))
        val full = listOf(LayoutRow(List(LayoutResourceBudget.KEYS_PER_REGION) { key("k") }))
        assertNull(LayoutRowsEditing.insertKey(full, 0, 0, key("x")))
    }

    @Test
    fun movingSwapsWithTheNeighbourAndStopsAtTheEdges() {
        assertEquals(listOf("bac"), labels(LayoutRowsEditing.moveKey(rows("abc"), 0, 0, +1)!!))
        assertEquals(listOf("acb"), labels(LayoutRowsEditing.moveKey(rows("abc"), 0, 2, -1)!!))
        assertNull(LayoutRowsEditing.moveKey(rows("abc"), 0, 0, -1))
        assertNull(LayoutRowsEditing.moveKey(rows("abc"), 0, 2, +1))
    }

    @Test
    fun addingARowAppendsOneKeyAndStopsAtTheLimit() {
        assertEquals(listOf("ab", "x"), labels(LayoutRowsEditing.addRow(rows("ab"), key("x"))!!))
        val full = List(LayoutResourceBudget.ROWS) { LayoutRow(listOf(key("k"))) }
        assertNull(LayoutRowsEditing.addRow(full, key("x")))
    }

    @Test
    fun movingARowSwapsWithItsNeighbour() {
        assertEquals(listOf("c", "ab"), labels(LayoutRowsEditing.moveRow(rows("ab", "c"), 0, +1)!!))
        assertNull(LayoutRowsEditing.moveRow(rows("ab", "c"), 0, -1))
    }

    @Test
    fun anEditNeverTouchesTheInput() {
        val original = rows("abc", "de")
        LayoutRowsEditing.removeKey(original, 0, 0)
        LayoutRowsEditing.insertKey(original, 0, 0, key("x"))
        assertEquals(listOf("abc", "de"), labels(original))
    }

    @Test
    fun adjustingTheSplitClampsEveryCutToItsRow() {
        val split = LayoutSplitConfiguration.of(rowBreaks = listOf(5, 5, 5))
        val adjusted = LayoutRowsEditing.adjusted(split, rows("abcdef", "abc", "ab"))
        // A cut never lands outside its row: at most keys-1, at least 1.
        assertEquals(listOf(5, 2, 1), adjusted.rowBreaks)
        // A row without a stored cut gets the middle one.
        assertEquals(
            listOf(2, 2),
            LayoutRowsEditing.adjusted(LayoutSplitConfiguration(), rows("abc", "abcd")).rowBreaks,
        )
        assertTrue(adjusted.rowBreaks.size == 3)
    }
}
