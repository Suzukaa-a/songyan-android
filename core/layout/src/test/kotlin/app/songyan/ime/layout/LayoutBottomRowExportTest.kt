package app.songyan.ime.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A layout exported and imported again draws the bottom row the original draws, and always keeps 中/英. */
class LayoutBottomRowExportTest {
    private fun roundTrip(layout: LayoutDefinition): LayoutDefinition =
        LayoutJson.decode(LayoutBottomRow.exportable(layout).encoded())

    @Test
    fun anExportedBuiltInLayoutDrawsTheSameBottomRowWhenImported() {
        for (layout in BuiltinLayouts.all) {
            assertEquals(layout.id, LayoutBottomRow.preview(layout), LayoutBottomRow.preview(roundTrip(layout)))
        }
    }

    @Test
    fun theExportCarriesTheRowTheKeyboardDrawsNotTheStandIn() {
        val exported = LayoutBottomRow.exportable(BuiltinLayouts.zhuyin41)
        val labels =
            exported.rows
                .last()
                .keys
                .map { it.label }
        assertEquals(listOf("123", "中/英", "，", "空格", "。", "⏎"), labels)
    }

    @Test
    fun aFileMadeFromTheExportKeepsThePageKeyAndTheMarksWhenAKeyIsEdited() {
        val exported = LayoutBottomRow.exportable(BuiltinLayouts.zhuyin41)
        val keys =
            exported.rows
                .last()
                .keys
                .toMutableList()
        keys[2] = keys[2].copy(label = "'", action = LayoutAction.literal("'"))
        val edited = exported.copy(id = "mine", rows = exported.rows.dropLast(1) + LayoutRow(keys))

        val drawn = LayoutBottomRow.authored(edited)?.let { LayoutBottomRow.reachable(edited, it) }
        assertNotNull(drawn)
        assertTrue(drawn!!.any { it.action.type == LayoutActionKind.LANGUAGE_SWITCH })
        assertTrue(drawn.any { it.action.type == LayoutActionKind.PAGE_SWITCH })
    }

    @Test
    fun aLayoutWhoseBottomRowHasNoLanguageKeyGetsOne() {
        val stripped =
            BuiltinLayouts.zhuyin41.let { base ->
                val row =
                    LayoutRow(
                        base.rows
                            .last()
                            .keys
                            .filter { it.action.type != LayoutActionKind.MODE_SWITCH } +
                            LayoutKey("'", action = LayoutAction.literal("'")),
                    )
                base.copy(id = "mine", rows = base.rows.dropLast(1) + row)
            }
        val drawn = LayoutBottomRow.reachable(stripped, LayoutBottomRow.authored(stripped)!!)
        assertEquals(LayoutActionKind.LANGUAGE_SWITCH, drawn.first().action.type)
    }

    @Test
    fun aStandInRowStillLeavesTheFooterToTheKeyboard() {
        assertNull(LayoutBottomRow.authored(BuiltinLayouts.qwerty26))
    }
}
