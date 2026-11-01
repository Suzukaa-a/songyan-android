package app.songyan.ime.ime

/** Bounds include the visual gutter so there are no dead strips between keys. */
data class KeyBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun contains(
        x: Float,
        y: Float,
    ) = x >= left && x < right && y >= top && y < bottom
}

object KeyGeometry {
    fun row(
        weights: List<Float>,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
    ): List<KeyBounds> {
        val total = weights.sum()
        var x = left
        return weights.map { weight ->
            val right = x + width * weight / total
            KeyBounds(x, top, right, top + height).also { x = right }
        }
    }

    fun hit(
        bounds: List<KeyBounds>,
        x: Float,
        y: Float,
    ): Int = bounds.indexOfFirst { it.contains(x, y) }

    /** Inside [bounds] grown by [margin] on every side, the way a View judges a finger still on it. */
    fun near(
        bounds: KeyBounds,
        x: Float,
        y: Float,
        margin: Float,
    ): Boolean =
        x >= bounds.left - margin && x < bounds.right + margin && y >= bounds.top - margin && y < bounds.bottom + margin
}
