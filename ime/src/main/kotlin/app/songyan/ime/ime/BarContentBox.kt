package app.songyan.ime.ime

import kotlin.math.max
import kotlin.math.min

/**
 * The one vertical rule for everything in the candidate bar's row: candidates, toolbar icons, the
 * copied-text chip, notices, inline readings and the system's inline autofill chips. Each of them is
 * centred on [Box.centreDp], computed here from the row's real height, so a new kind of content can
 * not drift from the others. Views draw or place themselves through it and report their centre via
 * [BarContent]; the autofill height requested from the system is [Box.heightDp].
 *
 * From the iOS candidate-area geometry: with no reading row the band keeps more space above its
 * content than below (9 against 3, so 3 below centre), closer to the keys it was typed on; under a
 * reading row it lifts half a point (2 above, 3 below) to keep its distance from the readings.
 */
internal object BarContentBox {
    const val DROP_DP = 3f
    const val DROP_UNDER_READINGS_DP = -0.5f

    /** How far content sits below the row's geometric centre. */
    fun dropDp(underReadingRow: Boolean): Float = if (underReadingRow) DROP_UNDER_READINGS_DP else DROP_DP

    /** The content box inside a row: [topDp] from the row's top, [heightDp] tall, centred on [centreDp]. */
    data class Box(
        val rowDp: Float,
        val topDp: Float,
        val heightDp: Float,
    ) {
        val centreDp: Float get() = topDp + heightDp / 2f
        val bottomInsetDp: Float get() = rowDp - topDp - heightDp
    }

    /** The box for a bar of [barDp] (shrunk to [readingRowDp] while a reading row sits above). */
    fun of(
        barDp: Float,
        readingRowDp: Float?,
    ): Box {
        val row = ReadingRowBudget.bandDp(barDp, readingRowDp)
        val drop = dropDp(readingRowDp != null)
        val top = min(row, max(0f, 2 * drop))
        val bottom = min(row - top, max(0f, -2 * drop))
        return Box(row, top, max(0f, row - top - bottom))
    }
}

/**
 * A view of the bar's row. [contentCentreY] is where its content is vertically centred, in px from
 * the view's own top. The band only accepts views of this type, so none can skip the shared rule
 * ([BarContentBox]); a device test compares every one to the rule.
 */
interface BarContent {
    fun contentCentreY(): Float
}
