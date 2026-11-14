package app.songyan.ime.layout

/**
 * The gap between the two parts of a wide keyboard: the halves of an automatically split keyboard,
 * or the 9-key keyboard and its candidates. Its share of the width grows with the width
 * ([percent] at [REFERENCE_WIDTH], half that at half the width), so a narrow tablet in portrait
 * keeps more of its width for keys than a wide one in landscape, and a forced split on a small
 * screen still has a gutter. The iOS keyboard uses the same curve.
 */
object WideMiddleGap {
    const val MIN_PERCENT = 0
    const val MAX_PERCENT = 20
    const val STEP_PERCENT = 1
    const val DEFAULT_PERCENT = 10

    /** The keyboard width the percentage is quoted at. */
    const val REFERENCE_WIDTH = 1000.0

    fun clampedPercent(value: Int): Int = value.coerceIn(MIN_PERCENT, MAX_PERCENT)

    /** The gap on a [width] wide keyboard: [percent] of the width, scaled by the width over [REFERENCE_WIDTH]. */
    fun width(
        width: Double,
        percent: Int,
    ): Double {
        if (!width.isFinite() || width <= 0.0) return 0.0
        return width * clampedPercent(percent) / 100.0 * (width / REFERENCE_WIDTH)
    }

    /**
     * The choice in effect, set by the keyboard each time it reads the user's settings. A device
     * setting rather than layout content, so it is not threaded through every split.
     */
    @Volatile
    var percent: Int = DEFAULT_PERCENT
        set(value) {
            field = clampedPercent(value)
        }
}
