package app.songyan.ime.content

/**
 * Vertical budget of the quick-content panel (symbols, phrases and clipboard), in dp.
 *
 * The panel is drawn inside the keyboard's own height, which on a phone in landscape is a
 * fraction of the portrait height. Chrome that takes its ideal height regardless of the space
 * left overflows its bands on a short panel: rows collide and the content gets clipped away.
 * This policy is the single place that decides how much room each band gets, and it guarantees
 * the chrome never exceeds the panel.
 *
 * The choices live in a leading column, the way the nine-key symbol page does it: one vertical
 * list of slots, scrolling when the list outgrows the panel, with the open choice marked. There
 * is no horizontal chip row, so nothing is squeezed into two stacked rows.
 */
data class QuickContentPanelLayout(
    val density: Density,
    /** Back / title / search row. */
    val titleBarHeight: Double,
    /** One divider; part of the budget, so it never adds to the panel. */
    val separatorHeight: Double,
    /** Height left for the leading column and the content side by side; never negative. */
    val contentHeight: Double,
    /** Leading column width. */
    val railWidth: Double,
    /** One leading-column slot; the list scrolls when the slots outgrow the panel. */
    val railSlotHeight: Double,
    val symbolCellMinHeight: Double,
    /** Minimum column width for a single glyph. */
    val symbolColumnMinWidth: Double,
    /** Minimum column width for a whole-phrase (kaomoji) cell. */
    val kaomojiColumnMinWidth: Double,
    /** Whole-phrase cells use this many equal columns; single glyphs stay adaptive. */
    val kaomojiColumnCount: Int,
    /** "Recent symbols" needs its own row: only worth showing when there is height to spare. */
    val showsRecents: Boolean,
) {
    /** How much chrome the panel can afford. */
    enum class Density {
        REGULAR,
        COMPACT,
        MINIMAL,
    }

    companion object {
        /** One divider, in dp. */
        private const val SEPARATOR: Double = 0.5

        /**
         * A slot keeps Android's 48 dp minimum touch target; the list scrolls when the slots outgrow
         * the panel instead of squeezing them below this floor.
         */
        private const val RAIL_SLOT_FLOOR: Double = 48.0

        /** A taller slot would waste the grid's room. */
        private const val RAIL_SLOT_CEILING: Double = 56.0

        /** Panel height (dp) from which the regular chrome fits. */
        private const val REGULAR_MIN_HEIGHT: Double = 240.0

        /** Panel height (dp) from which the compact chrome fits. */
        private const val COMPACT_MIN_HEIGHT: Double = 176.0

        /**
         * Leading column width: three Chinese characters at the rail's 15 sp, at the largest font
         * scale the keyboard follows, plus the label padding and the selection pill's inset.
         */
        private const val RAIL_WIDTH: Double = 68.0

        /** Whole phrases sit in a fixed number of equal columns. */
        private const val KAOMOJI_COLUMNS: Int = 3

        /** Content height (dp) below which the recents row is not worth showing. */
        private const val RECENTS_MIN_CONTENT_HEIGHT: Double = 150.0

        /**
         * The layout for a panel of [height] dp showing [railItemCount] leading-column rows.
         *
         * A panel shorter than its own title bar cannot exist, but a mid-animation frame or a
         * very short split layout can still ask for less: the chrome is scaled instead of
         * overflowing.
         */
        fun resolve(
            height: Double,
            railItemCount: Int,
        ): QuickContentPanelLayout {
            val bounded = maxOf(0.0, height)
            val density =
                when {
                    bounded >= REGULAR_MIN_HEIGHT -> Density.REGULAR
                    bounded >= COMPACT_MIN_HEIGHT -> Density.COMPACT
                    else -> Density.MINIMAL
                }
            val title =
                when (density) {
                    Density.REGULAR -> 44.0
                    Density.COMPACT -> 40.0
                    Density.MINIMAL -> 36.0
                }
            val cellHeight =
                when (density) {
                    Density.REGULAR -> 56.0
                    Density.COMPACT -> 48.0
                    Density.MINIMAL -> 44.0
                }
            val columnWidth =
                when (density) {
                    Density.REGULAR -> 64.0
                    Density.COMPACT -> 58.0
                    Density.MINIMAL -> 52.0
                }
            val chrome = title + SEPARATOR
            val scale = if (chrome > bounded) bounded / chrome else 1.0
            val content = maxOf(0.0, bounded - chrome * scale)
            val slots = maxOf(1, railItemCount)
            val gaps = (slots - 1) * SEPARATOR
            val slot = minOf(RAIL_SLOT_CEILING, maxOf(RAIL_SLOT_FLOOR, (content - gaps) / slots))
            return QuickContentPanelLayout(
                density = density,
                titleBarHeight = title * scale,
                separatorHeight = SEPARATOR * scale,
                contentHeight = content,
                railWidth = RAIL_WIDTH,
                railSlotHeight = slot,
                symbolCellMinHeight = cellHeight,
                symbolColumnMinWidth = columnWidth,
                kaomojiColumnMinWidth = 72.0,
                kaomojiColumnCount = KAOMOJI_COLUMNS,
                showsRecents = density == Density.REGULAR && content >= RECENTS_MIN_CONTENT_HEIGHT,
            )
        }
    }
}
