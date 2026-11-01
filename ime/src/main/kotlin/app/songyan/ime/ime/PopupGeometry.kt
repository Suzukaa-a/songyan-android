package app.songyan.ime.ime

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Where the long-press popup and the key preview sit, in the coordinates of the layer they are drawn
 * in. The layer covers the whole keyboard (candidate bar included), so a first-row popup is not
 * clipped by the key area; it only stops at the layer's top edge.
 */
internal object PopupGeometry {
    /** The popup frame and whether its cells run right-to-left (first alternate at the key). */
    data class Alternates(
        val bounds: KeyBounds,
        /** Cells are laid out leftwards from the key: the first alternate is the rightmost cell. */
        val mirrored: Boolean,
    ) {
        /** Option index shown in cell [cell] of [count]. */
        fun optionAt(
            cell: Int,
            count: Int,
        ): Int = if (mirrored) count - 1 - cell else cell

        /** Cell holding option [option] of [count]. */
        fun cellOf(
            option: Int,
            count: Int,
        ): Int = if (mirrored) count - 1 - option else option
    }

    /** Space kept between a key and the popup above it, so a thumb on the key does not cover it. */
    const val GAP_DP = 6f

    /** The shortest a popup may get when the key sits so high that the full height does not fit. */
    private const val MINIMUM_HEIGHT_DP = 36f

    /**
     * The alternates popup above [anchor]. The first alternate sits right above the key; the cells
     * run to the right, or to the left when they would not fit before the layer's right edge.
     */
    fun alternates(
        anchor: KeyBounds,
        count: Int,
        cellWidth: Float,
        cellHeight: Float,
        layerWidth: Float,
        layerTop: Float,
        gap: Float,
        minimumHeight: Float = MINIMUM_HEIGHT_DP,
    ): Alternates {
        val width = min(layerWidth, count * cellWidth)
        val keyCenter = (anchor.left + anchor.right) / 2
        val firstLeft = keyCenter - min(cellWidth, width) / 2
        val mirrored = count > 1 && firstLeft + width > layerWidth
        val rawLeft = if (mirrored) keyCenter + min(cellWidth, width) / 2 - width else firstLeft
        val left = rawLeft.coerceIn(0f, max(0f, layerWidth - width))
        val bottom = anchor.top - gap
        val available = bottom - layerTop
        val height = if (available >= cellHeight) cellHeight else max(available, minimumHeight)
        val top = max(layerTop, bottom - height)
        return Alternates(KeyBounds(left, top, left + width, top + height), mirrored)
    }

    /** The enlarged key preview above [anchor]: as wide as the key plus a little, centred on it. */
    fun preview(
        anchor: KeyBounds,
        width: Float,
        height: Float,
        layerWidth: Float,
        layerTop: Float,
        gap: Float,
    ): KeyBounds {
        val center = (anchor.left + anchor.right) / 2
        val left = (center - width / 2).coerceIn(0f, max(0f, layerWidth - width))
        val bottom = anchor.top - gap
        val top = max(layerTop, bottom - height)
        return KeyBounds(left, top, left + width, top + height)
    }
}

/**
 * Which alternate a finger dragging across an open popup selects.
 *
 * The popup sits above the key and the thumb covers it, so the pick follows how far the finger has
 * travelled sideways since the press began, one cell per cell width, rather than which cell it is
 * over: a thumb sliding along the key row, below the popup, selects just as well as one inside it.
 * A small move keeps the current option, a boundary needs a margin to cross so jitter cannot
 * flicker, and only a clear slide past either end or a clear pull down, away from the popup,
 * selects nothing so the release cancels.
 */
internal object PopupSelection {
    /** Sideways travel below this keeps the current option. */
    const val DEAD_ZONE_DP = 12f

    /** How far past a cell boundary the finger must go before the selection follows it. */
    const val HYSTERESIS_DP = 8f

    /** Sliding this far past either end of the popup cancels. */
    const val CANCEL_MARGIN_DP = 28f

    /** Pulling down this far below where the press began cancels; edge keys cannot slide out sideways. */
    const val CANCEL_DROP_DP = 72f

    /** Index of the alternate selected, or -1 when the finger has cancelled the pick. */
    fun index(
        popup: KeyBounds,
        count: Int,
        originX: Float,
        originY: Float,
        x: Float,
        y: Float,
        current: Int,
        density: Float,
        mirrored: Boolean = false,
    ): Int {
        if (count <= 0) return -1
        val margin = CANCEL_MARGIN_DP * density
        if (x < popup.left - margin || x > popup.right + margin) return -1
        if (y - originY >= CANCEL_DROP_DP * density) return -1
        val dx = x - originX
        val held = if (current in 0 until count) current else 0
        if (abs(dx) < DEAD_ZONE_DP * density) return held
        val cell = (popup.right - popup.left) / count
        if (cell <= 0f) return held

        fun cellOf(option: Int) = if (mirrored) count - 1 - option else option
        // The press began on the first alternate, so travel counts from the centre of its cell.
        val continuous = cellOf(0) + 0.5f + dx / cell
        var column = floor(continuous).toInt().coerceIn(0, count - 1)
        if (current in 0 until count) {
            val kept = cellOf(current)
            val sticky = HYSTERESIS_DP * density / cell
            if (column != kept && continuous > kept - sticky && continuous < kept + 1 + sticky) column = kept
        }
        return if (mirrored) count - 1 - column else column
    }
}
