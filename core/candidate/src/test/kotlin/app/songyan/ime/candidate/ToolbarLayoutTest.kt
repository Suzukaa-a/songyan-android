package app.songyan.ime.candidate

import org.junit.Test

/** The idle toolbar's stored choice and the width it has to fit. */
class ToolbarLayoutTest {
    private fun order(layout: ToolbarLayout) = layout.entries.map { it.item }

    @Test
    fun theDefaultIsTodaysOrderWithToolsRightAfterEdit() {
        assertEquals(
            listOf(
                ToolbarItem.CLIPBOARD,
                ToolbarItem.INPUT_METHODS,
                ToolbarItem.EMOJI,
                ToolbarItem.EDIT,
                ToolbarItem.TOOLS,
                ToolbarItem.ONE_HAND,
                ToolbarItem.SETTINGS,
            ),
            ToolbarLayout.DEFAULT.shown,
        )
        assertTrue(ToolbarLayout.DEFAULT.isDefault)
    }

    @Test
    fun nothingStoredAndDamagedValuesGiveTheDefault() {
        assertEquals(ToolbarLayout.DEFAULT, ToolbarLayout.parse(null))
        assertEquals(ToolbarLayout.DEFAULT, ToolbarLayout.parse(""))
        assertEquals(ToolbarLayout.DEFAULT, ToolbarLayout.parse("   "))
        assertEquals(ToolbarLayout.DEFAULT, ToolbarLayout.parse("nonsense,,-also"))
    }

    @Test
    fun aChoiceSurvivesTheWireForm() {
        val chosen =
            ToolbarLayout.DEFAULT
                .withShown(ToolbarItem.EMOJI, false)
                .moveEarlier(ToolbarItem.SETTINGS)
                .moveLater(ToolbarItem.CLIPBOARD)
        assertEquals(chosen, ToolbarLayout.parse(chosen.toWire()))
        assertEquals(false, ToolbarLayout.parse(chosen.toWire()).shown.contains(ToolbarItem.EMOJI))
    }

    @Test
    fun everythingTurnedOffStaysOffAndIsNotTheDefault() {
        val none = ToolbarItem.entries.fold(ToolbarLayout.DEFAULT) { layout, item -> layout.withShown(item, false) }
        assertEquals(emptyList<ToolbarItem>(), none.shown)
        assertEquals(none, ToolbarLayout.parse(none.toWire()))
        assertFalse(none.isDefault)
    }

    @Test
    fun unknownRepeatedAndMissingNamesAreRepaired() {
        val parsed = ToolbarLayout.parse("settings,bogus,-edit,settings,clipboard")
        assertEquals(ToolbarItem.entries.size, parsed.entries.size)
        assertEquals(listOf(ToolbarItem.SETTINGS, ToolbarItem.EDIT, ToolbarItem.CLIPBOARD), order(parsed).take(3))
        assertFalse(ToolbarItem.EDIT in parsed.shown)
        // Items the value never mentioned come back on, so an old value cannot hide a new button.
        assertTrue(ToolbarItem.TOOLS in parsed.shown)
    }

    @Test
    fun movingStopsAtTheEnds() {
        val first = ToolbarLayout.DEFAULT
        assertFalse(first.canMoveEarlier(ToolbarItem.CLIPBOARD))
        assertEquals(first, first.moveEarlier(ToolbarItem.CLIPBOARD))
        assertFalse(first.canMoveLater(ToolbarItem.SETTINGS))
        assertEquals(first, first.moveLater(ToolbarItem.SETTINGS))
        assertEquals(ToolbarItem.INPUT_METHODS, first.moveEarlier(ToolbarItem.INPUT_METHODS).entries[0].item)
    }

    @Test
    fun theLimitIsTheNumberOfMinimumSlotsLessTheHideButton() {
        assertEquals(7, ToolbarCapacity.limit(360f))
        assertEquals(8, ToolbarCapacity.limit(411f))
        assertEquals(5, ToolbarCapacity.limit(300f))
        assertEquals(17, ToolbarCapacity.limit(800f))
        assertEquals(0, ToolbarCapacity.limit(20f))
        assertEquals(0, ToolbarCapacity.limit(0f))
    }

    private val icon = ToolbarCapacity.Slot(ToolbarCapacity.MIN_ICON_SLOT_DP, droppable = true)
    private val fixed = ToolbarCapacity.Slot(ToolbarCapacity.MIN_ICON_SLOT_DP, droppable = false)

    @Test
    fun overflowIsDroppedFromTheEndAndFixedEntriesStay() {
        // Room for four slots: three chosen entries and the fixed hide button.
        val slots = listOf(icon, icon, icon, icon, icon, fixed)
        val keep = ToolbarCapacity.keep(4 * ToolbarCapacity.MIN_ICON_SLOT_DP, slots)
        assertEquals(listOf(true, true, true, false, false, true), keep)
    }

    @Test
    fun aPinnedToolCountsTheRoomItNeeds() {
        val tool = ToolbarCapacity.Slot(80f, droppable = true)
        // 44 + 80 + 44 + 44 = 212 does not fit 180: the last chosen entry goes, the tool stays.
        assertEquals(
            listOf(true, true, false, true),
            ToolbarCapacity.keep(180f, listOf(icon, tool, icon, fixed)),
        )
        // At 100 the later entries go first, then the tool.
        assertEquals(
            listOf(true, false, false, true),
            ToolbarCapacity.keep(100f, listOf(icon, tool, icon, fixed)),
        )
    }

    @Test
    fun anUnknownWidthDropsNothing() {
        assertEquals(listOf(true, true), ToolbarCapacity.keep(0f, listOf(icon, icon)))
    }
}
