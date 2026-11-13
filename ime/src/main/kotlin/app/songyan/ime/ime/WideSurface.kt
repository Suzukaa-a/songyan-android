package app.songyan.ime.ime

import app.songyan.ime.candidate.KeyboardContentDock
import app.songyan.ime.candidate.KeyboardWideContentPlan
import app.songyan.ime.candidate.KeyboardWideContentPolicy
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.layout.LayoutSplitConfiguration

/**
 * Whether the keyboard splits into two halves on a wide surface. A layout carries its own split
 * (the merged pads split automatically); this setting can switch splitting on for every layout or off
 * for all of them.
 */
enum class SplitMode(
    val wire: String,
) {
    /** Each layout's own setting decides. */
    LAYOUT("layout"),

    /** Every layout splits on wide surfaces, at its own row cuts when it has them. */
    ON("on"),

    /** No layout splits. */
    OFF("off"),
    ;

    companion object {
        fun resolve(stored: String?): SplitMode = entries.firstOrNull { it.wire == stored } ?: LAYOUT
    }
}

/**
 * Pure geometry of the wide-surface keyboard: how wide the key area is, where it sits, and which
 * split applies. Kept free of views so the rules are unit-tested off-device.
 */
internal object WideSurface {
    /** A surface at most this wide (dp) is phone-sized: full width, never split by the automatic rule. */
    const val PHONE_MAXIMUM_DP = 600

    /**
     * The widest an unsplit key area of [kind] grows (dp): the nine-key shell and the merged pads read
     * as one unit and stop growing; the full keyboard keeps stretching, its side margin is what keeps
     * the keys within reach. The iOS keyboard caps the same layouts.
     */
    fun contentCapDp(kind: KeyboardLayoutKind): Double =
        when (kind) {
            KeyboardLayoutKind.PHONE9 -> KeyboardWideContentPolicy.PHONE_NINE_MAXIMUM_CONTENT_WIDTH
            KeyboardLayoutKind.MERGED14, KeyboardLayoutKind.MERGED18 ->
                KeyboardWideContentPolicy.MERGED_MAXIMUM_CONTENT_WIDTH
            KeyboardLayoutKind.QWERTY26 -> Double.POSITIVE_INFINITY
        }

    /** The key area never gets narrower than this (dp), whatever side margin is chosen. */
    const val MINIMUM_KEY_AREA_DP = 280.0

    /**
     * The blank margin (dp) kept on each side of a [surfaceDp] wide surface for a [perMille] share of
     * its width. Only a large surface, one wider than [largeAboveDp] (the user's margin width), gets one:
     * a margin is for keeping keys within thumb reach on a wide screen, and a phone needs all of its
     * width. It is cut so the key area keeps [MINIMUM_KEY_AREA_DP].
     */
    fun sideMargin(
        surfaceDp: Double,
        perMille: Int,
        largeAboveDp: Double = LayoutSplitConfiguration.AUTOMATIC_MAXIMUM_WIDTH,
    ): Double {
        if (!surfaceDp.isFinite() || surfaceDp <= largeAboveDp) return 0.0
        return (surfaceDp * perMille / 1000).coerceAtMost((surfaceDp - MINIMUM_KEY_AREA_DP) / 2).coerceAtLeast(0.0)
    }

    /** The split that applies to a layout's own [split] under [mode], or null for an unsplit keyboard. */
    fun effectiveSplit(
        split: LayoutSplitConfiguration?,
        mode: SplitMode,
    ): LayoutSplitConfiguration? =
        when (mode) {
            SplitMode.OFF -> null
            SplitMode.LAYOUT -> split
            SplitMode.ON ->
                when {
                    split == null -> LayoutSplitConfiguration()
                    split.activation == LayoutSplitConfiguration.Activation.OFF ->
                        split.copy(activation = LayoutSplitConfiguration.Activation.AUTOMATIC)
                    else -> split
                }
        }

    /**
     * The key area on a [surfaceDp] wide surface. A split keyboard takes the whole surface (its halves
     * stay within thumb reach through the gutter); an unsplit one is capped and docked. Whether the
     * surface splits is judged on its full width against [automaticAboveDp], and whether it gets the
     * side margin against [marginAboveDp], so a margin never undoes the split; the cap then sees the
     * width left after the margin, and the insets include it.
     */
    fun plan(
        surfaceDp: Double,
        split: LayoutSplitConfiguration?,
        dock: KeyboardContentDock,
        capDp: Double = Double.POSITIVE_INFINITY,
        automaticAboveDp: Double = LayoutSplitConfiguration.AUTOMATIC_MAXIMUM_WIDTH,
        sideMarginPerMille: Int = 0,
        marginAboveDp: Double = automaticAboveDp,
    ): KeyboardWideContentPlan {
        val margin = sideMargin(surfaceDp, sideMarginPerMille, marginAboveDp)
        val inner = surfaceDp - 2 * margin
        val plan =
            if (split?.isActive(surfaceDp, automaticAboveDp) == true) {
                KeyboardWideContentPlan.inactive(inner)
            } else {
                KeyboardWideContentPolicy.plan(inner, capDp, dock)
            }
        if (margin == 0.0) return plan
        return plan.copy(
            active = true,
            leadingInset = plan.leadingInset + margin,
            trailingInset = plan.trailingInset + margin,
        )
    }
}
