package app.songyan.ime.settings.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/**
 * Page transitions between settings screens: the Material shared-axis X pattern. Both pages slide
 * together over the same time, and the outgoing page has faded out before the incoming one fades
 * in, so the two never show through each other. A predictive back gesture seeks this same
 * transition, so the curves must look right at every point, not only when played through. Compose
 * scales durations with the system animation scale, so with animations off pages change at once.
 */
object SettingsMotion {
    const val DURATION_MS = 300

    /** The outgoing page is gone after this share of the transition; the incoming one fades in after it. */
    const val FADE_OUT_MS = DURATION_MS * 35 / 100

    /** How far a page travels, in dp. */
    const val SLIDE_DP = 30

    /** A page arriving: slides in from the direction it travels and fades in once the old page is gone. */
    fun enter(
        slidePx: Int,
        forward: Boolean,
    ): EnterTransition =
        slideInHorizontally(tween(DURATION_MS, easing = FastOutSlowInEasing)) { if (forward) slidePx else -slidePx } +
            fadeIn(tween(DURATION_MS - FADE_OUT_MS, delayMillis = FADE_OUT_MS, easing = LinearOutSlowInEasing))

    /** A page leaving: slides with the arriving page and fades out early. */
    fun exit(
        slidePx: Int,
        forward: Boolean,
    ): ExitTransition =
        slideOutHorizontally(tween(DURATION_MS, easing = FastOutSlowInEasing)) { if (forward) -slidePx else slidePx } +
            fadeOut(tween(FADE_OUT_MS, easing = FastOutLinearInEasing))
}
