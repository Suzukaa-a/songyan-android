package app.songyan.ime.layout

/**
 * Horizontal split of an authored page's width (dp) into its leading rail, main area and trailing
 * rail, shared by the runtime and any preview so both place the same authored keys the same way.
 *
 * A rail whose width is null takes the phone keypad shell's rail width, so a materialized keypad
 * page keeps the geometry it was copied from. When both rails together would leave less than three
 * minimum touch targets for the main area they shrink in proportion instead of overflowing.
 */
data class KeyboardPageChromeMetrics(
    val leadingWidth: Double,
    val trailingWidth: Double,
    /** Distance from the left edge to the main area. */
    val mainLeft: Double,
    val mainWidth: Double,
    /** Width of the footer row: narrower only when the trailing rail runs down beside it. */
    val bottomWidth: Double,
) {
    companion object {
        fun resolve(
            width: Double,
            rowHeight: Double,
            chrome: KeyboardPageChrome,
            spacing: Double,
        ): KeyboardPageChromeMetrics {
            val root = if (width.isFinite()) maxOf(0.0, width) else 0.0
            val gap = if (spacing.isFinite()) maxOf(0.0, spacing) else 0.0
            val hasLeading = chrome.leading.isNotEmpty()
            val hasTrailing = chrome.trailing.isNotEmpty()
            val defaultRail = PhoneKeypadMetrics.resolve(root, rowHeight).railWidth
            var leading = if (hasLeading) clampRail(chrome.leadingWidth ?: defaultRail, root) else 0.0
            var trailing = if (hasTrailing) clampRail(chrome.trailingWidth ?: defaultRail, root) else 0.0
            val minimumMain = minOf(root, PhoneKeypadMetrics.MINIMUM_TOUCH_TARGET * 3 + gap * 2)
            val gaps = (if (leading > 0.0) gap else 0.0) + (if (trailing > 0.0) gap else 0.0)
            val allowed = maxOf(0.0, root - gaps - minimumMain)
            if (leading + trailing > allowed) {
                val total = leading + trailing
                if (total > 0.0) {
                    val keptLeading = allowed * leading / total
                    leading = keptLeading
                    trailing = allowed - keptLeading
                }
            }
            val mainLeft = if (leading > 0.0) leading + gap else 0.0
            val main = maxOf(0.0, root - mainLeft - if (trailing > 0.0) trailing + gap else 0.0)
            val bottom =
                if (hasTrailing && chrome.trailingSpansBottom) maxOf(0.0, root - trailing - gap) else root
            return KeyboardPageChromeMetrics(leading, trailing, mainLeft, main, bottom)
        }

        private fun clampRail(
            value: Double,
            root: Double,
        ): Double = minOf(maxOf(0.0, if (value.isFinite()) value else 0.0), root)
    }
}
