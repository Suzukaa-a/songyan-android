package app.songyan.ime.appearance

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A colour in the skin file's `#RRGGBB` or `#RRGGBBAA` form.
 *
 * The model keeps floating-point channels so contrast correction stays lossless for the round trip;
 * the UI converts to an Android ARGB int at the boundary.
 */
data class SkinColor(
    val red: Double,
    val green: Double,
    val blue: Double,
    /** 1 for opaque colours. */
    val alpha: Double = 1.0,
    /** Whether the colour was written as `#RRGGBBAA`; the 8-digit form is kept on output, even for `FF`. */
    private val writtenWithAlpha: Boolean = false,
) {
    /** Formats as `#RRGGBB`, or `#RRGGBBAA` when the colour was written with alpha. */
    fun toHexString(): String {
        fun channel(value: Double): Int = (value.coerceIn(0.0, 1.0) * 255).roundToInt()
        val base = "#" + hexPair(channel(red)) + hexPair(channel(green)) + hexPair(channel(blue))
        return if (writtenWithAlpha) base + hexPair(channel(alpha)) else base
    }

    /** The opaque colour this one shows as when drawn over [over]. */
    fun composited(over: SkinColor): SkinColor {
        if (alpha >= 1.0) return SkinColor(red, green, blue)

        fun mix(
            top: Double,
            below: Double,
        ): Double = top * alpha + below * (1 - alpha)
        return SkinColor(mix(red, over.red), mix(green, over.green), mix(blue, over.blue))
    }

    private val alphaByte: Int get() = (alpha.coerceIn(0.0, 1.0) * 255).roundToInt()

    /** Android ARGB int for a Canvas `Paint`; [alpha] overrides the colour's own alpha. */
    fun toArgb(alpha: Int = alphaByte): Int {
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

    /** Contrast ratio on [on]; translucent colours are measured as composited. */
    fun contrast(on: SkinColor): Double {
        val backing = if (on.alpha < 1.0) on.composited(WHITE) else on
        val foreground = composited(backing)
        return (max(foreground.luminance, backing.luminance) + 0.05) /
            (min(foreground.luminance, backing.luminance) + 0.05)
    }

    /**
     * Keeps this colour when it reads on [on]; otherwise falls back to opaque black or white. With
     * [over], a translucent [on] is first composited over that surface.
     */
    fun contrasting(
        on: SkinColor,
        over: SkinColor? = null,
    ): SkinColor {
        val backing = over?.let { on.composited(it) } ?: on
        if (contrast(backing) >= MINIMUM_CONTRAST) return this
        return if (BLACK.contrast(backing) >= WHITE.contrast(backing)) BLACK else WHITE
    }

    companion object {
        /** WCAG AA for body text; a lower contrast means the skin's own foreground is replaced. */
        const val MINIMUM_CONTRAST: Double = 4.5

        private val HEX_DIGITS = "0123456789ABCDEF".toCharArray()
        private val HEX_PAIRS: Array<String> =
            Array(256) { value -> "" + HEX_DIGITS[value shr 4] + HEX_DIGITS[value and 0x0F] }

        private fun hexPair(value: Int): String = HEX_PAIRS[value.coerceIn(0, 255)]

        private val BLACK = SkinColor(0.0, 0.0, 0.0)
        private val WHITE = SkinColor(1.0, 1.0, 1.0)

        /** Returns null when [hex] is not exactly `#RRGGBB` or `#RRGGBBAA` (hex digits only). */
        fun fromHex(hex: String): SkinColor? {
            if (hex.isEmpty() || hex[0] != '#' || (hex.length != 7 && hex.length != 9)) return null
            if (!hex.drop(1).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
            val withAlpha = hex.length == 9
            val value = hex.substring(1).toLong(16)
            val rgb = if (withAlpha) value shr 8 else value
            return SkinColor(
                red = ((rgb shr 16) and 0xFF) / 255.0,
                green = ((rgb shr 8) and 0xFF) / 255.0,
                blue = (rgb and 0xFF) / 255.0,
                alpha = if (withAlpha) (value and 0xFF) / 255.0 else 1.0,
                writtenWithAlpha = withAlpha,
            )
        }

        /** The system keyboard surface keys are composited over when judging text contrast. */
        fun systemSurface(dark: Boolean): SkinColor = fromHex(if (dark) "#222223" else "#E0E1E8")!!
    }
}
