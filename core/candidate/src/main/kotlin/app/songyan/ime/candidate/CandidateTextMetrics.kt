package app.songyan.ime.candidate

/**
 * Estimated rendered width of candidate text: a layout budget, not exact typesetting.
 *
 * CJK and other full-width scalars count as 1 em, everything else as 0.62 em. Real font fidelity is
 * checked by rendering, not here.
 */
object CandidateTextMetrics {
    fun estimatedWidth(
        text: String,
        fontSize: Double,
    ): Double {
        if (text.isEmpty() || !fontSize.isFinite() || fontSize <= 0.0) return 0.0
        var cjk = 0
        var latin = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            // Code points above U+2E80 are treated as full-width, matching the iOS estimate. The walk
            // steps by code point, so an astral scalar (emoji, rare CJK) counts once instead of
            // twice as its UTF-16 surrogate pair.
            if (codePoint > FULL_WIDTH_THRESHOLD) cjk++ else latin++
            index += Character.charCount(codePoint)
        }
        return cjk * fontSize + latin * fontSize * LATIN_WIDTH_EM
    }

    private const val LATIN_WIDTH_EM: Double = 0.62

    /** First code point the estimator treats as full-width (a scalar value, not a UTF-16 unit). */
    private const val FULL_WIDTH_THRESHOLD: Int = 0x2E80
}
