package app.songyan.ime.ime

import kotlin.math.abs

/**
 * Swipe left on Delete: the finger's travel decides how many characters before the caret are
 * selected. Pure arithmetic over the same step sizes as the space bar's caret drag, so the two
 * gestures feel alike and the feel is unit-tested.
 */
internal object DeleteSwipe {
    /** Whether a move of ([dxDp], [dyDp]) from the press point turns the held Delete into a swipe. */
    fun starts(
        dxDp: Float,
        dyDp: Float,
    ): Boolean = dxDp <= -KeyboardView.CURSOR_START_DP && abs(dxDp) > abs(dyDp)

    /**
     * [count] characters are selected; [anchorX] is the x the next step is measured from. It follows
     * the finger even when the editor cannot select more, so turning back reacts on the next step.
     */
    data class State(
        val count: Int,
        val anchorX: Float,
    )

    /** The state after the finger moved to [x]; [startX] is the press point and [startPx] the dead zone. */
    fun move(
        state: State,
        x: Float,
        startX: Float,
        startPx: Float,
        stepPx: Float,
        max: Int,
    ): State {
        // Back within the dead zone (or right of the press) selects nothing and restarts the count.
        if (x >= startX - startPx) return State(0, startX)
        val steps = CursorDrag.wholeSteps(state.anchorX - x, stepPx)
        return State((state.count + steps).coerceIn(0, max), state.anchorX - steps * stepPx)
    }
}
