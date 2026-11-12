package app.songyan.ime.candidate

/** One-handed keyboard choice: which side the key content hugs, if any. */
enum class KeyboardOneHandMode(
    val wire: String,
) {
    OFF("off"),

    /** Content hugs the left edge; the restore gutter sits on the right. */
    LEFT("left"),

    /** Content hugs the right edge; the restore gutter sits on the left. */
    RIGHT("right"), ;

    companion object {
        /** Unknown or missing stored values resolve to [OFF]. */
        fun resolve(storedValue: String?): KeyboardOneHandMode = entries.firstOrNull { it.wire == storedValue } ?: OFF
    }
}

/** The narrowed layout: only the laid-out width shrinks, with no scale transform. */
data class KeyboardOneHandPlan(
    /** false = lay out exactly as before, full width. */
    val active: Boolean,
    /** Actual content width when active (the full panel width when not). */
    val contentWidth: Double,
    /** Actual blank gutter width when active (0 when not). */
    val gutterWidth: Double,
    /** Whether the blank gutter sits on the left (right-handed mode) or the right (left-handed mode). */
    val gutterOnLeft: Boolean,
) {
    companion object {
        fun inactive(panelWidth: Double): KeyboardOneHandPlan =
            KeyboardOneHandPlan(active = false, contentWidth = panelWidth, gutterWidth = 0.0, gutterOnLeft = false)
    }
}

/**
 * Decides when one-handed narrowing applies and the widths it produces.
 *
 * Only narrow, tall phone surfaces offer narrowing; device idiom alone cannot distinguish a folded
 * phone from its expanded or multitasking surface, so the caller supplies the window facts.
 */
object KeyboardOneHandLayoutPolicy {
    /** The narrowed keyboard's width the user can pick, in percent of the panel width. */
    const val MINIMUM_WIDTH_PERCENT: Int = 60
    const val MAXIMUM_WIDTH_PERCENT: Int = 90
    const val DEFAULT_WIDTH_PERCENT: Int = 85

    /** Out-of-range values snap into the offered choices. */
    fun clampedWidthPercent(value: Int): Int = value.coerceIn(MINIMUM_WIDTH_PERCENT, MAXIMUM_WIDTH_PERCENT)

    /**
     * Lower bound of the narrowed content so alphabetic keys stay usable: ten columns of about
     * 29 dp, close to what a 360 dp phone's keys get with the usual 85% narrowing.
     */
    const val MINIMUM_CONTENT_WIDTH: Double = 288.0

    /** The gutter carries the restore control, which needs a 44 dp target. */
    const val MINIMUM_GUTTER_WIDTH: Double = 44.0

    /**
     * The narrowest panel that still fits the content floor and the gutter. Android phones are
     * commonly 360 dp wide, and less at a larger display size, so an iPhone-sized floor would hide
     * the mode on most of them.
     */
    const val MINIMUM_PANEL_WIDTH: Double = MINIMUM_CONTENT_WIDTH + MINIMUM_GUTTER_WIDTH

    /** Keep narrowing within phone-sized surfaces, even before the size classes update. */
    const val MAXIMUM_PANEL_WIDTH: Double = 600.0

    fun isAvailable(
        panelWidth: Double,
        isPhone: Boolean,
        compactHeight: Boolean,
        regularWidth: Boolean,
    ): Boolean =
        isPhone &&
            !compactHeight &&
            !regularWidth &&
            panelWidth >= MINIMUM_PANEL_WIDTH &&
            panelWidth <= MAXIMUM_PANEL_WIDTH

    fun plan(
        mode: KeyboardOneHandMode,
        panelWidth: Double,
        isPhonePortrait: Boolean,
        widthPercent: Int = DEFAULT_WIDTH_PERCENT,
    ): KeyboardOneHandPlan {
        // Normalize once, like the wide-content policy: a NaN width would pass both range checks
        // (comparisons with NaN are false) and produce an active plan with NaN geometry.
        val width = KeyboardWideContentPolicy.normalized(panelWidth)
        if (mode == KeyboardOneHandMode.OFF ||
            !isPhonePortrait ||
            width < MINIMUM_PANEL_WIDTH ||
            width > MAXIMUM_PANEL_WIDTH
        ) {
            return KeyboardOneHandPlan.inactive(width)
        }
        val ratio = clampedWidthPercent(widthPercent) / 100.0
        // A wide choice still leaves the gutter its controls' room instead of switching the mode off.
        val content =
            minOf(maxOf(MINIMUM_CONTENT_WIDTH, kotlin.math.floor(width * ratio)), width - MINIMUM_GUTTER_WIDTH)
        val gutter = width - content
        if (gutter < MINIMUM_GUTTER_WIDTH) return KeyboardOneHandPlan.inactive(width)
        return KeyboardOneHandPlan(
            active = true,
            contentWidth = content,
            gutterWidth = gutter,
            gutterOnLeft = mode == KeyboardOneHandMode.RIGHT,
        )
    }
}

/**
 * Where a width-capped keyboard content sits on a surface wider than its cap, as the share of the
 * free space that lies before it: 0 is flush left, 50 centred, 100 flush right.
 */
@JvmInline
value class KeyboardContentDock private constructor(
    val percent: Int,
) {
    companion object {
        const val MINIMUM_PERCENT = 0
        const val MAXIMUM_PERCENT = 100
        val LEFT = KeyboardContentDock(MINIMUM_PERCENT)
        val CENTER = KeyboardContentDock(50)
        val RIGHT = KeyboardContentDock(MAXIMUM_PERCENT)

        /** Out-of-range values clamp to the nearest edge. */
        fun of(percent: Int): KeyboardContentDock =
            KeyboardContentDock(percent.coerceIn(MINIMUM_PERCENT, MAXIMUM_PERCENT))
    }
}

/**
 * The laid-out width and side insets for a wide surface. No scale factor: callers assign
 * [contentWidth] as a real frame width and let the insets fill the rest.
 */
data class KeyboardWideContentPlan(
    /** false means the content keeps the full panel width and both insets are zero. */
    val active: Boolean,
    /** Real width to lay out with; finite and never above the panel width. */
    val contentWidth: Double,
    /** Blank width before the content; finite and non-negative. */
    val leadingInset: Double,
    /** Blank width after the content; finite and non-negative. */
    val trailingInset: Double,
) {
    companion object {
        fun inactive(panelWidth: Double): KeyboardWideContentPlan {
            val width = KeyboardWideContentPolicy.normalized(panelWidth)
            return KeyboardWideContentPlan(
                active = false,
                contentWidth = width,
                leadingInset = 0.0,
                trailingInset = 0.0,
            )
        }
    }
}

/**
 * Keeps wide layouts from stretching: above a phone-sized surface the content is capped and docked
 * anywhere from flush left to flush right. The policy only does geometry - layout identity, split, height, preferences
 * and system gestures stay with their owners.
 */
object KeyboardWideContentPolicy {
    /** Phone-sized surfaces keep their full-width geometry. */
    const val MINIMUM_ACTIVE_PANEL_WIDTH: Double = 600.0

    /**
     * The 9-key outer frame keeps the inner 436 dp phone shell intact with a 4 dp gutter per side
     * (436 + 2 * 4 = 444); the reconcile is left to the layout that draws the shell.
     */
    const val PHONE_NINE_MAXIMUM_CONTENT_WIDTH: Double = 444.0

    /** The merged 14/18-key letter pads read as one unit on a wide surface without stretching. */
    const val MERGED_MAXIMUM_CONTENT_WIDTH: Double = 520.0

    /**
     * Real content width and insets for this surface. A null or oversized cap, a phone-sized surface
     * and malformed widths all stay inactive at full width.
     */
    fun plan(
        panelWidth: Double,
        maximumContentWidth: Double?,
        dock: KeyboardContentDock,
    ): KeyboardWideContentPlan {
        val width = normalized(panelWidth)
        if (width <= MINIMUM_ACTIVE_PANEL_WIDTH ||
            maximumContentWidth == null ||
            !maximumContentWidth.isFinite() ||
            maximumContentWidth <= 0.0 ||
            maximumContentWidth >= width
        ) {
            return KeyboardWideContentPlan.inactive(width)
        }
        val remaining = width - maximumContentWidth
        val leading = remaining * dock.percent / KeyboardContentDock.MAXIMUM_PERCENT
        val trailing = remaining - leading
        return KeyboardWideContentPlan(
            active = true,
            contentWidth = maximumContentWidth,
            leadingInset = leading,
            trailingInset = trailing,
        )
    }

    /** Real UI widths may be negative or non-finite right after a resize; normalize exactly once. */
    fun normalized(width: Double): Double = if (width.isFinite()) maxOf(0.0, width) else 0.0
}
