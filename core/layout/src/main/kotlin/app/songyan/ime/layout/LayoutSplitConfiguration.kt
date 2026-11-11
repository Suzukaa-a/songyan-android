package app.songyan.ime.layout

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One row of an explicit split arrangement: the keys left and right of the gap. A half may be empty
 * only when the row is a single key, which cannot be cut.
 */
@Serializable
data class LayoutSplitRow(
    val left: List<LayoutKey>,
    val right: List<LayoutKey>,
) {
    val keys: List<LayoutKey> get() = left + right

    val hasKeyInEachHalf: Boolean
        get() = (left.isNotEmpty() && right.isNotEmpty()) || (left.size + right.size == 1)
}

/**
 * A saved split follows the available keyboard width, including floating windows.
 *
 * The wire form is the iOS one: all four fields are always written, because the iOS decoder requires
 * them. [of] mirrors the iOS initializer, where an explicit fraction turns automatic spacing off.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class LayoutSplitConfiguration(
    /** How the split decides whether to appear. */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val activation: Activation = Activation.AUTOMATIC,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val rowBreaks: List<Int> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val gapFraction: Double = DEFAULT_GAP_FRACTION,
    /** The gutter follows the width (the thumb-reach rule) instead of [gapFraction]. */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val automaticGap: Boolean = true,
    /**
     * The split arrangement as its own copy of the keys, one entry per row of the layout (the
     * control row included): the keys of each half. Written only once the user edited it; when absent
     * the arrangement is derived from the layout rows cut at [rowBreaks]. When present it replaces
     * the derived one and [rowBreaks] are not consulted for rendering or validation.
     */
    val halves: List<LayoutSplitRow>? = null,
) {
    /** When a split is shown. Independent from [automaticGap]. */
    @Serializable
    enum class Activation {
        /** Saved row cuts and spacing stay, but rows are never split. */
        @SerialName("off")
        OFF,

        /** Split only on wide surfaces. */
        @SerialName("automatic")
        AUTOMATIC,

        /** Split at every width; the user explicitly asked for two halves. */
        @SerialName("always")
        ALWAYS,
    }

    /**
     * Whether this split really shows at [width]. [Activation.ALWAYS] ignores the width rule;
     * [Activation.AUTOMATIC] splits above [automaticAboveDp], which the user's device setting can move
     * without touching the layout file.
     */
    fun isActive(
        width: Double,
        automaticAboveDp: Double = AUTOMATIC_MAXIMUM_WIDTH,
    ): Boolean =
        when (activation) {
            Activation.OFF -> false
            Activation.ALWAYS -> true
            Activation.AUTOMATIC -> width.isFinite() && width > automaticAboveDp
        }

    /**
     * The split to render at this width, or null when the row stays whole. The returned copy is
     * marked [Activation.ALWAYS] in memory only, so an inner key area narrowed by rails cannot make
     * the automatic rule switch the split off a second time.
     */
    fun resolvedForWidth(
        width: Double,
        automaticAboveDp: Double = AUTOMATIC_MAXIMUM_WIDTH,
    ): LayoutSplitConfiguration? {
        if (!isActive(width, automaticAboveDp)) return null
        return copy(activation = Activation.ALWAYS)
    }

    /** The arrangement for [rows]: the explicit [halves] when there are any, else [rows] cut at [rowBreaks]. */
    fun arrangement(rows: List<LayoutRow>): List<LayoutSplitRow> = halves ?: derivedArrangement(rows)

    /** [rows] cut at [rowBreaks] (the footer's space bar becomes one half under each thumb). */
    fun derivedArrangement(rows: List<LayoutRow>): List<LayoutSplitRow> =
        rows.mapIndexed { index, row -> cut(index, row.keys) }

    /** One row cut where this split says; the space bar of a row is divided between the halves. */
    fun cut(
        row: Int,
        keys: List<LayoutKey>,
    ): LayoutSplitRow {
        val spaceAt = keys.indexOfFirst { it.action.type == LayoutActionKind.SPACE }
        if (spaceAt < 0) {
            val at = breakIndex(row, keys.size)
            return LayoutSplitRow(keys.take(at), keys.drop(at))
        }
        val space = keys[spaceAt]
        val half = space.copy(width = space.widthWeight / 2)
        return LayoutSplitRow(keys.take(spaceAt) + half, listOf(half) + keys.drop(spaceAt + 1))
    }

    /**
     * [rowKeyCounts] are the keys of every whole row. Explicit [halves] must match the row count and
     * keep a key in each half; [allowsHalves] is false for pages, which only follow the layout's split.
     */
    @Throws(LayoutException::class)
    fun validate(
        rowKeyCounts: List<Int>,
        allowsHalves: Boolean = false,
    ) {
        if (!gapFraction.isFinite() || gapFraction < 0.0 || gapFraction > 0.85) {
            throw LayoutException.Invalid(LayoutInvalidCode.SPLIT_GAP_FRACTION_OUT_OF_RANGE)
        }
        val arranged = halves
        if (arranged != null) {
            if (!allowsHalves) throw LayoutException.Invalid(LayoutInvalidCode.SPLIT_HALVES_ONLY_ON_LAYOUT)
            if (arranged.size != rowKeyCounts.size) {
                throw LayoutException.Invalid(
                    LayoutInvalidCode.SPLIT_HALVES_ROW_COUNT_MISMATCH,
                    "halves " + arranged.size + " rows " + rowKeyCounts.size,
                )
            }
            for ((row, pair) in arranged.withIndex()) {
                if (!pair.hasKeyInEachHalf) {
                    throw LayoutException.Invalid(LayoutInvalidCode.SPLIT_HALF_EMPTY, "row " + row)
                }
            }
            return
        }
        if (rowBreaks.size > rowKeyCounts.size) {
            throw LayoutException.Invalid(LayoutInvalidCode.SPLIT_BREAK_OUT_OF_RANGE)
        }
        for ((row, count) in rowBreaks.withIndex()) {
            val keys = rowKeyCounts[row]
            if (keys > 1 && (count < 1 || count >= keys)) {
                throw LayoutException.Invalid(
                    LayoutInvalidCode.SPLIT_BREAK_OUT_OF_RANGE,
                    "row " + row + " break " + count,
                )
            }
        }
    }

    /** Character index of the split in a row, ignoring the width activation rule. */
    fun breakIndex(
        row: Int,
        keyCount: Int,
    ): Int {
        if (keyCount <= 1) return keyCount
        val requested =
            if (row in rowBreaks.indices) rowBreaks[row] else (keyCount + 1) / 2
        return minOf(keyCount - 1, maxOf(1, requested))
    }

    /** The split index to render at this width, or null when the row stays whole. */
    fun breakIndex(
        row: Int,
        keyCount: Int,
        width: Double,
    ): Int? {
        if (!isActive(width)) return null
        return breakIndex(row, keyCount)
    }

    /** The gutter width reserved for the split at [width], or 0 when the row stays whole. */
    fun gap(
        width: Double,
        keyCount: Int,
        spacing: Double = 4.0,
        minimumKeyWidth: Double = 28.0,
    ): Double {
        if (!isActive(width) || keyCount <= 1) return 0.0
        val keyFloor = if (automaticGap) maxOf(MINIMUM_AUTOMATIC_KEY_WIDTH, minimumKeyWidth) else minimumKeyWidth
        val room = maxOf(0.0, width - keyCount * keyFloor - (keyCount - 1) * spacing)
        // Keep large surfaces within thumb reach, while narrow windows keep usable keys.
        val maximumContentWidth =
            minOf(
                MAXIMUM_THUMB_HALF_WIDTH * 2,
                keyCount * PhoneKeypadMetrics.PREFERRED_DIGIT_WIDTH + (keyCount - 1) * spacing,
            )
        val requested =
            if (automaticGap) {
                maxOf(width * DEFAULT_GAP_FRACTION, width - maximumContentWidth)
            } else {
                width * gapFraction
            }
        val result = minOf(requested, room)
        return if (result > spacing) result else 0.0
    }

    companion object {
        /** Widths at or below this stay unsplit under [Activation.AUTOMATIC]. */
        const val AUTOMATIC_MAXIMUM_WIDTH: Double = 600.0

        const val DEFAULT_GAP_FRACTION: Double = 0.32

        const val MAXIMUM_THUMB_HALF_WIDTH: Double = 280.0

        /** Automatic spacing never shrinks a key below this width. */
        const val MINIMUM_AUTOMATIC_KEY_WIDTH: Double = 32.0

        /** The iOS initializer: an explicit [gapFraction] keeps manual spacing, an omitted one is automatic. */
        fun of(
            activation: Activation = Activation.AUTOMATIC,
            rowBreaks: List<Int> = emptyList(),
            gapFraction: Double? = null,
        ): LayoutSplitConfiguration =
            LayoutSplitConfiguration(
                activation = activation,
                rowBreaks = rowBreaks,
                gapFraction = gapFraction ?: DEFAULT_GAP_FRACTION,
                automaticGap = gapFraction == null,
            )

        /**
         * Pages follow the main keyboard split unless they authored their own. Page splits drop
         * the main row breaks: other pages have different key counts and choose their own.
         */
        fun resolving(
            page: LayoutSplitConfiguration?,
            main: LayoutSplitConfiguration?,
        ): LayoutSplitConfiguration? {
            if (page != null) return page
            val inherited = main ?: return null
            return inherited.copy(rowBreaks = emptyList(), halves = null)
        }
    }
}
