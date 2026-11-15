package app.songyan.ime.ime

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The nine-key reading column as a scrolling list, as iOS draws it in a scroll view: the three rail
 * slots only mark the column, and every reading sits one slot pitch below the last, moved by a pixel
 * offset that follows the finger and keeps going after a flick. A reading partly scrolled out is
 * clipped at the column's edge, never snapped to a slot.
 */
internal object RailColumn {
    /** The column the rail slots span, or null when the shell draws no rail. */
    fun bounds(
        railIndices: Set<Int>,
        bounds: List<KeyBounds>,
        out: RectF,
    ): RectF? {
        if (railIndices.isEmpty()) return null
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (i in railIndices) {
            val box = bounds.getOrNull(i) ?: continue
            left = min(left, box.left)
            top = min(top, box.top)
            right = max(right, box.right)
            bottom = max(bottom, box.bottom)
        }
        if (left >= right || top >= bottom) return null
        out.set(left, top, right, bottom)
        return out
    }

    /** One reading's height: a column [height] tall shows [T9RailPolicy.VISIBLE_SLOTS] of them. */
    fun pitch(height: Float): Float = height / T9RailPolicy.VISIBLE_SLOTS

    /** The furthest the list scrolls: its last reading at the column's bottom edge. */
    fun maxOffset(
        count: Int,
        height: Float,
    ): Float = max(0f, count * pitch(height) - height)

    /** The reading under [y] in a column from [top], [height] tall, or -1 past the last one. */
    fun itemAt(
        y: Float,
        offset: Float,
        count: Int,
        top: Float,
        height: Float,
    ): Int {
        val index = floor((y - top + offset) / pitch(height)).toInt()
        return if (index in 0 until count) index else -1
    }

    /** The reading drawn at slot [slot]'s centre, for the slot's accessibility label. */
    fun itemAtSlot(
        slot: Int,
        offset: Float,
        count: Int,
        height: Float,
    ): Int = itemAt((slot + 0.5f) * pitch(height), offset, count, 0f, height)
}

private val railColumnRect = RectF()

/** The rail column in view coordinates, or null without a rail. */
internal fun KeyboardView.railColumn(): RectF? = RailColumn.bounds(railIndices, bounds, railColumnRect)

/** Sets the reading column's scroll, clamped to the list; true when it moved. */
internal fun KeyboardView.setRailOffset(value: Float): Boolean {
    val column = railColumn() ?: return false
    val next = value.coerceIn(0f, RailColumn.maxOffset(railChoices.size, column.height()))
    if (next == railOffset) return false
    railOffset = next
    invalidate()
    return true
}

/** Throws the reading list after a drag; [velocityY] is the finger's speed, positive moving down. */
internal fun KeyboardView.flingRail(velocityY: Float) {
    val column = railColumn() ?: return
    val max = RailColumn.maxOffset(railChoices.size, column.height()).toInt()
    if (max <= 0) return
    railScroller.fling(0, railOffset.toInt(), 0, (-velocityY).toInt(), 0, 0, 0, max)
    postInvalidateOnAnimation()
}

/** Stops a fling in progress; true when one was running, so the touch that stopped it selects nothing. */
internal fun KeyboardView.stopRailFling(): Boolean {
    if (railScroller.isFinished) return false
    railScroller.forceFinished(true)
    relabelRail()
    return true
}

/** Advances a fling one frame; the slot labels follow once the list rests. */
internal fun KeyboardView.stepRailFling() {
    if (!railScroller.computeScrollOffset()) return
    railOffset = railScroller.currY.toFloat()
    if (railScroller.isFinished) {
        relabelRail()
    } else {
        postInvalidateOnAnimation()
    }
    invalidate()
}

/**
 * Draws the readings over the rail column: the chosen ones and the one under a finger get a cap of
 * their own; the rest sit on the column surface. Labels were fitted in [fitRailLabels].
 */
internal fun KeyboardView.drawRailItems(
    canvas: Canvas,
    column: RectF,
    pressedItem: Int,
    paint: Paint,
    capBounds: RectF,
) {
    val count = railChoices.size
    if (count == 0) return
    val pitch = RailColumn.pitch(column.height())
    val first = max(0, floor(railOffset / pitch).toInt())
    val last = min(count - 1, floor((railOffset + column.height()) / pitch).toInt())
    val radius = keyRadiusPx()
    canvas.save()
    canvas.clipRect(column.left, column.top + insetY, column.right, column.bottom - insetY)
    for (i in first..last) {
        val top = column.top + i * pitch - railOffset
        capBounds.set(column.left + insetX, top + insetY, column.right - insetX, top + pitch - insetY)
        val chosen = i < railSelectedCount
        val pressed = i == pressedItem
        if (chosen || pressed) {
            paint.style = Paint.Style.FILL
            paint.color = if (pressed) palette.pressed else palette.accent
            canvas.drawRoundRect(capBounds, radius, radius, paint)
        }
        val size = railLabelSizes.getOrNull(i) ?: continue
        paint.textSize = size
        paint.textAlign = Paint.Align.CENTER
        paint.color = if (chosen && !pressed) palette.accentText else palette.text
        val metrics = paint.fontMetrics
        val baseline = capBounds.centerY() - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(railChoices[i], capBounds.centerX(), baseline, paint)
    }
    canvas.restore()
}

/** Fits every reading once per list, at candidate size, so the draw loop measures nothing. */
internal fun KeyboardView.fitRailLabels() {
    val column = railColumn()
    if (column == null || railChoices.isEmpty()) {
        railLabelSizes = FloatArray(0)
        return
    }
    val capWidth = column.width() - 2 * insetX - 6 * density
    railLabelSizes = FloatArray(railChoices.size) { fitSize(railChoices[it], metrics.candidateTextSizeSp, capWidth) }
}

/** Scrolls the list one column of readings for assistive technology; true when it moved. */
internal fun KeyboardView.pageRail(forward: Boolean): Boolean {
    val column = railColumn() ?: return false
    val moved = setRailOffset(railOffset + if (forward) column.height() else -column.height())
    if (moved) relabelRail()
    return moved
}

/** Whether the list can scroll further down ([forward]) or back up. */
internal fun KeyboardView.railCanScroll(forward: Boolean): Boolean {
    val column = railColumn() ?: return false
    return if (forward) railOffset < RailColumn.maxOffset(railChoices.size, column.height()) else railOffset > 0f
}
