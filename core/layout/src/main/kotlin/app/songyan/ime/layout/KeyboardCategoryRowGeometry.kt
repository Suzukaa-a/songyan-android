package app.songyan.ime.layout

/**
 * Category-grid row geometry, in dp. A tail row reuses the preceding full row's column scale instead
 * of stretching its fewer keys across the whole width.
 */
data class KeyboardCategoryRowGeometry(
    val keyWidths: List<Double>,
    val gap: Double,
    val splitIndex: Int,
    val usedWidth: Double,
) {
    companion object {
        private const val SPACING = 4.0

        fun resolve(
            width: Double,
            columns: Int,
            weights: List<Double>,
            row: Int,
            split: LayoutSplitConfiguration?,
            referenceWeights: List<Double>? = null,
        ): KeyboardCategoryRowGeometry {
            val resolvedWidth = if (width.isFinite()) maxOf(0.0, width) else 0.0
            val fullGap =
                split?.gap(
                    resolvedWidth,
                    keyCount = columns,
                    spacing = SPACING,
                    minimumKeyWidth = if (columns == 2) 140.0 else 28.0,
                ) ?: 0.0
            val splitIndex = split?.breakIndex(row, columns, width) ?: columns
            val gap = if (weights.size > splitIndex) fullGap else 0.0
            val reference = referenceWeights ?: weights
            val average =
                if (reference.isEmpty()) {
                    0.0
                } else {
                    reference.map { it / reference.size }.sum()
                }
            val emptyWeights =
                (weights.size until columns).map { index ->
                    if (index < reference.size) reference[index] else average
                }
            val projection = LayoutRowWidthProjection.of(weights + emptyWeights)
            val available = maxOf(0.0, resolvedWidth - fullGap - (columns - 1) * SPACING)
            val keyWidths = projection.keyFractions.take(weights.size).map { available * it }
            val usedWidth = keyWidths.sum() + maxOf(0, weights.size - 1) * SPACING + gap
            return KeyboardCategoryRowGeometry(keyWidths, gap, splitIndex, usedWidth)
        }
    }
}
