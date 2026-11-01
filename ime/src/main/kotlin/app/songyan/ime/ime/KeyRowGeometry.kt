package app.songyan.ime.ime

import app.songyan.ime.layout.KeyboardPageRailGeometry
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutRow
import app.songyan.ime.layout.LayoutSplitConfiguration
import kotlin.math.max
import kotlin.math.min

/**
 * Slot geometry of the row pages: letters, 123, symbols and their footer.
 *
 * A slot is a key's weight plus the gutter after it; the drawn keycap is that slot inset by half
 * the horizontal and vertical gutter. The solve is pure Kotlin so drawing, hit testing and unit
 * tests share one source of truth instead of three copies that can drift.
 */
internal object KeyRowSolver {
    /** Columns of a standard character row; the shared unit is solved against them. */
    private const val COLUMNS_LETTERS = 10f

    /** A Shift/Delete width at or above this is a side control, not a grid cell. */
    private const val OUTLYING_CONTROL_MIN_WEIGHT = 1.3

    /**
     * The key columns of every row. The input-method switch key is dropped unless [showsGlobe]: the
     * system's own switcher is the default, so a layout's globe key only shows when the user asked.
     */
    fun columns(
        rows: List<LayoutRow>,
        showsGlobe: Boolean = false,
    ): List<List<LayoutKey>> =
        rows.map { row ->
            if (showsGlobe) row.keys else row.keys.filterNot { it.action.type == LayoutActionKind.GLOBE }
        }

    /**
     * A control key that sits outside the character grid: Shift (or, on a 123 / symbol page, the
     * page switch in its place) and Delete surround the characters, so they absorb the width the
     * characters leave instead of shrinking them. A page switch only counts in a row that also
     * carries Delete, so a footer's wide switches keep their own weights.
     */
    fun isOutlyingControl(
        key: LayoutKey,
        row: List<LayoutKey>,
    ): Boolean {
        if (key.widthWeight < OUTLYING_CONTROL_MIN_WEIGHT) return false
        return when (key.action.type) {
            LayoutActionKind.SHIFT, LayoutActionKind.BACKSPACE -> true
            LayoutActionKind.PAGE_SWITCH -> row.any { it.action.type == LayoutActionKind.BACKSPACE }
            else -> false
        }
    }

    /**
     * One key column: every character row shares the same column width, derived from the widest
     * row, so a 9-letter row is narrower than a 10-key row and the letters keep the standard
     * half-key stagger instead of stretching across the keyboard. Rows that carry a space bar or
     * outlying control keys are excluded because they compute their own widths.
     */
    fun columnUnit(
        rows: List<List<LayoutKey>>,
        inner: Float,
        gap: Float,
    ): Float {
        var unit = Float.MAX_VALUE
        for (keys in rows) {
            if (keys.isEmpty()) continue
            if (keys.any { it.action.type == LayoutActionKind.SPACE }) continue
            if (keys.any { isOutlyingControl(it, keys) }) continue
            val weight = keys.sumOf { it.widthWeight }.toFloat()
            if (weight <= 0f) continue
            unit = min(unit, (inner - (keys.size - 1) * gap) / weight)
        }
        return if (unit == Float.MAX_VALUE) inner / COLUMNS_LETTERS else max(unit, 1f)
    }

    /** A split keyboard: the gutter between the halves and the key index each row is cut at. */
    class RowSplit(
        val gap: Float,
        /** Per row: the number of keys in the left half; a row without an entry stays whole. */
        val breaks: List<Int>,
    )

    /** The slots of every row, row-major and contiguous horizontally (the halves apart when [split] is given). */
    fun slots(
        rows: List<List<LayoutKey>>,
        left: Float,
        top: Float,
        rowHeight: Float,
        inner: Float,
        gap: Float,
        split: RowSplit? = null,
    ): List<List<KeyBounds>> {
        val gutter = split?.gap ?: 0f
        // The gutter is room the keys do not get: every row solves its widths in what is left.
        val unit = columnUnit(rows, inner - gutter, gap)
        return rows.mapIndexed { index, keys ->
            val cut = split?.breaks?.getOrNull(index)?.takeIf { it in 1 until keys.size }
            rowSlots(
                keys,
                top + index * rowHeight,
                rowHeight,
                left,
                inner,
                gap,
                unit,
                if (cut ==
                    null
                ) {
                    0f
                } else {
                    gutter
                },
                cut,
            )
        }
    }

    private fun rowSlots(
        keys: List<LayoutKey>,
        top: Float,
        height: Float,
        left: Float,
        fullInner: Float,
        gap: Float,
        unit: Float,
        gutter: Float,
        cut: Int?,
    ): List<KeyBounds> {
        if (keys.isEmpty()) return emptyList()
        val inner = fullInner - gutter
        val widths = FloatArray(keys.size)
        val outlying = keys.count { isOutlyingControl(it, keys) }
        var start = left
        when {
            keys.any { it.action.type == LayoutActionKind.SPACE } -> {
                // A footer line (space bar plus switches) spans the full content width.
                val weight = keys.sumOf { it.widthWeight }.toFloat()
                val lineUnit = if (weight > 0f) (inner - (keys.size - 1) * gap) / weight else 0f
                keys.forEachIndexed { i, key -> widths[i] = key.widthWeight.toFloat() * lineUnit }
            }
            outlying > 0 -> {
                val letters = keys.filterNot { isOutlyingControl(it, keys) }.sumOf { it.widthWeight }.toFloat()
                val side = (inner - letters * unit - (keys.size - 1) * gap) / outlying
                keys.forEachIndexed { i, key ->
                    widths[i] = if (isOutlyingControl(key, keys)) max(side, 1f) else key.widthWeight.toFloat() * unit
                }
            }
            else -> {
                keys.forEachIndexed { i, key -> widths[i] = key.widthWeight.toFloat() * unit }
                val rowWidth = widths.sum() + (keys.size - 1) * gap
                start = left + (inner - rowWidth) / 2f
            }
        }
        var cursor = start
        return keys.mapIndexed { i, _ ->
            // The gutter sits between the halves, on top of the ordinary key gap.
            if (i == cut) cursor += gutter
            val box = KeyBounds(cursor, top, cursor + widths[i], top + height)
            cursor += widths[i] + gap
            box
        }
    }
}

/**
 * Slots of an authored side rail: a column of keys whose relative width is their vertical span.
 * The slots tile the column without a gap (the drawn keycap supplies the gutter, like the rows). A
 * key keeps at least [minimumKeyHeight]; when the minimums would overflow the column every slot
 * shrinks by the same factor, because a rail that scrolls could not be reached while typing.
 */
internal object KeyRailSolver {
    fun slots(
        left: Float,
        width: Float,
        top: Float,
        height: Float,
        weights: List<Double>,
        minimumKeyHeight: Float,
    ): List<KeyBounds> {
        if (weights.isEmpty()) return emptyList()
        val geometry =
            KeyboardPageRailGeometry.resolve(
                height = height.toDouble(),
                weights = weights,
                spacing = 0.0,
                minimumKeyHeight = minimumKeyHeight.toDouble(),
            )
        val heights = geometry.keyHeights.map { it.toFloat() }
        val total = heights.sum()
        val fit = if (total > height && total > 0f) height / total else 1f
        var cursor = top
        return heights.map { h ->
            val box = KeyBounds(left, cursor, left + width, cursor + h * fit)
            cursor += h * fit
            box
        }
    }
}

/**
 * How far the drawn keycap sits inside its placed slot rectangle.
 *
 * The row pages separate neighbours by the horizontal gutter token itself, so their caps fill the
 * slot horizontally (a cap inset would double the visual gap); their rows tile vertically, so each
 * cap retreats half the vertical gutter and the visible row gap is that token. The nine-key shell's
 * placed rectangles already contain its own spacing gutter, so its cap only adds the difference.
 * Pure Kotlin so the visual gutter is unit-tested against the same numbers the view draws.
 */
internal object KeyInsets {
    /** Row pages: the slot already owns the horizontal gutter, so the cap needs no side inset. */
    fun rowHorizontal(): Float = 0f

    /** Row pages: rows tile, so each cap retreats half the vertical gutter from its slot. */
    fun rowVertical(gapVertical: Float): Float = gapVertical / 2f

    /** The shell's placed rects already contain [spacing]; the cap adds the remaining gutter. */
    fun phoneHorizontal(
        gapHorizontal: Float,
        spacing: Float,
    ): Float = max(0f, gapHorizontal - spacing) / 2f

    /** The shell's bands tile, so each cap retreats half the vertical gutter from its band. */
    fun phoneVertical(gapVertical: Float): Float = gapVertical / 2f
}

/**
 * Touch targets of a placed keyboard.
 *
 * The drawn keycap is decoration: a tap anywhere in the key area must have one deterministic
 * owner, so a target is its keycap grown by half the visual gutter towards each neighbour. Two
 * neighbours therefore meet exactly on the gutter's midpoint and no strip between keys is dead.
 * The outermost targets of a row grow to the key area's own edges instead. Pure Kotlin, so the
 * tiling is unit-tested off-device.
 */
internal object KeyHitZones {
    /** Geometry comparison tolerance in px; anything smaller is float noise from the row solve. */
    private const val EDGE_EPS = 0.01f

    /** Rows of contiguous slots, e.g. the row pages: every row owns the full key area width. */
    fun fillRows(
        rows: List<List<KeyBounds>>,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): List<List<KeyBounds>> {
        val filled = rows.filter { it.isNotEmpty() }
        if (filled.isEmpty()) return rows
        return filled.mapIndexed { index, row ->
            val rowTop = row.minOf { it.top }
            val rowBottom = row.maxOf { it.bottom }
            val hitTop =
                if (index == 0) {
                    top
                } else {
                    (filled[index - 1].maxOf { it.bottom } + rowTop) / 2f
                }
            val hitBottom =
                if (index == filled.lastIndex) {
                    bottom
                } else {
                    (rowBottom + filled[index + 1].minOf { it.top }) / 2f
                }
            row.mapIndexed { i, slot ->
                val hitLeft = if (i == 0) left else (row[i - 1].right + slot.left) / 2f
                val hitRight = if (i == row.lastIndex) right else (slot.right + row[i + 1].left) / 2f
                KeyBounds(
                    min(hitLeft, slot.left),
                    hitTop,
                    max(hitRight, slot.right),
                    hitBottom,
                )
            }
        }
    }

    /**
     * Slots whose rows are not a plain list, e.g. the nine-key shell with its two rails and its
     * two-band trailing key: neighbours are found by geometric overlap, and only slots that sit on
     * the area's own boundary grow to it.
     */
    fun fill(
        slots: List<KeyBounds>,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): List<KeyBounds> {
        if (slots.isEmpty()) return slots
        var minLeft = Float.MAX_VALUE
        var maxRight = -Float.MAX_VALUE
        var minTop = Float.MAX_VALUE
        var maxBottom = -Float.MAX_VALUE
        for (slot in slots) {
            minLeft = min(minLeft, slot.left)
            maxRight = max(maxRight, slot.right)
            minTop = min(minTop, slot.top)
            maxBottom = max(maxBottom, slot.bottom)
        }
        val filled = ArrayList<KeyBounds>(slots.size)
        for (i in slots.indices) {
            val slot = slots[i]
            // A side with a neighbour takes that gutter's midpoint; a free side keeps the slot edge
            // and only grows to the key area edge when the slot actually sits on it.
            var leftBoundary = Float.NEGATIVE_INFINITY
            var rightBoundary = Float.POSITIVE_INFINITY
            var topBoundary = Float.NEGATIVE_INFINITY
            var bottomBoundary = Float.POSITIVE_INFINITY
            for (j in slots.indices) {
                if (j == i) continue
                val other = slots[j]
                val overlapX = min(slot.right, other.right) - max(slot.left, other.left)
                val overlapY = min(slot.bottom, other.bottom) - max(slot.top, other.top)
                if (overlapY > EDGE_EPS) {
                    if (other.right <= slot.left + EDGE_EPS) {
                        leftBoundary = max(leftBoundary, (other.right + slot.left) / 2f)
                    }
                    if (other.left >= slot.right - EDGE_EPS) {
                        rightBoundary = min(rightBoundary, (slot.right + other.left) / 2f)
                    }
                }
                if (overlapX > EDGE_EPS) {
                    if (other.bottom <= slot.top + EDGE_EPS) {
                        topBoundary = max(topBoundary, (other.bottom + slot.top) / 2f)
                    }
                    if (other.top >= slot.bottom - EDGE_EPS) {
                        bottomBoundary = min(bottomBoundary, (slot.bottom + other.top) / 2f)
                    }
                }
            }
            val hitLeft =
                when {
                    leftBoundary != Float.NEGATIVE_INFINITY -> leftBoundary
                    slot.left <= minLeft + EDGE_EPS -> left
                    else -> slot.left
                }
            val hitRight =
                when {
                    rightBoundary != Float.POSITIVE_INFINITY -> rightBoundary
                    slot.right >= maxRight - EDGE_EPS -> right
                    else -> slot.right
                }
            val hitTop =
                when {
                    topBoundary != Float.NEGATIVE_INFINITY -> topBoundary
                    slot.top <= minTop + EDGE_EPS -> top
                    else -> slot.top
                }
            val hitBottom =
                when {
                    bottomBoundary != Float.POSITIVE_INFINITY -> bottomBoundary
                    slot.bottom >= maxBottom - EDGE_EPS -> bottom
                    else -> slot.bottom
                }
            filled += KeyBounds(hitLeft, hitTop, hitRight, hitBottom)
        }
        return filled
    }
}

/**
 * How a split keyboard spends its width (dp): an outer margin on both sides so the halves do not
 * run into the screen edges, then the gutter, and the keys take the rest. The automatic gutter is
 * narrower than the shared model's thumb-reach one so the keys get wider; each half still stops
 * at [MAXIMUM_HALF_DP] and a wider window opens the gutter instead. The keys never drop below the
 * split's minimum: when the width is short the margin goes first, then the gutter.
 */
internal object SplitFrame {
    class Plan(
        val inset: Double,
        val gutter: Double,
    )

    private const val MARGIN_SHARE = 0.025
    private const val MARGIN_MIN_DP = 8.0
    private const val MARGIN_MAX_DP = 20.0
    private const val GUTTER_SHARE = 0.2
    const val MAXIMUM_HALF_DP = 300.0

    fun plan(
        split: LayoutSplitConfiguration,
        width: Double,
        keyCount: Int,
        spacing: Double,
    ): Plan {
        val shared = split.gap(width, keyCount, spacing)
        if (shared <= 0.0) return Plan(0.0, 0.0)
        val keyFloor = LayoutSplitConfiguration.MINIMUM_AUTOMATIC_KEY_WIDTH
        val slack = max(0.0, width - keyCount * keyFloor - (keyCount - 1) * spacing)
        val margin = (width * MARGIN_SHARE).coerceIn(MARGIN_MIN_DP, MARGIN_MAX_DP)
        val requested =
            if (split.automaticGap) {
                max(width * GUTTER_SHARE, width - 2 * margin - 2 * MAXIMUM_HALF_DP)
            } else {
                shared
            }
        val gutter = min(requested, slack)
        if (gutter <= spacing) return Plan(0.0, 0.0)
        return Plan(min(margin, (slack - gutter) / 2), gutter)
    }
}
