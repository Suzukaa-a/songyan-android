package app.songyan.ime.ime

import app.songyan.ime.candidate.ToolbarItem
import app.songyan.ime.candidate.ToolbarLayout
import org.junit.Assert.assertEquals
import org.junit.Test
import app.songyan.ime.ime.ToolbarView.Action as a

class ToolbarActionsTest {
    private fun assemble(
        layout: ToolbarLayout = ToolbarLayout.DEFAULT,
        hidden: Boolean = false,
        switcher: Boolean = false,
        oneHand: Boolean = true,
        tools: Boolean = true,
    ) = ToolbarActions.assemble(layout, hidden, switcher, oneHand, tools)

    @Test
    fun theDefaultIsTodaysToolbarWithToolsAfterEdit() {
        assertEquals(
            listOf(a.CLIPBOARD, a.INPUT_METHODS, a.EMOJI, a.EDIT, a.TOOLS, a.ONE_HAND, a.SETTINGS, a.HIDE),
            assemble(),
        )
        assertEquals(ToolbarView.DEFAULT_ACTIONS, assemble(tools = false))
    }

    @Test
    fun theUsersOrderIsRespectedAndHideIsAlwaysLast() {
        val layout =
            ToolbarLayout.DEFAULT
                .withShown(ToolbarItem.CLIPBOARD, false)
                .moveEarlier(ToolbarItem.SETTINGS)
        assertEquals(
            listOf(a.INPUT_METHODS, a.EMOJI, a.EDIT, a.TOOLS, a.SETTINGS, a.ONE_HAND, a.HIDE),
            assemble(layout),
        )
        assertEquals(listOf(a.HIDE), assemble(layout.copy(entries = layout.entries.map { it.copy(shown = false) })))
    }

    @Test
    fun unavailableItemsAreLeftOutAndNeverForced() {
        assertEquals(
            listOf(a.CLIPBOARD, a.INPUT_METHODS, a.EMOJI, a.EDIT, a.SETTINGS, a.HIDE),
            assemble(oneHand = false, tools = false),
        )
    }

    @Test
    fun theSystemSwitcherSitsBeforeHide() {
        val shown = assemble(ToolbarLayout.DEFAULT.withShown(ToolbarItem.EMOJI, false), switcher = true)
        assertEquals(listOf(a.SWITCH_INPUT_METHOD, a.HIDE), shown.takeLast(2))
        assertEquals(false, a.EMOJI in shown)
    }

    @Test
    fun aHiddenToolbarShowsNothing() {
        assertEquals(emptyList<ToolbarView.Action>(), assemble(hidden = true, switcher = true))
    }

    @Test
    fun onlyTheUsersChoicesMayBeDroppedForWidth() {
        assertEquals(true, a.CLIPBOARD.chosen)
        assertEquals(true, a.TOOLS.chosen)
        assertEquals(false, a.HIDE.chosen)
        assertEquals(false, a.SWITCH_INPUT_METHOD.chosen)
        assertEquals(false, a.VOICE_INPUT.chosen)
    }
}
