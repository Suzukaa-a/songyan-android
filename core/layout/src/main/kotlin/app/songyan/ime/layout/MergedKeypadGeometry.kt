package app.songyan.ime.layout

/**
 * Key widths of the merged letter pad (14-key, 18-key). One unit is one 26-key letter: a key of
 * weight `w` spans `w` units plus the `w - 1` gaps between them, so a row width depends only on
 * its total weight. Upper rows share the widest row unit and are centred; the last row sits
 * between the separator and Delete keys and fills that space.
 */
object MergedKeypadGeometry {
    data class Row(
        val leadingInset: Double,
        val keyWidths: List<Double>,
    )

    /** [width] is the full row width; [sideWidth] is each side key in the last row. */
    fun rows(
        weights: List<List<Double>>,
        width: Double,
        spacing: Double,
        sideWidth: Double,
    ): List<Row> {
        if (weights.isEmpty()) return emptyList()
        val upper = weights.dropLast(1)
        val widest = upper.maxOfOrNull { units(it) } ?: 0.0
        val unit = if (widest > 0.0) maxOf(0.0, (width - (widest - 1) * spacing) / widest) else 0.0
        val result =
            upper
                .map { row ->
                    val widths = row.map { span(safe(it), unit, spacing) }
                    val used = widths.sum() + maxOf(0, row.size - 1) * spacing
                    Row(leadingInset = maxOf(0.0, (width - used) / 2), keyWidths = widths)
                }.toMutableList()
        val last = weights.last()
        val available = maxOf(0.0, width - 2 * (sideWidth + spacing))
        val lastUnits = units(last)
        val lastUnit = if (lastUnits > 0.0) maxOf(0.0, (available - (lastUnits - 1) * spacing) / lastUnits) else 0.0
        result.add(Row(leadingInset = 0.0, keyWidths = last.map { span(safe(it), lastUnit, spacing) }))
        return result
    }

    /** Total units of a row, gaps of multi-unit keys included. */
    fun units(row: List<Double>): Double = row.sumOf { safe(it) }

    private fun span(
        weight: Double,
        unit: Double,
        spacing: Double,
    ): Double = weight * unit + maxOf(0.0, weight - 1) * spacing

    private fun safe(weight: Double): Double = if (weight.isFinite() && weight > 0.0) weight else 1.0
}
