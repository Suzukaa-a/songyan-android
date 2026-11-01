package app.songyan.ime.ime.panels

private const val PAD_COLUMNS = 6
private const val PAD_ROWS = 4

/**
 * The keys of the edit pad and where they sit on a grid of [COLUMNS] by [ROWS] units: a tall arrow
 * on each side of a central block, and a column of editing keys beside the right arrow. The uniform
 * grid of the other panels cannot express keys that span the whole height, so the pad has its own.
 *
 * Cut and Undo share a row with Select all and Copy instead of adding a fifth row: the pad keeps
 * the height of four rows on a short landscape window and nothing depends on whether text is selected.
 */
enum class EditPadKey(
    val column: Int,
    val row: Int,
    val columnSpan: Int,
    val rowSpan: Int,
    /** Held, the key repeats the way the keyboard's Backspace does. */
    val repeats: Boolean = false,
) {
    LEFT(0, 0, 1, PAD_ROWS, repeats = true),
    UP(1, 0, 2, 1, repeats = true),
    SELECT(1, 1, 2, 1),
    DOWN(1, 2, 2, 1, repeats = true),
    LINE_START(1, 3, 1, 1),
    LINE_END(2, 3, 1, 1),
    RIGHT(3, 0, 1, PAD_ROWS, repeats = true),
    SELECT_ALL(4, 0, 1, 1),
    UNDO(5, 0, 1, 1),
    COPY(4, 1, 1, 1),
    CUT(5, 1, 1, 1),
    PASTE(4, 2, 2, 1),
    DELETE(4, 3, 2, 1, repeats = true),
    ;

    companion object {
        const val COLUMNS = PAD_COLUMNS
        const val ROWS = PAD_ROWS
    }
}

object EditPadGeometry {
    /**
     * The box of every [EditPadKey] (same order as [EditPadKey.entries]) in a pad of [widthDp] by
     * [heightDp] whose top left is ([leftDp], [topDp]). The boxes tile the pad exactly.
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
                right = leftDp + (key.column + key.columnSpan) * unitWidth,
                bottom = topDp + (key.row + key.rowSpan) * unitHeight,
            )
        }
    }

    /**
     * The wait before a held key's first repeat: the keyboard's Backspace delay, never shorter than the
     * system's touch and hold delay, which users lengthen in the accessibility settings.
     */
    fun repeatDelayMs(
        systemLongPressMs: Long,
        keyboardDelayMs: Long,
    ): Long = maxOf(systemLongPressMs, keyboardDelayMs)
}
