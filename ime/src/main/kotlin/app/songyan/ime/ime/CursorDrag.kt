package app.songyan.ime.ime

/**
 * Caret movement while dragging on the space bar.
 *
 * One character costs [STEP_DP] of travel (a comfortable thumb sweep covers a line, not a word).
 * A fast drag costs [FAST_STEP_DP] per character instead, so a long jump does not need three
 * swipes. Speed only changes the travel per character, never the characters per step: the caret
 * always moves exactly as far as the finger went, however the platform batched the touch events.
 * Pure arithmetic so the feel is unit-tested.
 */
internal object CursorDrag {
    /** Horizontal finger travel for one character (dp). */
    const val STEP_DP = 24f

    /** Travel for one character while the drag is fast (dp). */
    const val FAST_STEP_DP = 12f

    /** A drag becomes fast above this speed (dp per ms) ... */
    const val FAST_SPEED = 1f

    /** ... and slow again below this one, so a speed near the threshold does not flicker. */
    const val SLOW_SPEED = 0.5f

    /** Caret ticks are throttled to one per this long (ms), so fast drags do not buzz. */
    const val TICK_INTERVAL_MS = 30L

    /** Whole steps of travel in [travel] px, truncated towards zero. */
    fun wholeSteps(
        travel: Float,
        stepPx: Float,
    ): Int = (travel / stepPx).toInt()

    /** Whether the drag is fast after a move of [distanceDp] over [elapsedMs], given whether it [was]. */
    fun fast(
        was: Boolean,
        distanceDp: Float,
        elapsedMs: Long,
    ): Boolean {
        if (elapsedMs <= 0L) return was
        val speed = distanceDp / elapsedMs
        return if (was) speed >= SLOW_SPEED else speed > FAST_SPEED
    }

    /** Whether a haptic tick is due now, given the last tick's time. */
    fun tickDue(
        now: Long,
        lastTick: Long,
    ): Boolean = now - lastTick >= TICK_INTERVAL_MS
}
