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
    ) = ToolbarActions.assemble(layout, hidden, switcher, oneHand)

    @Test
    fun theDefaultIsTodaysToolbar() {
        assertEquals(
            listOf(a.CLIPBOARD, a.INPUT_METHODS, a.EMOJI, a.EDIT, a.ONE_HAND, a.SETTINGS, a.HIDE),
            assemble(),
        )
        assertEquals(ToolbarView.DEFAULT_ACTIONS, assemble())
    }

    @Test
    fun theUsersOrderIsRespectedAndHideIsAlwaysLast() {
        val layout =
            ToolbarLayout.DEFAULT
                .withShown(ToolbarItem.CLIPBOARD, false)
                .moveEarlier(ToolbarItem.SETTINGS)
        assertEquals(
            listOf(a.INPUT_METHODS, a.EMOJI, a.EDIT, a.SETTINGS, a.ONE_HAND, a.HIDE),
            assemble(layout),
        )
        assertEquals(listOf(a.HIDE), assemble(layout.copy(entries = layout.entries.map { it.copy(shown = false) })))
    }

    @Test
    fun unavailableItemsAreLeftOutAndNeverForced() {
        assertEquals(
            listOf(a.CLIPBOARD, a.INPUT_METHODS, a.EMOJI, a.EDIT, a.SETTINGS, a.HIDE),
            assemble(oneHand = false),
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
    fun theMicrophoneAppearsOnlyWhileVoiceIsOffered() {
        assertEquals(false, a.VOICE_INPUT in assemble())

        val offered =
            ToolbarActions.assemble(ToolbarLayout.DEFAULT, false, false, true, voiceAvailable = true)
        assertEquals(a.VOICE_INPUT, offered.dropLast(1).last())
        assertEquals(a.HIDE, offered.last())

        // After a committed result the same slot offers undo instead of another take.
        val undo =
            ToolbarActions.assemble(
                ToolbarLayout.DEFAULT,
                false,
                false,
                true,
                voiceAvailable = true,
                voiceUndoAvailable = true,
            )
        assertEquals(a.VOICE_UNDO, undo.dropLast(1).last())
        assertEquals(false, a.VOICE_INPUT in undo)
    }

    @Test
    fun theMicrophoneSitsBeforeTheSystemSwitcher() {
        val shown =
            ToolbarActions.assemble(
                ToolbarLayout.DEFAULT,
                false,
                switcher = true,
                oneHandAvailable = true,
                voiceAvailable = true,
            )
        assertEquals(listOf(a.VOICE_INPUT, a.SWITCH_INPUT_METHOD, a.HIDE), shown.takeLast(3))
    }

    @Test
    fun onlyTheUsersChoicesMayBeDroppedForWidth() {
        assertEquals(true, a.CLIPBOARD.chosen)
        assertEquals(false, a.HIDE.chosen)
        assertEquals(false, a.SWITCH_INPUT_METHOD.chosen)
        assertEquals(false, a.VOICE_INPUT.chosen)
    }
}
