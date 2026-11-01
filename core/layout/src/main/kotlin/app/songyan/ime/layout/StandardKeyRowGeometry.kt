package app.songyan.ime.layout

/** Character rows share ten columns; controls absorb the remaining third-row width. */
data class StandardKeyRowGeometry(
    val gap: Double,
    val keyWidth: Double,
    val thirdRowSideWidth: Double,
) {
    companion object {
        fun resolve(
            width: Double,
            split: LayoutSplitConfiguration? = null,
            spacing: Double = 4.0,
        ): StandardKeyRowGeometry {
            val safeWidth = maxOf(0.0, width)
            val gap = split?.gap(safeWidth, keyCount = 10, spacing = spacing) ?: 0.0
            val keyWidth = maxOf(0.0, (safeWidth - gap - spacing * 9) / 10.0)
            val thirdRowSideWidth = maxOf(0.0, (safeWidth - gap - keyWidth * 7 - spacing * 8) / 2.0)
            return StandardKeyRowGeometry(gap, keyWidth, thirdRowSideWidth)
        }
    }
}
