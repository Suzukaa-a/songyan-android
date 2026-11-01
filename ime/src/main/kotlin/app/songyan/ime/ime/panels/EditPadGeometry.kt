package app.songyan.ime.ime.panels

import app.songyan.ime.ime.EditAction

private const val PAD_COLUMNS = 4
private const val PAD_ROWS = 4

/**
 * The keys of the edit pad and where they sit on a grid of [COLUMNS] by [ROWS] units: a D-pad cross
 * in the first three columns and rows (Up on top, Left and Right beside Select, Down below), a row of
 * line start, select all and line end under it, and a column of editing keys on the right. The cross
 * leaves its corners empty, as text editors with a pad do, except Undo, which keeps the top left.
 * The uniform grid of the other panels cannot express that shape, so the pad has its own.
 */
enum class EditPadKey(
    val column: Int,
    val row: Int,
    /** What the key does to the editor; null for the Select toggle, which only changes the pad's mode. */
    val action: EditAction?,
    /** Held, the key repeats the way the keyboard's Backspace does. */
    val repeats: Boolean = false,
) {
    UNDO(0, 0, EditAction.UNDO),
    UP(1, 0, EditAction.UP, repeats = true),
    LEFT(0, 1, EditAction.LEFT, repeats = true),
    SELECT(1, 1, null),
    RIGHT(2, 1, EditAction.RIGHT, repeats = true),
    DOWN(1, 2, EditAction.DOWN, repeats = true),
    LINE_START(0, 3, EditAction.LINE_START),
    SELECT_ALL(1, 3, EditAction.SELECT_ALL),
    LINE_END(2, 3, EditAction.LINE_END),
    DELETE(3, 0, EditAction.DELETE, repeats = true),
    COPY(3, 1, EditAction.COPY),
    CUT(3, 2, EditAction.CUT),
    PASTE(3, 3, EditAction.PASTE),
    ;

    /** Moves the caret, so it extends the selection while Select is on. */
    val movesCaret: Boolean
        get() =
            when (action) {
                EditAction.LEFT,
                EditAction.RIGHT,
                EditAction.UP,
                EditAction.DOWN,
                EditAction.LINE_START,
                EditAction.LINE_END,
                -> true
                else -> false
            }

    /** The arrows are drawn as a glyph alone, however much room the key has. */
    val isArrow: Boolean
        get() = this == UP || this == DOWN || this == LEFT || this == RIGHT

    companion object {
        const val COLUMNS = PAD_COLUMNS
        const val ROWS = PAD_ROWS
    }
}

/** What the editor and the clipboard allow right now; it decides which keys of the pad are live. */
data class EditAvailability(
    val hasSelection: Boolean,
    val canPaste: Boolean,
)

/**
 * The mode and the live keys of the edit pad. Pure, so the rules (what Select does, what ends it,
 * which keys are dimmed) are tested without a view.
 */
class EditPadState {
    /** Select is on: the keys that move the caret extend the selection instead. */
    var selecting = false
        private set

    var availability = EditAvailability(hasSelection = false, canPaste = true)

    /** Select toggles the mode and keeps whatever is selected, so turning it off collapses nothing. */
    fun toggleSelect() {
        selecting = !selecting
    }

    fun reset() {
        selecting = false
    }

    /** Copy and Cut need a selection, Paste needs something on the clipboard; every other key is always live. */
    fun isEnabled(key: EditPadKey): Boolean =
        when (key) {
            EditPadKey.COPY, EditPadKey.CUT -> availability.hasSelection
            EditPadKey.PASTE -> availability.canPaste
            else -> true
        }

    /** Whether pressing [key] now extends the selection. */
    fun extends(key: EditPadKey): Boolean = selecting && key.movesCaret

    /**
     * The mode after [key] was handled. Select all turns it on, so the arrows then shrink the whole text;
     * Copy, Cut, and a Delete or Paste that replaced the selection finish the selection work and turn it off.
     */
    fun afterKey(
        key: EditPadKey,
        hadSelection: Boolean,
    ) {
        when (key) {
            EditPadKey.SELECT_ALL -> selecting = true
            EditPadKey.COPY, EditPadKey.CUT -> selecting = false
            EditPadKey.DELETE, EditPadKey.PASTE -> if (hadSelection) selecting = false
            else -> Unit
        }
    }
}

object EditPadGeometry {
    /** Below this height a key shows its name alone: an icon above a name no longer fits legibly. */
    const val STACKED_MIN_HEIGHT_DP = 40.0

    /**
     * The box of every [EditPadKey] (same order as [EditPadKey.entries]) in a pad of [widthDp] by
     * [heightDp] whose top left is ([leftDp], [topDp]). The boxes never overlap; the three empty
     * corners of the cross are the only part of the pad no key covers.
     */
    fun cells(
        widthDp: Double,
        heightDp: Double,
        leftDp: Double = 0.0,
        topDp: Double = 0.0,
    ): List<PanelBox> {
        val unitWidth = widthDp / EditPadKey.COLUMNS
        val unitHeight = heightDp / EditPadKey.ROWS
        return EditPadKey.entries.map { key ->
            PanelBox(
                left = leftDp + key.column * unitWidth,
                top = topDp + key.row * unitHeight,
                right = leftDp + (key.column + 1) * unitWidth,
                bottom = topDp + (key.row + 1) * unitHeight,
            )
        }
    }

    /** Whether a key of [heightDp] stacks its icon above its name. */
    fun stacksLabel(heightDp: Double): Boolean = heightDp >= STACKED_MIN_HEIGHT_DP

    /**
     * The wait before a held key's first repeat: the keyboard's Backspace delay, never shorter than the
     * system's touch and hold delay, which users lengthen in the accessibility settings.
     */
    fun repeatDelayMs(
        systemLongPressMs: Long,
        keyboardDelayMs: Long,
    ): Long = maxOf(systemLongPressMs, keyboardDelayMs)
}
