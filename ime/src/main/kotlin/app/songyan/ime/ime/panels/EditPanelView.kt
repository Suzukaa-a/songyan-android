package app.songyan.ime.ime.panels

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.ViewConfiguration
import app.songyan.ime.content.QuickContentPanelLayout
import app.songyan.ime.ime.EditAction
import app.songyan.ime.ime.KeyboardView
import app.songyan.ime.ime.R

private const val CELL_INSET_DP = 3f
private const val LABEL_TEXT_SP = 16f

/**
 * The edit panel: arrows, select, line start and end, select all, copy, cut, paste, undo and delete as one pad of keys.
 *
 * It only reports what was pressed ([onEdit]); the editor work happens behind that callback. The
 * Select key is a mode: while it is on, the arrow keys extend the selection instead of moving the
 * caret. Arrows and Delete repeat while held, at the keyboard's Backspace pace.
 */
class EditPanelView(
    context: Context,
) : QuickContentPanelView(context) {
    /** A key of the pad was pressed; [extend] is true while Select is on. */
    var onEdit: (EditAction, Boolean) -> Unit = { _, _ -> }

    /** Whether the arrow keys extend the selection. */
    var selecting = false
        private set

    private class Key(
        val pad: EditPadKey,
        val action: EditAction?,
        val label: String,
        val spoken: Int,
    )

    private val keys: List<Key> =
        EditPadKey.entries.map { pad ->
            when (pad) {
                EditPadKey.LEFT -> key(pad, EditAction.LEFT, R.string.ime_edit_left, "←")
                EditPadKey.RIGHT -> key(pad, EditAction.RIGHT, R.string.ime_edit_right, "→")
                EditPadKey.UP -> key(pad, EditAction.UP, R.string.ime_edit_up, "↑")
                EditPadKey.DOWN -> key(pad, EditAction.DOWN, R.string.ime_edit_down, "↓")
                EditPadKey.SELECT -> key(pad, null, R.string.ime_edit_select)
                EditPadKey.LINE_START -> key(pad, EditAction.LINE_START, R.string.ime_edit_line_start)
                EditPadKey.LINE_END -> key(pad, EditAction.LINE_END, R.string.ime_edit_line_end)
                EditPadKey.SELECT_ALL -> key(pad, EditAction.SELECT_ALL, R.string.ime_edit_select_all)
                EditPadKey.COPY -> key(pad, EditAction.COPY, R.string.ime_edit_copy)
                EditPadKey.CUT -> key(pad, EditAction.CUT, R.string.ime_edit_cut)
                EditPadKey.PASTE -> key(pad, EditAction.PASTE, R.string.ime_edit_paste)
                EditPadKey.UNDO -> key(pad, EditAction.UNDO, R.string.ime_edit_undo)
                EditPadKey.DELETE -> key(pad, EditAction.DELETE, R.string.ime_edit_delete)
            }
        }

    private fun key(
        pad: EditPadKey,
        action: EditAction?,
        spoken: Int,
        glyph: String? = null,
    ) = Key(pad, action, glyph ?: context.getString(spoken), spoken)

    override val titleText: String get() = context.getString(R.string.ime_panel_edit_title)

    /** A closed panel starts the next time with Select off. */
    fun reset() {
        selecting = false
        invalidate()
    }

    override fun cellCount(): Int = keys.size

    override fun contentColumns(layout: QuickContentPanelLayout): Int = EditPadKey.COLUMNS

    /** The pad always fits the panel, however short it is: the keys share its whole content area. */
    override fun fixedCellBoxes(): List<PanelBox> =
        EditPadGeometry.cells(contentWidthDp(), contentHeightDp(), railWidthDp(), contentTopDp())

    override fun cellRepeatsWhenHeld(index: Int): Boolean = keys.getOrNull(index)?.pad?.repeats == true

    override fun cellRepeatDelayMs(): Long =
        EditPadGeometry.repeatDelayMs(
            ViewConfiguration.getLongPressTimeout().toLong(),
            (KeyboardView.DEFAULT_LONG_PRESS_DELAY_S * 1000).toLong(),
        )

    override fun cellRepeatIntervalMs(): Long = KeyboardView.BACKSPACE_REPEAT_MS

    override fun cellAccessibilityLabel(index: Int): String {
        val key = keys.getOrNull(index) ?: return ""
        return context.getString(if (key.action == null && selecting) R.string.ime_edit_select_on else key.spoken)
    }

    override fun onCellActivated(index: Int) {
        val key = keys.getOrNull(index) ?: return
        val action = key.action
        if (action == null) {
            selecting = !selecting
            invalidate()
            return
        }
        onEdit(action, selecting && action.isMovement)
    }

    override fun drawCell(
        canvas: Canvas,
        cell: PanelBox,
        index: Int,
    ) {
        val key = keys.getOrNull(index) ?: return
        val on = key.action == null && selecting
        val radius = metrics.keyRadiusDp * density
        paint.color =
            if (on) {
                palette.candidateSelectedBackground
            } else if (key.action?.isMovement == true) {
                palette.key
            } else {
                palette.control
            }
        rect.set(
            px(cell.left) + CELL_INSET_DP * density,
            px(cell.top) + CELL_INSET_DP * density,
            px(cell.right) - CELL_INSET_DP * density,
            px(cell.bottom) - CELL_INSET_DP * density,
        )
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.color = if (on) palette.candidateSelectedText else palette.text
        paint.textAlign = Paint.Align.CENTER
        val room = rect.width() - 2 * CELL_INSET_DP * density
        val natural = LABEL_TEXT_SP * textScale
        paint.textSize =
            PanelText.fitSize(natural, PanelText.MIN_FIT_SIZE_SP * textScale, room) { size ->
                paint.textSize = size
                paint.measureText(key.label)
            }
        canvas.drawText(key.label, px(cell.centerX), px(cell.centerY) - (paint.ascent() + paint.descent()) / 2, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    private val EditAction.isMovement: Boolean
        get() =
            this == EditAction.LEFT ||
                this == EditAction.RIGHT ||
                this == EditAction.UP ||
                this == EditAction.DOWN ||
                this == EditAction.LINE_START ||
                this == EditAction.LINE_END
}
