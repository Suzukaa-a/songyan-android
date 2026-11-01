package app.songyan.ime.layout

/** Keeps a usable space bar in each half while preserving the surrounding controls. */
data class SplitSpaceRowGeometry(
    val gap: Double,
    val spacing: Double,
    val leftWidth: Double,
    val rightWidth: Double,
    val leftSpaceWidth: Double,
    val rightSpaceWidth: Double,
) {
    companion object {
        /**
         * Resolves one split control row, or null when the halves cannot keep a usable space
         * bar. Space takes priority over the gutter, with equal targets under both thumbs.
         */
        fun resolve(
            width: Double,
            requestedGap: Double,
            leftKeyWidths: List<Double>,
            rightKeyWidths: List<Double>,
            spacing: Double = 4.0,
            minimumSpaceWidth: Double = 44.0,
            preferredSpaceWidth: Double? = null,
        ): SplitSpaceRowGeometry? {
            val leftFixed = leftKeyWidths.sum() + leftKeyWidths.size * spacing
            val rightFixed = rightKeyWidths.sum() + rightKeyWidths.size * spacing
            val available = width - leftFixed - rightFixed - spacing
            if (!(requestedGap > spacing) || !(available - minimumSpaceWidth * 2 > spacing)) {
                return null
            }
            val preferred = maxOf(minimumSpaceWidth, preferredSpaceWidth ?: minimumSpaceWidth)
            val fittingSpace = maxOf(minimumSpaceWidth, (available - spacing * 2) / 2)
            val spaceFloor = minOf(preferred, fittingSpace)
            val gap = minOf(requestedGap, available - spaceFloor * 2)
            val space = (available - gap) / 2
            return SplitSpaceRowGeometry(
                gap = gap,
                spacing = spacing,
                leftWidth = leftFixed + space,
                rightWidth = rightFixed + space,
                leftSpaceWidth = space,
                rightSpaceWidth = space,
            )
        }
    }
}
