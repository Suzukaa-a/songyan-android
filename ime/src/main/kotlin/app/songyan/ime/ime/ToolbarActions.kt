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
        toolsAvailable: Boolean,
    ): List<ToolbarView.Action> {
        if (hidden) return emptyList()
        val chosen =
            layout.shown
                .filter { it != ToolbarItem.ONE_HAND || oneHandAvailable }
                .filter { it != ToolbarItem.TOOLS || toolsAvailable }
                .map { ToolbarView.Action.valueOf(it.name) }
        return chosen +
            listOfNotNull(ToolbarView.Action.SWITCH_INPUT_METHOD.takeIf { switcher }, ToolbarView.Action.HIDE)
    }
}
