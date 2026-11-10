package app.songyan.ime.ime

import android.animation.ValueAnimator
import android.os.Build
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import androidx.dynamicanimation.animation.SpringForce

/**
 * The keyboard's few transitions: the candidate bar's expansion, panel show/hide and the keys
 * fading in after a keyboard switch.
 *
 * Every transition follows a state change that already happened, so nothing here delays dispatch,
 * key feedback or input: it only animates what is already on screen. When the system asks for no
 * animations (animator duration scale 0, reduced motion) each caller applies the final state
 * immediately - an animation is decoration, never evidence that input was accepted.
 *
 * Two kinds of animation exist here and they scale differently:
 *  - Platform-driven ones ([android.animation.ValueAnimator], [android.view.ViewPropertyAnimator]):
 *    the platform already multiplies their duration by the system animator duration scale, so they
 *    take the base [CANDIDATE_MS] / [PANEL_MS] / [SWITCH_MS] and must NOT pass through [duration].
 *    AndroidX spring animations likewise scale their frame deltas on API 33+, so their physical
 *    response must stay unchanged; changing stiffness would apply the speed twice.
 *  - Canvas-driven progress (a value read while drawing, e.g. the key press release): no platform
 *    scaler touches it, so it multiplies its own frame budget by [duration].
 */
object ImeMotion {
    /** Base candidate bar reveal time; a platform animator already scales it. */
    const val CANDIDATE_MS: Long = 180

    /** Base quick-content panel show/hide time; a platform animator already scales it. */
    const val PANEL_MS: Long = 140

    /** Base fade-in of the keys after a keyboard switch; a platform animator already scales it. */
    const val SWITCH_MS: Long = 180

    /** The keys start the switch fade faint, not invisible, the same as on iOS. */
    const val SWITCH_FROM_ALPHA = 0.15f

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
     * AndroidX 1.1 applies the system duration scale to spring frame deltas on API 33+. Keep the
     * physical response unchanged so the candidate reveal follows that scale exactly once.
     * Older platforms use the same default response; callers skip the spring when motion is off.
     */
    fun revealSpring(finalPosition: Float): SpringForce =
        SpringForce(finalPosition)
            .setDampingRatio(REVEAL_DAMPING)
            .setStiffness(REVEAL_STIFFNESS)

    private const val REVEAL_DAMPING: Float = 0.9f
    private const val REVEAL_STIFFNESS: Float = 800f
}
