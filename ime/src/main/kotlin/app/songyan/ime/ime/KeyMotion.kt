package app.songyan.ime.ime

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A damped spring of unit mass, evaluated in closed form.
 *
 * The keyboard draws on a canvas and samples motion at frame time, so the spring is a function of
 * elapsed time instead of an animator object per key: no allocation on the touch path, and the same
 * numbers drive drawing and unit tests. [stiffness] and [dampingRatio] have the meaning they have in
 * `androidx.dynamicanimation.SpringForce`.
 */
internal class KeySpring(
    private val stiffness: Float,
    private val dampingRatio: Float,
) {
    private val omega = sqrt(stiffness)

    /**
     * Remaining displacement from the rest position after [seconds], starting at rest with an
     * initial displacement of [start] (so 1 -> 0 for a unit step).
     */
    fun displacement(
        seconds: Float,
        start: Float = 1f,
    ): Float {
        if (seconds <= 0f) return start
        val decay = dampingRatio * omega
        return if (dampingRatio >= 1f) {
            start * (1f + omega * seconds) * exp(-omega * seconds)
        } else {
            val damped = omega * sqrt(1f - dampingRatio * dampingRatio)
            start * exp(-decay * seconds) * (cos(damped * seconds) + decay / damped * sin(damped * seconds))
        }
    }

    /** Value of a 0 -> 1 step response after [seconds]; it overshoots 1 when under-damped. */
    fun step(seconds: Float): Float = 1f - displacement(seconds)

    /** Time after which the spring is within [tolerance] of rest and needs no more frames (ms). */
    val settleMs: Long =
        run {
            var milliseconds = 0L
            // The envelope decays monotonically, so the first time it is small enough is final.
            val envelopeTolerance = TOLERANCE
            while (milliseconds < MAXIMUM_MS && envelope(milliseconds / 1000f) > envelopeTolerance) milliseconds += 1
            milliseconds
        }

    private fun envelope(seconds: Float): Float {
        val decay = dampingRatio * omega
        return if (dampingRatio >= 1f) {
            (1f + omega * seconds) * exp(-omega * seconds)
        } else {
            val damped = omega * sqrt(1f - dampingRatio * dampingRatio)
            exp(-decay * seconds) * sqrt(1f + (decay / damped) * (decay / damped))
        }
    }

    private companion object {
        const val TOLERANCE = 0.01f
        const val MAXIMUM_MS = 2_000L
    }
}

/**
 * The keyboard's motion parameters. Pure math, so the curves are unit-tested and the view only
 * reads numbers; animating the drawn keycap never delays dispatch or moves a touch target.
 *
 * Every time here is in milliseconds at animator duration scale 1; [duration] and the functions
 * that take a `durationScale` stretch them, and a scale of 0 jumps to the final state.
 */
internal object KeyMotion {
    /** A pressed keycap is at this scale the instant the finger lands: no fade-in. */
    const val PRESSED_SCALE = 0.96f

    /** Release spring: critically damped, so the keycap settles without bouncing. */
    private val releaseSpring = KeySpring(stiffness = 1500f, dampingRatio = 1f)

    /** The pressed colour fades out over this long after release (ms). */
    const val RELEASE_COLOR_MS = 120L

    /** Popup open: a lightly under-damped spring, so the popup settles with a small overshoot. */
    private val popupSpring = KeySpring(stiffness = 800f, dampingRatio = 0.8f)

    /** Popup close, after a commit or a cancel (ms). */
    const val POPUP_CLOSE_MS = 80L

    /** The highlighted cell of an open popup cross-fades over this long (ms). */
    const val POPUP_SELECT_MS = 50L

    /** A key preview stays this long after the finger lifts (ms). */
    const val PREVIEW_HOLD_MS = 40L

    /** Popup starts at this scale and springs to 1. */
    const val POPUP_START_SCALE = 0.92f

    /** How far the popup shrinks while closing. */
    private const val POPUP_CLOSE_SHRINK = 0.04f

    /** Effective duration of [baseMs] at the current animator duration scale. */
    fun duration(
        baseMs: Long,
        durationScale: Float,
    ): Float = (baseMs * durationScale).coerceAtLeast(1f)

    /** Time in motion-scale-adjusted seconds; a scale of 0 means every animation is finished. */
    private fun seconds(
        elapsedMs: Long,
        durationScale: Float,
    ): Float = elapsedMs / 1000f / durationScale

    /** The scale of a held keycap: pressed immediately, with no easing in. */
    fun pressScale(): Float = PRESSED_SCALE

    /** Springs back to 1 after the finger lifts; 1 when no release is pending. */
    fun releaseScale(
        now: Long,
        releasedAt: Long,
        durationScale: Float,
    ): Float {
        if (releasedAt <= 0L || durationScale <= 0f) return 1f
        return 1f - (1f - PRESSED_SCALE) * releaseSpring.displacement(seconds(now - releasedAt, durationScale))
    }

    /** Whether a release at [releasedAt] still needs frames (scale spring or colour fade). */
    fun releaseAnimating(
        now: Long,
        releasedAt: Long,
        durationScale: Float,
    ): Boolean {
        if (releasedAt <= 0L || durationScale <= 0f) return false
        val budget = maxOf(releaseSpring.settleMs, RELEASE_COLOR_MS) * durationScale
        return now - releasedAt < budget
    }

    /** 1 right after release, easing out to 0: how much of the pressed colour is left. */
    fun releaseColor(
        now: Long,
        releasedAt: Long,
        durationScale: Float,
    ): Float {
        if (releasedAt <= 0L || durationScale <= 0f) return 0f
        val t = ((now - releasedAt) / duration(RELEASE_COLOR_MS, durationScale)).coerceIn(0f, 1f)
        return (1f - t) * (1f - t)
    }

    /** Open-spring value, 0 at the start and settling on 1 (overshooting a little on the way). */
    fun popupOpenValue(
        now: Long,
        openedAt: Long,
        durationScale: Float,
    ): Float {
        if (durationScale <= 0f || openedAt <= 0L) return 1f
        return popupSpring.step(seconds(now - openedAt, durationScale))
    }

    /** Whether the open spring still needs frames. */
    fun popupOpening(
        now: Long,
        openedAt: Long,
        durationScale: Float,
    ): Boolean = durationScale > 0f && openedAt > 0L && now - openedAt < popupSpring.settleMs * durationScale

    /** 0..1 popup close progress. */
    fun popupCloseProgress(
        now: Long,
        closedAt: Long,
        durationScale: Float,
    ): Float {
        if (durationScale <= 0f || closedAt <= 0L) return 0f
        val t = ((now - closedAt) / duration(POPUP_CLOSE_MS, durationScale)).coerceIn(0f, 1f)
        // Ease-out: the popup leaves quickly and does not linger.
        return 1f - (1f - t) * (1f - t)
    }

    /** Popup scale for an open-spring value. */
    fun popupScale(value: Float): Float = POPUP_START_SCALE + (1f - POPUP_START_SCALE) * value

    /** Popup opacity for an open-spring value; the fade finishes before the scale settles. */
    fun popupAlpha(value: Float): Float = (value * 2f).coerceIn(0f, 1f)

    /** Popup opacity for a close progress. */
    fun popupCloseAlpha(progress: Float): Float = (1f - progress).coerceIn(0f, 1f)

    /** Popup scale for a close progress. */
    fun popupCloseScale(progress: Float): Float = 1f - POPUP_CLOSE_SHRINK * progress

    /** 0..1 cross-fade progress of a changed popup highlight. */
    fun highlightProgress(
        now: Long,
        changedAt: Long,
        durationScale: Float,
    ): Float {
        if (durationScale <= 0f || changedAt <= 0L) return 1f
        return ((now - changedAt) / duration(POPUP_SELECT_MS, durationScale)).coerceIn(0f, 1f)
    }

    /** Frames the open spring needs, for tests and for the layer's idle detection (ms). */
    val popupSettleMs: Long get() = popupSpring.settleMs

    val releaseSettleMs: Long get() = releaseSpring.settleMs
}
