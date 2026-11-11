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

    /**
     * Fingers land a little below the point they aim at, and more so on the lower rows, where the
     * thumb reaches down and flattens. The press is read that much higher before the key under it is
     * picked, after AOSP LatinIME's touch position correction (about 0, 0.04 and 0.09 of a key
     * height on its three letter rows): growing from nothing at the top of the key area to
     * [TOUCH_Y_CORRECTION] of the touched row's height at the bottom.
     */
    fun hitCorrected(
        bounds: List<KeyBounds>,
        x: Float,
        y: Float,
        areaHeight: Float,
    ): Int {
        val raw = hit(bounds, x, y)
        if (raw < 0 || areaHeight <= 0f) return raw
        val row = bounds[raw]
        val reach = (y / areaHeight).coerceIn(0f, 1f)
        val corrected = hit(bounds, x, y - TOUCH_Y_CORRECTION * (row.bottom - row.top) * reach)
        return if (corrected >= 0) corrected else raw
    }

    const val TOUCH_Y_CORRECTION = 0.09f

    /** Inside [bounds] grown by [margin] on every side, the way a View judges a finger still on it. */
    fun near(
        bounds: KeyBounds,
        x: Float,
        y: Float,
        margin: Float,
    ): Boolean =
        x >= bounds.left - margin && x < bounds.right + margin && y >= bounds.top - margin && y < bounds.bottom + margin
}
