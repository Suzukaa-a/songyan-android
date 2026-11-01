package app.songyan.ime.layout

/**
 * Projects relative row weights into finite fractions without overflowing their sum. Scaling by
 * the largest weight preserves authored ratios, including rows whose equally weighted keys are all
 * near [Double.MAX_VALUE] or [Double.MIN_VALUE].
 */
data class LayoutRowWidthProjection(
    val keyFractions: List<Double>,
    val insetFraction: Double,
) {
    companion object {
        /**
         * Builds the projection. Raw, unvalidated preview values must not produce NaN or infinite
         * geometry, so non-finite weights and insets count as zero.
         */
        fun of(
            weights: List<Double>,
            inset: Double = 0.0,
        ): LayoutRowWidthProjection {
            val safeWeights = weights.map { if (it.isFinite() && it > 0.0) it else 0.0 }
            val safeInset = if (inset.isFinite() && inset > 0.0) inset else 0.0
            val scale = maxOf(safeWeights.maxOrNull() ?: 0.0, safeInset)
            if (scale <= 0.0) {
                return LayoutRowWidthProjection(
                    keyFractions = List(weights.size) { 0.0 },
                    insetFraction = 0.0,
                )
            }
            val scaled = safeWeights.map { it / scale }
            val scaledInset = safeInset / scale
            val total = scaled.sum() + scaledInset + scaledInset
            return LayoutRowWidthProjection(
                keyFractions = scaled.map { it / total },
                insetFraction = scaledInset / total,
            )
        }
    }
}
