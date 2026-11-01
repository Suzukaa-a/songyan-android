package app.songyan.ime.ime

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
 * Where the finger of an open alternates popup selects, and whether it selects anything at all.
 *
 * The corridor is the popup plus the pressed key: resting on the key keeps the first alternate,
 * moving into the popup picks by column, and anything beyond the corridor selects nothing so the
 * release cancels instead of committing a highlighted option.
 */
internal object PopupSelection {
    /** Index of the alternate the finger selects, or -1 when it is outside the corridor. */
    fun index(
        popup: KeyBounds,
        anchor: KeyBounds,
        count: Int,
        x: Float,
        y: Float,
        slop: Float,
        mirrored: Boolean = false,
    ): Int {
        if (count <= 0) return -1
        if (inside(popup, x, y, slop)) {
            val cell = (popup.right - popup.left) / count
            if (cell <= 0f) return 0
            val column = ((x - popup.left) / cell).toInt().coerceIn(0, count - 1)
            return if (mirrored) count - 1 - column else column
        }
        if (inside(anchor, x, y, slop)) return 0
        return -1
    }

    private fun inside(
        bounds: KeyBounds,
        x: Float,
        y: Float,
        slop: Float,
    ): Boolean =
        x >= bounds.left - slop &&
            x <= bounds.right + slop &&
            y >= bounds.top - slop &&
            y <= bounds.bottom + slop
}
