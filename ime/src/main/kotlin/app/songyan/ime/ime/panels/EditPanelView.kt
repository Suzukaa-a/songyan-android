package app.songyan.ime.ime.panels

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.ViewConfiguration
import androidx.annotation.DrawableRes
import androidx.core.graphics.ColorUtils
import app.songyan.ime.content.QuickContentPanelLayout
import app.songyan.ime.ime.EditAction
import app.songyan.ime.ime.KeyboardView
import app.songyan.ime.ime.PanelIcon
import app.songyan.ime.ime.R

private const val CELL_INSET_DP = 3f
private const val LABEL_TEXT_SP = 16f
private const val STACKED_TEXT_SP = 11f
private const val ARROW_ICON_DP = 24f
private const val KEY_ICON_DP = 20f
private const val ICON_GAP_DP = 2f

/** Opacity of a dimmed key's content, the Material 3 disabled-content value. */
private const val DISABLED_ALPHA = 97

/**
 * The edit panel uses tall left/right targets, a central selection pad, and editing actions on the right.
 * The bottom row holds line start, Select All (or Cut when selected), line end and Delete.
 *
 * It only reports what was pressed ([onEdit]); the editor work happens behind that callback. The
 * Select key is a mode ([EditPadState]): while it is on, the keys that move the caret extend the
 * selection instead. Copy and Cut dim while nothing is selected and Paste while the clipboard is empty;
 * the owner pushes both through [setAvailability]. Arrows and Delete repeat while held, at the keyboard's
 * Backspace pace.
 */
class EditPanelView(
    context: Context,
) : QuickContentPanelView(context) {
    /** A key of the pad was pressed; the flag is true when it extends the selection (Select is on). */
    var onEdit: (EditAction, Boolean) -> Unit = { _, _ -> }

    private val state = EditPadState()

    /** Whether the arrow keys extend the selection. */
    val selecting: Boolean get() = state.selecting

    private val keys: List<EditPadKey> = EditPadKey.layoutKeys

    private fun keyAt(index: Int): EditPadKey? = keys.getOrNull(index)?.let(state::displayedKey)

    // Inflated on first draw: the panel is built with the keyboard, and the icons are only needed once it opens.
    private val icons: List<Lazy<PanelIcon>?> =
        EditPadKey.entries.map { key ->
            iconRes(key)?.let { res -> lazy(LazyThreadSafetyMode.NONE) { PanelIcon(context, res) } }
        }

    override val titleText: String get() = context.getString(R.string.ime_panel_edit_title)

    /** A closed panel starts the next time with Select off. */
    fun reset() {
        state.reset()
        invalidate()
    }

    /** What the editor and the clipboard allow now; dims Copy, Cut and Paste accordingly. */
    fun setAvailability(value: EditAvailability) {
        if (state.availability == value) return
        state.availability = value
        invalidate()
    }

    /** The selection changed under the panel; the clipboard answer stays as it was. */
    fun setSelection(hasSelection: Boolean) = setAvailability(state.availability.copy(hasSelection = hasSelection))

    override fun cellCount(): Int = keys.size

    override fun contentColumns(layout: QuickContentPanelLayout): Int = EditPadKey.COLUMNS

    /** The pad always fits the panel, however short it is: the keys share its whole content area. */
    override fun fixedCellBoxes(): List<PanelBox> =
        EditPadGeometry.cells(contentWidthDp(), contentHeightDp(), railWidthDp(), contentTopDp())

    override fun cellRepeatsWhenHeld(index: Int): Boolean = keyAt(index)?.repeats == true

    override fun cellEnabled(index: Int): Boolean = keyAt(index)?.let(state::isEnabled) ?: true

    override fun cellRepeatDelayMs(): Long =
        EditPadGeometry.repeatDelayMs(
            ViewConfiguration.getLongPressTimeout().toLong(),
            (KeyboardView.DEFAULT_LONG_PRESS_DELAY_S * 1000).toLong(),
        )

    override fun cellRepeatIntervalMs(): Long = KeyboardView.BACKSPACE_REPEAT_MS

    override fun cellAccessibilityLabel(index: Int): String {
        val key = keyAt(index) ?: return ""
        return context.getString(
            if (key == EditPadKey.SELECT &&
                state.selecting
            ) {
                R.string.ime_edit_select_on
            } else {
                spoken(key)
            },
        )
    }

    override fun onCellActivated(index: Int) {
        val key = keyAt(index) ?: return
        val action = key.action
        if (action == null) {
            state.toggleSelect()
            invalidate()
            return
        }
        val hadSelection = state.availability.hasSelection
        onEdit(action, state.extends(key))
        state.afterKey(key, hadSelection)
        invalidate()
    }

    override fun drawCell(
        canvas: Canvas,
        cell: PanelBox,
        index: Int,
    ) {
        val key = keyAt(index) ?: return
        val on = key == EditPadKey.SELECT && state.selecting
        val enabled = state.isEnabled(key)
        val radius = metrics.keyRadiusDp * density
        paint.color =
            if (on) {
                palette.candidateSelectedBackground
            } else if (key.movesCaret) {
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
        val ink =
            when {
                on -> palette.candidateSelectedText
                enabled -> palette.text
                else -> ColorUtils.setAlphaComponent(palette.text, DISABLED_ALPHA)
            }
        drawContent(canvas, key, cell, ink)
    }

    /** The glyph of an arrow, a name for Select, and an icon over a name for every other key that has the room. */
    private fun drawContent(
        canvas: Canvas,
        key: EditPadKey,
        cell: PanelBox,
        ink: Int,
    ) {
        val icon = icons[key.ordinal]?.value
        val centerX = px(cell.centerX)
        val centerY = px(cell.centerY)
        if (key.isArrow && icon != null) {
            icon.draw(canvas, centerX, centerY, minOf(ARROW_ICON_DP * density, rect.height() * 0.6f), ink)
            return
        }
        val label =
            context.getString(
                when {
                    key == EditPadKey.SELECT && state.selecting -> R.string.ime_edit_selecting
                    else -> spoken(key)
                },
            )
        val stacked = icon != null && EditPadGeometry.stacksLabel(rect.height() / density.toDouble())
        paint.color = ink
        paint.textAlign = Paint.Align.CENTER
        val room = rect.width() - 2 * CELL_INSET_DP * density
        val natural = (if (stacked) STACKED_TEXT_SP else LABEL_TEXT_SP) * textScale
        paint.textSize =
            PanelText.fitSize(natural, PanelText.MIN_FIT_SIZE_SP * textScale, room) { size ->
                paint.textSize = size
                paint.measureText(label)
            }
        val textHeight = paint.descent() - paint.ascent()
        if (stacked && icon != null) {
            val iconSize = minOf(KEY_ICON_DP * density, rect.height() - textHeight - ICON_GAP_DP * density)
            val block = iconSize + ICON_GAP_DP * density + textHeight
            val top = centerY - block / 2
            icon.draw(canvas, centerX, top + iconSize / 2, iconSize, ink)
            canvas.drawText(label, centerX, top + iconSize + ICON_GAP_DP * density - paint.ascent(), paint)
        } else {
            canvas.drawText(label, centerX, centerY - (paint.ascent() + paint.descent()) / 2, paint)
        }
        paint.textAlign = Paint.Align.LEFT
    }

    private fun spoken(key: EditPadKey): Int =
        when (key) {
            EditPadKey.LEFT -> R.string.ime_edit_left
            EditPadKey.RIGHT -> R.string.ime_edit_right
            EditPadKey.UP -> R.string.ime_edit_up
            EditPadKey.DOWN -> R.string.ime_edit_down
            EditPadKey.SELECT -> R.string.ime_edit_select
            EditPadKey.LINE_START -> R.string.ime_edit_line_start
            EditPadKey.LINE_END -> R.string.ime_edit_line_end
            EditPadKey.SELECT_ALL -> R.string.ime_edit_select_all
            EditPadKey.COPY -> R.string.ime_edit_copy
            EditPadKey.CUT -> R.string.ime_edit_cut
            EditPadKey.PASTE -> R.string.ime_edit_paste
            EditPadKey.UNDO -> R.string.ime_edit_undo
            EditPadKey.DELETE -> R.string.ime_edit_delete
        }

    @DrawableRes
    private fun iconRes(key: EditPadKey): Int? =
        when (key) {
            EditPadKey.LEFT -> R.drawable.ic_edit_left
            EditPadKey.RIGHT -> R.drawable.ic_edit_right
            EditPadKey.UP -> R.drawable.ic_edit_up
            EditPadKey.DOWN -> R.drawable.ic_edit_down
            EditPadKey.SELECT -> null
            EditPadKey.LINE_START -> R.drawable.ic_edit_line_start
            EditPadKey.LINE_END -> R.drawable.ic_edit_line_end
            EditPadKey.SELECT_ALL -> R.drawable.ic_edit_select_all
            EditPadKey.COPY -> R.drawable.ic_edit_copy
            EditPadKey.CUT -> R.drawable.ic_edit_cut
            EditPadKey.PASTE -> R.drawable.ic_edit_paste
            EditPadKey.UNDO -> R.drawable.ic_edit_undo
            EditPadKey.DELETE -> R.drawable.ic_edit_delete
        }
}
