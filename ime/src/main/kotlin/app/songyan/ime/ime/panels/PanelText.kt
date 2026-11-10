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

    /**
     * [text] in one line when it fits [availablePx], else in two lines broken where they come out most
     * even: between words when the text has spaces, otherwise (CJK) anywhere but inside a katakana word or
     * before a mark that must not start a line. When no break fits both halves, the second line is ellipsized.
     */
    fun twoLines(
        text: String,
        availablePx: Float,
        measure: (String) -> Float,
    ): List<String> {
        if (measure(text) <= availablePx || text.length < 2) return listOf(text)
        val breaks =
            if (text.contains(' ')) {
                text.indices.filter { text[it] == ' ' }.map { it + 1 }
            } else {
                // A katakana run is one word (プレビュー), so it stays whole.
                (1 until text.length).filter {
                    text[it] !in NO_LINE_START &&
                        !(isKatakana(text[it - 1]) && isKatakana(text[it]))
                }
            }
        val best =
            breaks
                .map { text.substring(0, it).trim() to text.substring(it).trim() }
                .filter { (first, rest) -> first.isNotEmpty() && rest.isNotEmpty() }
                .map { (first, rest) -> Triple(first, rest, maxOf(measure(first), measure(rest))) }
                .filter { it.third <= availablePx }
                .minByOrNull { it.third }
        if (best != null) return listOf(best.first, best.second)
        var split = text.length
        while (split > 1 && measure(text.substring(0, split)) > availablePx) split--
        if (text.contains(' ')) text.lastIndexOf(' ', split).takeIf { it > 0 }?.let { split = it + 1 }
        return listOf(text.substring(0, split).trim(), ellipsize(text.substring(split).trim(), availablePx, measure))
    }

    private const val ELLIPSIS = "\u2026"

    private fun isKatakana(c: Char): Boolean = c in '\u30A0'..'\u30FF'

    /** Marks a line may not open with (JIS X 4051 / CSS line-break: strict). */
    private const val NO_LINE_START = "ー・、。，．）」』】〉》！？：；ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮヵヶ"
}
