package app.songyan.ime.ime

import android.content.Context
import android.content.res.Resources
import kotlin.math.min

/** The row pitch the user picks for the portrait keyboard; the wire value is what is stored. */
enum class KeyHeight(
    val wire: String,
    val pitchDp: Float,
) {
    COMPACT("compact", 56f),
    STANDARD("standard", 60f),
    TALL("tall", 64f),
    ;

    companion object {
        val DEFAULT = STANDARD

        /** Unknown or missing stored values resolve to the default. */
        fun resolve(stored: String?): KeyHeight = entries.firstOrNull { it.wire == stored } ?: DEFAULT
    }
}

/**
 * The keyboard's vertical budget. Pure arithmetic so the limits are unit-tested off-device.
 *
 * Portrait: the key area (four rows) follows the chosen pitch and stays inside 200..256 dp. On a
 * window shorter than [FULL_SIZE_WINDOW_DP] the area shrinks toward the
 * floor while the gaps between the presets shrink with it, never below [MIN_PITCH_STEP_DP], so the
 * three choices stay distinct.
 * Landscape and other short windows: rows at [SHORT_ROW_SHARE] of the preset's pitch, so the choice
 * still counts, reduced further only when the keyboard would pass [shortKeyboardMaxDp]: a share of
 * the window that never leaves the app less than [MIN_APP_DP]. Usability wins over that budget on
 * the shortest windows: the rows never go below [SHORT_ROW_MIN_DP], so at about 320 dp and less the
 * keyboard is taller than the budget and the app gets under [MIN_APP_DP].
 */
object KeyboardGeometry {
    /**
     * The shortest portrait window that gets the presets at full size. It is fixed rather than
     * derived from the tallest preset: deriving it moved the threshold up with the presets, so on
     * the common 700–800 dp phones the taller presets were scaled straight back to the old heights.
     */
    private const val FULL_SIZE_WINDOW_DP = 750f
    private const val AREA_MIN_DP = 200f
    private const val AREA_MAX_DP = 256f

    /** The least row pitch difference between two neighbouring presets on a portrait window. */
    private const val MIN_PITCH_STEP_DP = 1.5f

    /** A window at most this tall (dp) is treated as short: phone landscape, split-screen panes. */
    const val SHORT_WINDOW_DP = 520

    /** Landscape keeps its rows as short as before the portrait presets grew, so the reading row still fits. */
    private const val SHORT_ROW_SHARE = 0.75f
    private const val SHORT_ROW_MIN_DP = 32f
    private const val SHORT_WINDOW_SHARE = 0.55f
    private const val MIN_APP_DP = 180f

    /**
     * Below this window height (dp) the platform's landscape fullscreen editor is kept: the compact
     * height window size class (https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes).
     */
    private const val FULLSCREEN_EDITOR_MAX_HEIGHT_DP = 480

    /**
     * Whether the fullscreen editor the platform asks for ([systemWants]: landscape, and the field
     * allows it) is worth it on a window [screenHeightDp] tall. A phone in landscape has no room for
     * the app beside the keyboard; a tablet or an unfolded inner screen does, and covering the app
     * there only hides it.
     */
    fun fullscreenEditor(
        systemWants: Boolean,
        screenHeightDp: Int,
    ): Boolean = systemWants && screenHeightDp in 1 until FULLSCREEN_EDITOR_MAX_HEIGHT_DP

    /** The tallest a keyboard may be on a short window [screenHeightDp] tall (dp). */
    fun shortKeyboardMaxDp(screenHeightDp: Int): Float =
        min(SHORT_WINDOW_SHARE * screenHeightDp, screenHeightDp - MIN_APP_DP)

    /**
     * The portrait key area height for [preset] on a screen [screenHeightDp] tall (dp). The area is
     * the standard preset's share-of-screen height plus the preset's offset from it. Both scale
     * with the window, but the standard area never drops below what keeps the smallest preset at
     * [AREA_MIN_DP], so the offsets are applied on top of the floor and the presets keep their
     * order and at least [MIN_PITCH_STEP_DP] between them on every portrait window.
     */
    fun portraitAreaDp(
        preset: KeyHeight,
        screenHeightDp: Int,
    ): Float {
        val rows = KeyboardDesignTokens.KEY_ROWS
        val scale = if (screenHeightDp > 0) min(1f, screenHeightDp / FULL_SIZE_WINDOW_DP) else 1f
        val offsetScale = maxOf(scale, MIN_PITCH_STEP_DP / (KeyHeight.TALL.pitchDp - KeyHeight.STANDARD.pitchDp))
        val smallestOffset = (KeyHeight.COMPACT.pitchDp - KeyHeight.STANDARD.pitchDp) * rows
        val standard = maxOf(KeyHeight.STANDARD.pitchDp * rows * scale, AREA_MIN_DP - smallestOffset * offsetScale)
        val area = standard + (preset.pitchDp - KeyHeight.STANDARD.pitchDp) * rows * offsetScale
        return area.coerceIn(AREA_MIN_DP, AREA_MAX_DP)
    }

    /**
     * The row pitch for a window; [barDp] and [bottomGapDp] are the fixed bands the short-window
     * budget has to leave room for.
     */
    fun rowPitchDp(
        preset: KeyHeight,
        screenHeightDp: Int,
        barDp: Float,
        bottomGapDp: Float,
    ): Float {
        if (screenHeightDp in 1..SHORT_WINDOW_DP) {
            val room = (shortKeyboardMaxDp(screenHeightDp) - barDp - bottomGapDp) / KeyboardDesignTokens.KEY_ROWS
            return min(preset.pitchDp * SHORT_ROW_SHARE, room).coerceAtLeast(SHORT_ROW_MIN_DP)
        }
        return portraitAreaDp(preset, screenHeightDp) / KeyboardDesignTokens.KEY_ROWS
    }

    /** The extra band below the key area is chosen in this range and step (dp). */
    const val BOTTOM_GAP_MAX_DP = 48
    const val BOTTOM_GAP_STEP_DP = 4

    /** Snaps a stored or typed value to the range and the step; anything else would draw an odd band. */
    fun clampBottomGapDp(value: Int): Int =
        (value.coerceIn(0, BOTTOM_GAP_MAX_DP) / BOTTOM_GAP_STEP_DP) * BOTTOM_GAP_STEP_DP

    /** The width (dp) above which the automatic split shows is chosen in this range and step. */
    const val SPLIT_WIDTH_MIN_DP = 400
    const val SPLIT_WIDTH_MAX_DP = 1200
    const val SPLIT_WIDTH_STEP_DP = 20
    const val SPLIT_WIDTH_DEFAULT_DP = 600

    /** The blank margin (dp) on each side of the key area is chosen in this range and step. */
    const val SIDE_MARGIN_MAX_DP = 96
    const val SIDE_MARGIN_STEP_DP = 4

    fun clampSplitWidthDp(value: Int): Int =
        (value.coerceIn(SPLIT_WIDTH_MIN_DP, SPLIT_WIDTH_MAX_DP) - SPLIT_WIDTH_MIN_DP) / SPLIT_WIDTH_STEP_DP *
            SPLIT_WIDTH_STEP_DP + SPLIT_WIDTH_MIN_DP

    fun clampSideMarginDp(value: Int): Int =
        value.coerceIn(0, SIDE_MARGIN_MAX_DP) / SIDE_MARGIN_STEP_DP * SIDE_MARGIN_STEP_DP

    /**
     * The extra empty band below the key area for a [requestedDp] choice. On a short window it is
     * cut to what is left of [shortKeyboardMaxDp] after the bar, the rows at [pitchDp] and the
     * built-in [gapDp]; the reading row's room is kept while that row still fits, so the band never
     * pushes it out. Rows are never shrunk for the band: the keys keep their size.
     */
    fun bottomExtraDp(
        requestedDp: Int,
        screenHeightDp: Int,
        barDp: Float,
        pitchDp: Float,
        gapDp: Float,
    ): Float {
        val requested = clampBottomGapDp(requestedDp).toFloat()
        if (screenHeightDp !in 1..SHORT_WINDOW_DP) return requested
        val readingRow =
            if (ReadingRowBudget.rowDp(screenHeightDp, barDp, pitchDp, gapDp) >
                0f
            ) {
                ReadingRowBudget.SHORT_ROW_DP
            } else {
                0f
            }
        val room =
            shortKeyboardMaxDp(screenHeightDp) - barDp - pitchDp * KeyboardDesignTokens.KEY_ROWS - gapDp - readingRow
        return min(requested, room.coerceAtLeast(0f))
    }

    /** The user's choice, process-wide: one keyboard window exists per process. */
    @Volatile
    var keyHeight: KeyHeight = KeyHeight.DEFAULT

    /** The user's extra band below the keys (dp), 0 by default; see [bottomExtraDp]. */
    @Volatile
    var bottomGapDp: Int = 0

    /** [tokens] with the row pitch that fits [context]'s window and the user's key height. */
    fun applyTo(
        tokens: KeyboardDesignTokens,
        context: Context,
    ): KeyboardDesignTokens {
        val screenHeightDp = context.resources.configuration.screenHeightDp
        val short = screenHeightDp in 1..SHORT_WINDOW_DP
        val base = if (short) tokens.forCompactWindow() else tokens
        val pitch = rowPitchDp(keyHeight, screenHeightDp, base.candidateBarHeightDp, base.keyAreaBottomGapDp)
        val extra =
            bottomExtraDp(bottomGapDp, screenHeightDp, base.candidateBarHeightDp, pitch, base.keyAreaBottomGapDp)
        return if (pitch == base.rowHeightDp && extra == base.keyAreaExtraBottomDp) {
            base
        } else {
            base.copy(rowHeightDp = pitch, keyAreaExtraBottomDp = extra)
        }
    }
}

/** Text scaling for the canvas views: follows the system font size but stops at 1.3x. */
object ImeText {
    const val MAX_FONT_SCALE = 1.3f

    /** Multiplier from an sp value to pixels; the font scale is capped, the density is not. */
    fun scale(resources: Resources): Float = scale(resources.displayMetrics.density, resources.configuration.fontScale)

    fun scale(
        density: Float,
        fontScale: Float,
    ): Float = density * min(fontScale, MAX_FONT_SCALE)
}
