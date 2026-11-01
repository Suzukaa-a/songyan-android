package app.songyan.ime.ime

import app.songyan.ime.candidate.KeyboardContentDock
import app.songyan.ime.candidate.KeyboardWideContentPlan
import app.songyan.ime.candidate.KeyboardWideContentPolicy
import app.songyan.ime.layout.LayoutSplitConfiguration

/** Where the key area sits on a surface wider than [WideSurface.PHONE_MAXIMUM_DP]. */
enum class KeyboardDock(
    val wire: String,
    internal val policy: KeyboardContentDock,
) {
    LEFT("left", KeyboardContentDock.LEFT),
    CENTER("center", KeyboardContentDock.CENTER),
    RIGHT("right", KeyboardContentDock.RIGHT),
    ;

    companion object {
        /** Unknown or missing stored values resolve to the centre. */
        fun resolve(stored: String?): KeyboardDock = entries.firstOrNull { it.wire == stored } ?: CENTER
    }
}

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

    /** The key area never grows wider than this when it is not split (dp). */
    const val CONTENT_MAXIMUM_DP = 600.0

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
     * stay within thumb reach through the gutter); an unsplit one is capped and docked.
     */
    fun plan(
        surfaceDp: Double,
        split: LayoutSplitConfiguration?,
        dock: KeyboardDock,
        capDp: Double = CONTENT_MAXIMUM_DP,
    ): KeyboardWideContentPlan {
        if (split?.isActive(surfaceDp) == true) return KeyboardWideContentPlan.inactive(surfaceDp)
        return KeyboardWideContentPolicy.plan(surfaceDp, capDp, dock.policy)
    }
}
