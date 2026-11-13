package app.songyan.ime.ime

import app.songyan.ime.layout.PhoneKeypadMetrics
import kotlin.math.max

/**
 * A rectangle in dp, in the twelve-key surface's own coordinates.
 */
data class Kana12Rect(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
    val centerX: Double get() = (left + right) / 2
    val centerY: Double get() = (top + bottom) / 2

    /** Half-open containment, so two touching keys never both own a point. */
    fun contains(
        x: Double,
        y: Double,
    ): Boolean = x >= left && x < right && y >= top && y < bottom

    /** Pulls every side in by the same amount, never past the centre. */
    fun inset(
        horizontal: Double,
        vertical: Double,
    ): Kana12Rect {
        val insetX = horizontal.coerceIn(0.0, max(0.0, width) / 2)
        val insetY = vertical.coerceIn(0.0, max(0.0, height) / 2)
        return Kana12Rect(left + insetX, top + insetY, right - insetX, bottom - insetY)
    }

    fun translate(
        dx: Double,
        dy: Double,
    ): Kana12Rect = Kana12Rect(left + dx, top + dy, right + dx, bottom + dy)
}

/** Where a placed key sits: the center grid or one of the two rails. */
enum class Kana12Region {
    LeftRail,
    Grid,
    RightRail,
}

/**
 * One placed key rectangle.
 *
 * The whole rectangle is the key's touch target; the drawn cap is [Kana12Geometry.capBounds] of
 * it, so a tap in the gutter between two keys still has exactly one owner.
 */
data class Kana12Slot(
    val region: Kana12Region,
    val bounds: Kana12Rect,
    /** How many of the four equal bands this key spans. */
    val bands: Int,
)

/**
 * The twelve-key surface as pure geometry: five columns and four equal bands.
 *
 * The columns reuse [PhoneKeypadMetrics], the same solve the nine-key shell is laid out with, so
 * the kana keys keep that shell's key width and the rails never grow wider than a kana key. The
 * height is four equal bands: the center grid is the four rows of three kana keys, and each rail
 * stacks its keys over their shares of those bands.
 *
 * Which keys a rail draws, and in what order, is the selected layout's own data; this class only
 * places slots. The default split is the reference keyboard's: the leading rail gives each of its
 * four keys one band (cursor, symbols, script and the system switch), the trailing rail gives
 * Delete and Space one band each and lets Return span the lower two.
 */
class Kana12Geometry private constructor(
    val width: Double,
    val height: Double,
    val content: Kana12Rect,
    val capInsetHorizontal: Double,
    val capInsetVertical: Double,
    val slots: List<Kana12Slot>,
) {
    /** The key under ([x], [y]) dp, or null in the margins beside a centred shell or in a gutter. */
    fun slotAt(
        x: Double,
        y: Double,
    ): Kana12Slot? = slots.firstOrNull { it.bounds.contains(x, y) }

    /** The drawn cap of [slot]: its touch target pulled in by the visual gutter. */
    fun capBounds(slot: Kana12Slot): Kana12Rect = slot.bounds.inset(capInsetHorizontal, capInsetVertical)

    companion object {
        /** The four key rows: the grid's rows and the two-band Return share them. */
        const val BAND_COUNT = 4

        /** The centre block's columns; the fourth row still starts with the modify key. */
        const val GRID_COLUMNS = 3

        /** The default horizontal gutter between two caps (dp), matching KeyboardDesignTokens. */
        const val DEFAULT_KEY_GAP_HORIZONTAL_DP: Double = 5.5

        /** The default vertical gutter between two cap rows (dp), matching KeyboardDesignTokens. */
        const val DEFAULT_KEY_GAP_VERTICAL_DP: Double = 8.0

        /** The reference keyboard's leading rail: four keys, one band each. */
        val DEFAULT_LEFT_BANDS: List<Int> = List(BAND_COUNT) { 1 }

        /** Delete and Space take one band each; Return takes the lower two. */
        val DEFAULT_RIGHT_BANDS: List<Int> = listOf(1, 1, 2)

        /**
         * Places the surface on a [widthDp] by [heightDp] area.
         *
         * [keyGapHorizontalDp] and [keyGapVerticalDp] are the visual gutters the caller draws with,
         * so a cap retreats half of each from its slot. [leftRailBands] and [rightRailBands] say how
         * many bands each rail key spans and how many keys the rail has: a rail that hides its
         * system switch key passes three spans instead of the default four. A list with a
         * non-positive span falls back to one band per key rather than throwing in the middle of a
         * keystroke.
         */
        fun resolve(
            widthDp: Double,
            heightDp: Double,
            keyGapHorizontalDp: Double = DEFAULT_KEY_GAP_HORIZONTAL_DP,
            keyGapVerticalDp: Double = DEFAULT_KEY_GAP_VERTICAL_DP,
            leftRailBands: List<Int> = DEFAULT_LEFT_BANDS,
            rightRailBands: List<Int> = DEFAULT_RIGHT_BANDS,
        ): Kana12Geometry {
            val width = safeSize(widthDp)
            val height = safeSize(heightDp)
            val metrics = PhoneKeypadMetrics.resolve(width)
            val contentLeft = metrics.outerInset
            val content = Kana12Rect(contentLeft, 0.0, contentLeft + metrics.contentWidth, height)
            val bandHeight = height / BAND_COUNT
            val slots =
                ArrayList<Kana12Slot>(
                    leftRailBands.size + GRID_COLUMNS * BAND_COUNT + rightRailBands.size,
                )
            slots += railSlots(Kana12Region.LeftRail, contentLeft, metrics.railWidth, height, leftRailBands)
            for (row in 0 until BAND_COUNT) {
                for (column in 0 until GRID_COLUMNS) {
                    val cell = metrics.digitCellRect(0, column)
                    slots +=
                        Kana12Slot(
                            region = Kana12Region.Grid,
                            bounds =
                                Kana12Rect(
                                    contentLeft + cell.minX,
                                    row * bandHeight,
                                    contentLeft + cell.maxX,
                                    (row + 1) * bandHeight,
                                ),
                            bands = 1,
                        )
                }
            }
            slots +=
                railSlots(
                    Kana12Region.RightRail,
                    contentLeft + metrics.contentWidth - metrics.railWidth,
                    metrics.railWidth,
                    height,
                    rightRailBands,
                )
            return Kana12Geometry(
                width = width,
                height = height,
                content = content,
                // The rail column and the grid keep the shell's own spacing between them, so the
                // drawn gutter is what is left of the design token after that spacing.
                capInsetHorizontal = max(0.0, safeSize(keyGapHorizontalDp) - metrics.spacing) / 2,
                capInsetVertical = safeSize(keyGapVerticalDp) / 2,
                slots = slots,
            )
        }

        /**
         * Stacks one rail's keys down the column, each as tall as its share of the bands. The
         * spans also decide how many keys the rail has, because a rail's keys are its slots.
         */
        private fun railSlots(
            region: Kana12Region,
            left: Double,
            width: Double,
            height: Double,
            bands: List<Int>,
        ): List<Kana12Slot> {
            val spans = bands.takeIf { spans -> spans.isNotEmpty() && spans.all { it > 0 } } ?: List(bands.size) { 1 }
            val total = spans.sum()
            var top = 0.0
            return spans.map { span ->
                val slotHeight = if (total > 0) height * span / total else 0.0
                val slot = Kana12Slot(region, Kana12Rect(left, top, left + width, top + slotHeight), span)
                top += slotHeight
                slot
            }
        }

        private fun safeSize(value: Double): Double = if (value.isFinite()) max(0.0, value) else 0.0
    }
}
