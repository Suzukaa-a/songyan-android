package app.songyan.ime.layout

/**
 * The single geometry source for the phone (T9) shell: one width solved so the digit grid exactly
 * fills the same content width as the bottom control row, and one set of row/column boundaries
 * shared by both rails and the 3x3 digit grid.
 *
 * Width rule (deterministic, symmetric, no hidden side slack):
 *
 *     contentWidth = leadingRail + gap + [d0 gap d1 gap d2] + gap + trailingRail
 *     digit        = min(preferredDigitWidth, (width - 4*gap - 2*minTouch) / 3)
 *     rail         = min((width - 3*digit - 4*gap) / 2, digit)
 *
 * Digits grow to their preferred width first; every remaining point is split equally between the
 * two rails, so the grid and the bottom control row share exactly one content edge.
 */
class PhoneKeypadMetrics private constructor(
    val width: Double,
    val spacing: Double,
    val railWidth: Double,
    val digitWidth: Double,
    val rowHeight: Double,
    val contentWidth: Double,
) {
    /** A pure layout rectangle. Visual insets are separate; see [Slot.visualRect]. */
    data class Slot(
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double,
    ) {
        val minX: Double get() = x
        val maxX: Double get() = x + width
        val midX: Double get() = x + width / 2
        val minY: Double get() = y
        val maxY: Double get() = y + height
        val midY: Double get() = y + height / 2

        /** The drawn keycap surface; the layout slot (and its hit region) is unchanged. */
        fun visualRect(
            insetX: Double,
            insetY: Double,
        ): Slot =
            Slot(
                x = x + insetX,
                y = y + insetY,
                width = maxOf(0.0, width - insetX * 2),
                height = maxOf(0.0, height - insetY * 2),
            )
    }

    sealed class Column {
        data object LeadingRail : Column()

        data class Digit(
            val index: Int,
        ) : Column()

        data object TrailingRail : Column()
    }

    enum class Rail { LEADING, TRAILING }

    /** The three-column pad including its own two inner gaps. */
    val mainWidth: Double get() = digitWidth * 3 + spacing * 2

    /** Rail + gap + pad + gap + rail; identical to [contentWidth] by construction. */
    val computedContentWidth: Double get() = railWidth * 2 + mainWidth + spacing * 2

    /** Symmetric margin around the whole shell (grid and control row together). */
    val outerInset: Double get() = maxOf(0.0, (width - contentWidth) / 2)

    /** The shell vertical centre; the two rails mirror about it. */
    val centerlineX: Double get() = contentWidth / 2

    /** Height of a key spanning [rows] stacked digit rows. */
    fun spanHeight(rows: Int): Double {
        val count = maxOf(1, rows)
        return count * rowHeight + (count - 1) * spacing
    }

    /** The three digit rows plus their two inner gaps; the control row sits outside this. */
    val mainHeight: Double get() = spanHeight(MAIN_ROW_COUNT)

    /** Top of the bottom control row, in shell coordinates. */
    val controlRowY: Double get() = mainHeight + spacing

    /** Grid + gap + control row. */
    val totalHeight: Double get() = mainHeight + spacing + rowHeight

    fun columnRect(column: Column): Slot {
        val height = mainHeight
        return when (column) {
            Column.LeadingRail -> Slot(0.0, 0.0, railWidth, height)
            Column.TrailingRail -> Slot(contentWidth - railWidth, 0.0, railWidth, height)
            is Column.Digit -> {
                if (column.index !in 0 until 3) {
                    Slot(0.0, 0.0, 0.0, height)
                } else {
                    val x = railWidth + spacing + column.index * (digitWidth + spacing)
                    Slot(x, 0.0, digitWidth, height)
                }
            }
        }
    }

    /** Left/right symmetry of the two rails about [centerlineX]. */
    fun railsAreSymmetric(): Boolean {
        val left = columnRect(Column.LeadingRail)
        val right = columnRect(Column.TrailingRail)
        return kotlin.math.abs(left.width - right.width) < EPSILON &&
            kotlin.math.abs(left.minX - (contentWidth - right.maxX)) < EPSILON &&
            kotlin.math.abs(left.midX + right.midX - contentWidth) < EPSILON
    }

    /** Top of digit row [index] (0...2), in shell coordinates. */
    fun rowY(index: Int): Double = index * (rowHeight + spacing)

    /** The full-width band of digit row [index]: rails and digits share this baseline. */
    fun mainRowRect(index: Int): Slot = Slot(0.0, rowY(index), contentWidth, rowHeight)

    fun digitCellRect(
        row: Int,
        column: Int,
    ): Slot {
        val cell = columnRect(Column.Digit(column))
        return Slot(cell.x, rowY(row), digitWidth, rowHeight)
    }

    /** The bottom control row, spanning exactly the grid content width. */
    fun controlRowRect(): Slot = Slot(0.0, controlRowY, contentWidth, rowHeight)

    /** The control row when the trailing column runs down beside it (bundled T9). */
    fun controlRowRectBesideTrailingRail(): Slot =
        Slot(0.0, controlRowY, maxOf(0.0, contentWidth - railWidth - spacing), rowHeight)

    /** Deterministic rail slots laid out top-to-bottom, one entry per requested row span. */
    fun railSlots(
        rail: Rail,
        spans: List<Int>,
    ): List<Slot> {
        val x = if (rail == Rail.LEADING) 0.0 else contentWidth - railWidth
        var y = 0.0
        val slots = mutableListOf<Slot>()
        for (span in spans) {
            val height = spanHeight(span)
            slots.add(Slot(x, y, railWidth, height))
            y += height + spacing
        }
        return slots
    }

    /** Convenience for a rail whose keys each occupy one row. */
    fun railSlots(
        rail: Rail,
        count: Int,
    ): List<Slot> = railSlots(rail, List(maxOf(0, count)) { 1 })

    /** Rail slots resolved directly from a key count via [slotSpans]. */
    fun railSlotsForKeyCount(
        rail: Rail,
        keyCount: Int,
    ): List<Slot> = railSlots(rail, slotSpans(keyCount))

    companion object {
        /** The one gap shared by every horizontal and vertical neighbour. */
        const val SPACING: Double = 4.0

        /** The minimum comfortable touch target. */
        const val MINIMUM_TOUCH_TARGET: Double = 44.0

        /** Preferred number-key width. Wider shells grow the rails instead. */
        const val PREFERRED_DIGIT_WIDTH: Double = 84.0

        /** The main grid is always three digit rows; the control row sits outside it. */
        const val MAIN_ROW_COUNT: Int = 3

        /** Shell width beyond which the whole shell is centred instead of stretched. */
        const val MAXIMUM_SHELL_WIDTH: Double = PREFERRED_DIGIT_WIDTH * 5 + SPACING * 4

        private const val EPSILON = 1e-9

        /**
         * Deterministic row spans for a rail with [keyCount] keys. Fewer keys than rows puts the
         * surplus on the first slot, so 2 keys over 3 rows becomes [2, 1].
         */
        fun slotSpans(
            keyCount: Int,
            rowCount: Int = MAIN_ROW_COUNT,
        ): List<Int> {
            val count = maxOf(0, keyCount)
            val rows = maxOf(1, rowCount)
            if (count == 0) return emptyList()
            if (count >= rows) return List(count) { 1 }
            return listOf(rows - (count - 1)) + List(count - 1) { 1 }
        }

        fun resolve(
            width: Double,
            rowHeight: Double = 54.0,
        ): PhoneKeypadMetrics {
            val safeWidth = if (width.isFinite()) maxOf(0.0, width) else 0.0
            val safeRowHeight = if (rowHeight.isFinite()) maxOf(0.0, rowHeight) else 0.0
            if (safeWidth <= 0.0) {
                return PhoneKeypadMetrics(0.0, 0.0, 0.0, 0.0, safeRowHeight, 0.0)
            }
            val shellWidth = minOf(safeWidth, MAXIMUM_SHELL_WIDTH)
            val minimumContent = MINIMUM_TOUCH_TARGET * 5 + SPACING * 4
            var spacing = SPACING
            var rail: Double
            var digit: Double
            if (shellWidth < minimumContent) {
                // Too narrow for the real grid (previews): scale the whole unit down together.
                val scale = shellWidth / minimumContent
                spacing *= scale
                rail = MINIMUM_TOUCH_TARGET * scale
                digit = MINIMUM_TOUCH_TARGET * scale
            } else {
                val digitAvailable = (shellWidth - spacing * 4 - MINIMUM_TOUCH_TARGET * 2) / 3
                digit = minOf(PREFERRED_DIGIT_WIDTH, digitAvailable)
                rail = minOf((shellWidth - digit * 3 - spacing * 4) / 2, digit)
                if (rail < MINIMUM_TOUCH_TARGET) {
                    rail = MINIMUM_TOUCH_TARGET
                    digit = maxOf(0.0, (shellWidth - spacing * 4 - rail * 2) / 3)
                }
            }
            rail = maxOf(0.0, rail)
            digit = maxOf(0.0, digit)
            val content = rail * 2 + digit * 3 + spacing * 4
            return PhoneKeypadMetrics(safeWidth, spacing, rail, digit, safeRowHeight, content)
        }
    }
}
