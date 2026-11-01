package app.songyan.ime.layout

/**
 * Vertical spans for a leading/trailing rail, in dp, shared by the runtime and the preview so both
 * place the same authored keys at the same heights.
 *
 * A rail key's relative width is a relative vertical span, not a width. With total height H, spacing
 * s and spans summing to S, key i is (H + s) * span_i / S - s; each height is then raised to the
 * minimum tap height. When the minimum pushes the rail past H the caller scrolls it instead of
 * compressing keys below a usable size.
 */
data class KeyboardPageRailGeometry(
    val keyHeights: List<Double>,
    /** Total rail content height including inter-key spacing; scroll when it exceeds H. */
    val contentHeight: Double,
    val spacing: Double,
) {
    companion object {
        fun resolve(
            height: Double,
            weights: List<Double>,
            spacing: Double = 4.0,
            minimumKeyHeight: Double = 44.0,
        ): KeyboardPageRailGeometry {
            val resolvedSpacing = if (spacing.isFinite()) maxOf(0.0, spacing) else 0.0
            val resolvedHeight = if (height.isFinite()) maxOf(0.0, height) else 0.0
            val minimum = if (minimumKeyHeight.isFinite()) maxOf(0.0, minimumKeyHeight) else 0.0
            val safeWeights = weights.map { if (it.isFinite() && it > 0.0) it else 0.0 }
            // Divide by the largest weight before summing so legal largest-magnitude values cannot
            // overflow the total or the per-key multiplication.
            val scale = safeWeights.maxOrNull() ?: 0.0
            val normalized = if (scale > 0.0) safeWeights.map { it / scale } else safeWeights
            val total = normalized.sum()
            if (weights.isEmpty()) {
                return KeyboardPageRailGeometry(emptyList(), 0.0, resolvedSpacing)
            }
            if (total <= 0.0) {
                // Unvalidated preview weights: keep every span at the usable minimum instead of
                // borrowing the whole rail height for each key.
                val keyHeights = weights.map { minimum }
                val contentHeight = keyHeights.sum() + resolvedSpacing * (weights.size - 1)
                return KeyboardPageRailGeometry(keyHeights, contentHeight, resolvedSpacing)
            }
            val keyHeights =
                normalized.map { weight ->
                    val raw = (resolvedHeight + resolvedSpacing) * weight / total - resolvedSpacing
                    maxOf(minimum, raw)
                }
            val contentHeight = keyHeights.sum() + resolvedSpacing * (weights.size - 1)
            return KeyboardPageRailGeometry(keyHeights, contentHeight, resolvedSpacing)
        }
    }
}
