package app.songyan.ime.appearance

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A colour in the skin file's `#RRGGBB` form.
 *
 * The model keeps floating-point channels so contrast correction stays lossless for the round trip;
 * the UI converts to an Android ARGB int at the boundary.
 */
data class SkinColor(
    val red: Double,
    val green: Double,
    val blue: Double,
) {
    /** Formats as `#RRGGBB`; alpha is not representable in the skin file format. */
    fun toHexString(): String {
        fun channel(value: Double): Int = (value.coerceIn(0.0, 1.0) * 255).roundToInt()
        return "#" + hexPair(channel(red)) + hexPair(channel(green)) + hexPair(channel(blue))
    }

    /** Android ARGB int for a Canvas `Paint`. */
    fun toArgb(alpha: Int = 0xFF): Int {
        fun channel(value: Double): Int = (value.coerceIn(0.0, 1.0) * 255).toInt()
        return (alpha shl 24) or (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)
    }

    /** WCAG relative luminance, used only to pick a readable foreground. */
    val luminance: Double
        get() {
            fun linear(channel: Double): Double =
                if (channel <= 0.04045) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
            return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue)
        }

    fun contrast(on: SkinColor): Double = (max(luminance, on.luminance) + 0.05) / (min(luminance, on.luminance) + 0.05)

    /** Keeps this colour when it already reads on [background]; otherwise falls back to black or white. */
    fun contrasting(on: SkinColor): SkinColor {
        if (contrast(on) >= MINIMUM_CONTRAST) return this
        val black = SkinColor(0.0, 0.0, 0.0)
        val white = SkinColor(1.0, 1.0, 1.0)
        return if (black.contrast(on) >= white.contrast(on)) black else white
    }

    companion object {
        /** WCAG AA for body text; a lower contrast means the skin's own foreground is replaced. */
        const val MINIMUM_CONTRAST: Double = 4.5

        private val HEX_DIGITS = "0123456789ABCDEF".toCharArray()
        private val HEX_PAIRS: Array<String> =
            Array(256) { value -> "" + HEX_DIGITS[value shr 4] + HEX_DIGITS[value and 0x0F] }

        private fun hexPair(value: Int): String = HEX_PAIRS[value.coerceIn(0, 255)]

        /** Returns null when [hex] is not exactly `#RRGGBB`. */
        fun fromHex(hex: String): SkinColor? {
            if (hex.length != 7 || hex[0] != '#') return null
            val value =
                hex.substring(1).toIntOrNull(16)?.takeIf { it >= 0 } ?: return null
            return SkinColor(
                red = ((value shr 16) and 0xFF) / 255.0,
                green = ((value shr 8) and 0xFF) / 255.0,
                blue = (value and 0xFF) / 255.0,
            )
        }
    }
}
