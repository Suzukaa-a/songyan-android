package app.songyan.ime.ime

import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.layout.LayoutActionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The last letter row of the 26-, 18- and 14-key pads: the key in the Shift slot and Delete are the
 * same width on every pad, and the merged pads put the syllable separator where Shift sits.
 */
class MergedPadSideKeysTest {
    private val inner = 1060f
    private val gap = 13f

    private fun lastLetterRow(layout: KeyboardLayoutKind): List<Pair<String, Float>> {
        val config =
            KeyboardPages.Config(
                layout = layout,
                englishDirectInput = false,
                symbolMarksAscii = false,
                languageSwitch = true,
                lettersReachable = true,
            )
        val rows =
            KeyRowSolver
                .columns(KeyboardPages.definition(null, config).rows)
                .filter { row -> row.isNotEmpty() && row.none { it.action.type == LayoutActionKind.SPACE } }
        val slots = KeyRowSolver.slots(rows, 0f, 0f, 100f, inner, gap)
        val keys = rows.last()
        return keys.mapIndexed { index, key ->
            val name = if (KeyboardPages.isMergedSeparator(key)) "separator" else key.action.type.name
            name to slots.last()[index].let { it.right - it.left }
        }
    }

    @Test
    fun everyPadHasTheSameShiftSlotAndDeleteWidth() {
        val standard = lastLetterRow(KeyboardLayoutKind.QWERTY26)
        val shift = standard.first().second
        val delete = standard.last().second
        assertEquals("SHIFT", standard.first().first)
        assertEquals(KeyRowSolver.standardSideWidth(inner, gap), shift, 0.5f)
        for (layout in listOf(KeyboardLayoutKind.MERGED14, KeyboardLayoutKind.MERGED18)) {
            val row = lastLetterRow(layout)
            assertEquals("$layout Shift slot", shift, row.first().second, 0.5f)
            assertEquals("$layout Delete", delete, row.last().second, 0.5f)
            val used = row.sumOf { it.second.toDouble() }.toFloat() + (row.size - 1) * gap
            assertEquals("$layout row fills the width", inner, used, 1f)
        }
    }

    @Test
    fun mergedPadsPutTheSeparatorInTheShiftSlot() {
        for (layout in listOf(KeyboardLayoutKind.MERGED14, KeyboardLayoutKind.MERGED18)) {
            val row = lastLetterRow(layout)
            assertEquals("$layout", "separator", row.first().first)
            assertTrue("$layout keeps no Shift", row.none { it.first == "SHIFT" })
            assertEquals("$layout", "BACKSPACE", row.last().first)
        }
        assertEquals("SHIFT", lastLetterRow(KeyboardLayoutKind.QWERTY26).first().first)
    }
}
