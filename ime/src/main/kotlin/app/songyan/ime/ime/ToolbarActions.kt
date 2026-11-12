package app.songyan.ime.ime

import app.songyan.ime.candidate.ToolbarItem
import app.songyan.ime.candidate.ToolbarLayout

/** Builds the toolbar's entry list from the user's choice and what the editor and window can do. */
internal object ToolbarActions {
    /**
     * The user's shown items in their order, minus what is unavailable, then the system switcher when
     * asked for, then the collapse button, which is always last. A choice can only remove entries,
     * never add one the window or field cannot offer. Width overflow is dropped later, by the view,
     * which knows its real width.
     */
    fun assemble(
        layout: ToolbarLayout,
        hidden: Boolean,
        switcher: Boolean,
        oneHandAvailable: Boolean,
        toolsAvailable: Boolean = false,
        voiceAvailable: Boolean = false,
        voiceUndoAvailable: Boolean = false,
    ): List<ToolbarView.Action> {
        if (hidden) return emptyList()
        val chosen =
            layout.shown
                .filter { it != ToolbarItem.ONE_HAND || oneHandAvailable }
                .map { ToolbarView.Action.valueOf(it.name) }
        // After a voice result the microphone slot offers to take it back; the two never show
        // together, and neither is a user-chosen toolbar item.
        val voice =
            when {
                voiceUndoAvailable -> ToolbarView.Action.VOICE_UNDO
                voiceAvailable -> ToolbarView.Action.VOICE_INPUT
                else -> null
            }
        return chosen +
            listOfNotNull(
                ToolbarView.Action.TOOLS.takeIf { toolsAvailable },
                voice,
                ToolbarView.Action.SWITCH_INPUT_METHOD.takeIf { switcher },
                ToolbarView.Action.HIDE,
            )
    }
}
