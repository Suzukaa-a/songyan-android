package app.songyan.ime.ime

import android.animation.ValueAnimator
import android.os.Build
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import androidx.dynamicanimation.animation.SpringForce

/**
 * The keyboard's few transitions: the candidate bar's expansion, panel show/hide and the page
 * content fade.
 *
 * Every transition follows a state change that already happened, so nothing here delays dispatch,
 * key feedback or input: it only animates what is already on screen. When the system asks for no
 * animations (animator duration scale 0, reduced motion) each caller applies the final state
 * immediately - an animation is decoration, never evidence that input was accepted.
 *
 * Two kinds of animation exist here and they scale differently:
 *  - Platform-driven ones ([android.animation.ValueAnimator], [android.view.ViewPropertyAnimator]):
 *    the platform already multiplies their duration by the system animator duration scale, so they
 *    take the base [CANDIDATE_MS] / [PANEL_MS] / [PAGE_MS] and must NOT pass through [duration] -
 *    doing so would scale them twice (scale 0.5 would run at 0.25).
 *  - Canvas-driven progress (a value read while drawing, e.g. the key press release): no platform
 *    scaler touches it, so it multiplies its own frame budget by [duration].
 */
object ImeMotion {
    /** Base candidate bar reveal time; a platform animator already scales it. */
    const val CANDIDATE_MS: Long = 180

    /** Base quick-content panel show/hide time; a platform animator already scales it. */
    const val PANEL_MS: Long = 140

    /** Base page-swap fade time; a platform animator already scales it. */
    const val PAGE_MS: Long = 100

    /**
     * Partial animator scales are only readable from API 33; older platforms only expose the
     * all-or-nothing [ValueAnimator.areAnimatorsEnabled], so there a reduced motion setting still
     * halves the duration but Android 8-12 can only report 1 or 0.
     */
    private const val PARTIAL_SCALE_API = 33

    /** Tests set this to exercise [scale] and [duration] without an Android runtime. */
    internal var scaleOverride: Float? = null

    /**
     * The system's animator duration scale: 0 removes animations, 0.5 halves every duration, 1 is
     * normal. Callers that draw their own progress multiply by it; platform animators already do.
     */
    val scale: Float
        get() = scaleOverride ?: readScale()

    private fun readScale(): Float {
        if (Build.VERSION.SDK_INT >= PARTIAL_SCALE_API) {
            // Some platforms report a negative value before the setting is first read.
            val value = ValueAnimator.getDurationScale()
            return if (value >= 0f) value else 1f
        }
        return if (ValueAnimator.areAnimatorsEnabled()) 1f else 0f
    }

    /** False when the system asks for no animations at all. */
    val enabled: Boolean get() = scale > 0f

    /**
     * [baseMs] scaled for a CANVAS-DRIVEN progress value, never below one millisecond.
     *
     * Do not pass this to a ValueAnimator or ViewPropertyAnimator: the platform scales those by the
     * same system setting already, so they would run at the square of the user's scale.
     */
    fun duration(baseMs: Long): Long = (baseMs * scale).toLong().coerceAtLeast(1L)

    /** Fast-out-slow-in: the platform's shape for a surface that stays contained. */
    val standard: Interpolator get() = PathInterpolator(0.2f, 0f, 0f, 1f)

    /**
     * The spring of a contained reveal (the expanded candidate grid): damping ratio 0.9, stiffness
     * 800. A physics-driven animation is not scaled by the platform, so the system animator scale
     * is applied here: a slower system makes the spring proportionally softer.
     */
    fun revealSpring(finalPosition: Float): SpringForce =
        SpringForce(finalPosition)
            .setDampingRatio(REVEAL_DAMPING)
            .setStiffness(REVEAL_STIFFNESS / (scale * scale).coerceAtLeast(MIN_SPRING_SCALE))

    private const val REVEAL_DAMPING: Float = 0.9f
    private const val REVEAL_STIFFNESS: Float = 800f
    private const val MIN_SPRING_SCALE: Float = 0.01f
}
