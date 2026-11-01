package app.songyan.ime.ime.panels

/**
 * Text fitting of the quick-content panels.
 *
 * Rail labels and symbol cells have to hold one, two or three characters in a fixed slot: a
 * category name must not overflow its 60 dp rail and a kaomoji must not spill out of its cell.
 * Both rules are plain arithmetic, so they are unit-tested off-device with a fake measurer
 * instead of being tuned by eye against one screen size.
 */
internal object PanelText {
    /** Smallest size a fitted label may be drawn at; below this a label is ellipsized instead. */
    const val MIN_FIT_SIZE_SP: Float = 10f

    /**
     * The largest size in [minSp]..[naturalSp] at which [measure] reports [text] fits
     * [availablePx]. Text that never fits keeps the minimum size; the caller ellipsizes it.
     */
    fun fitSize(
        naturalSp: Float,
        minSp: Float,
        availablePx: Float,
        measure: (Float) -> Float,
    ): Float {
        if (availablePx <= 0f) return minSp
        var size = naturalSp
        while (size > minSp && measure(size) > availablePx) size -= 0.5f
        return maxOf(minSp, size)
    }

    /**
     * [text] cut to [availablePx] with a trailing ellipsis. The cut keeps at least one character,
     * so a label never becomes a bare "…" on a very narrow slot.
     */
    fun ellipsize(
        text: String,
        availablePx: Float,
        measure: (String) -> Float,
    ): String {
        if (text.isEmpty() || availablePx <= 0f) return text
        if (measure(text) <= availablePx) return text
        val ellipsis = ELLIPSIS
        var end = text.length - 1
        while (end > 1 && measure(text.substring(0, end) + ellipsis) > availablePx) end--
        return text.substring(0, end) + ellipsis
    }

    private const val ELLIPSIS = "\u2026"
}
