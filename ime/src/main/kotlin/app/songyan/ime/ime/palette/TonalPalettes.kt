package app.songyan.ime.ime.palette

/**
 * One Material You tonal palette in the shape the Android platform exposes it.
 *
 * Since Android 12 the platform publishes 13 perceptual-luminance anchors per wallpaper palette
 * (`android.R.color.system_accent1_*` … `system_neutral2_*`) instead of the continuous 0..100 tone
 * scale Material 3 resolves schemes from. Intermediate tones are therefore interpolated between the
 * two nearest anchors: neighbours are close enough in luminance for the error to be invisible, and
 * every role keeps the wallpaper-derived hue instead of falling back to a hard-coded scheme.
 */
data class TonalPalette(
    /** Anchors in the platform's own order: lightest first, darkest last (see [TONES]). */
    val anchors: List<Long>,
) {
    init {
        require(anchors.size == TONES.size) { "a tonal palette has " + TONES.size + " anchors" }
    }

    /** The colour at Material 3 [tone] (perceptual luminance, 0 = black, 100 = white). */
    fun tone(tone: Int): Long {
        val level = tone.coerceIn(0, 100)
        for (index in 0 until TONES.size - 1) {
            val lighter = TONES[index]
            val darker = TONES[index + 1]
            if (level <= lighter && level >= darker) {
                val span = (lighter - darker).toDouble()
                val fraction = if (span == 0.0) 0.0 else (lighter - level) / span
                return Argb.blend(anchors[index], anchors[index + 1], fraction)
            }
        }
        return anchors.last()
    }

    companion object {
        /** The 13 anchors Android publishes per palette, expressed as Material 3 tones. */
        val TONES: List<Int> = listOf(100, 99, 95, 90, 80, 70, 60, 50, 40, 30, 20, 10, 0)
    }
}

/** The five palettes Material You derives from the wallpaper. */
data class TonalPalettes(
    val accent1: TonalPalette,
    val accent2: TonalPalette,
    val accent3: TonalPalette,
    val neutral1: TonalPalette,
    val neutral2: TonalPalette,
)
