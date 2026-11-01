package app.songyan.ime.layout

/**
 * Horizontal swipe on a two-letter key: left picks the left letter, right the right one.
 *
 * A tap finger roll stays well under the commit distance, and the swipe must be mostly sideways
 * when it crosses it (direction lock at 45 degrees), so a slightly sloppy tap never turns into a
 * pick. Once locked, the pick follows the finger: crossing back over the touch-down point picks the
 * other letter, and coming to rest near it returns to a plain tap.
 *
 * Distances are in dp. iOS uses points at the same numeric values (18 / 8); Android dp is the
 * matching unit, so the defaults are unchanged.
 */
class LetterSwipeTracker(
    val letterCount: Int,
) {
    var isLocked: Boolean = false
        private set

    /** The picked letter index; null while the press is still a tap. */
    var choice: Int? = null
        private set

    /**
     * Feeds the finger offset from the touch-down point; returns whether [choice] changed.
     * Non-finite offsets are ignored so a bad pointer sample cannot lock the swipe.
     */
    fun update(
        dx: Double,
        dy: Double,
    ): Boolean {
        if (letterCount <= 1 || !dx.isFinite() || !dy.isFinite()) return false
        if (!isLocked) {
            if (kotlin.math.abs(dx) < LOCK_DISTANCE || kotlin.math.abs(dx) <= kotlin.math.abs(dy)) {
                return false
            }
            isLocked = true
        }
        val next =
            when {
                kotlin.math.abs(dx) < RETURN_DISTANCE -> null
                dx < 0 -> 0
                else -> letterCount - 1
            }
        if (next == choice) return false
        choice = next
        return true
    }

    companion object {
        /**
         * Sideways travel that locks the swipe, in dp. Within the 15-20 range keyboards use for
         * flick input, and past the few dp a finger rolls during a tap.
         */
        const val LOCK_DISTANCE: Double = 18.0

        /** Back within this distance of the touch-down point, the press is a tap again. */
        const val RETURN_DISTANCE: Double = 8.0
    }
}
