package app.songyan.ime.ime.palette

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Opaque ARGB arithmetic for palette derivation.
 *
 * Deliberately free of Android types: the derivation rules (contrast repair, tone interpolation)
 * are the part that must be unit-tested, so they cannot need a platform.
 */
internal object Argb {
    const val BLACK: Long = 0xFF000000L
    const val WHITE: Long = 0xFFFFFFFFL

    /** WCAG AA for body text; a pair below this is replaced rather than shipped. */
    const val MINIMUM_CONTRAST: Double = 4.5

    fun channel(
        color: Long,
        shift: Int,
    ): Double = ((color shr shift) and 0xFFL) / 255.0

    fun of(
        red: Double,
        green: Double,
        blue: Double,
    ): Long {
        fun byte(value: Double): Long = (value.coerceIn(0.0, 1.0) * 255.0).roundToLong()
        return (0xFFL shl 24) or (byte(red) shl 16) or (byte(green) shl 8) or byte(blue)
    }

    /** Straight sRGB interpolation, used between two neighbouring tone anchors. */
    fun blend(
        from: Long,
        to: Long,
        fraction: Double,
    ): Long {
        val t = fraction.coerceIn(0.0, 1.0)
        return of(
            channel(from, 16) + (channel(to, 16) - channel(from, 16)) * t,
            channel(from, 8) + (channel(to, 8) - channel(from, 8)) * t,
            channel(from, 0) + (channel(to, 0) - channel(from, 0)) * t,
        )
    }

    /** WCAG relative luminance of an opaque colour. */
    fun luminance(color: Long): Double {
        fun linear(value: Double): Double = if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        return 0.2126 * linear(channel(color, 16)) + 0.7152 * linear(channel(color, 8)) +
            0.0722 * linear(channel(color, 0))
    }

    fun contrast(
        one: Long,
        other: Long,
    ): Double {
        val first = luminance(one)
        val second = luminance(other)
        return (max(first, second) + 0.05) / (min(first, second) + 0.05)
    }

    /**
     * Returns [foreground] when it reads on every surface in [backgrounds], otherwise black or white,
     * whichever separates further. That fallback always clears WCAG AA: the least favourable surface
     * still leaves one of the two at about 4.58:1.
     */
    fun readableOn(
        foreground: Long,
        vararg backgrounds: Long,
    ): Long {
        if (backgrounds.all { contrast(foreground, it) >= MINIMUM_CONTRAST }) return foreground
        val blackContrast = backgrounds.minOfOrNull { contrast(BLACK, it) } ?: 0.0
        val whiteContrast = backgrounds.minOfOrNull { contrast(WHITE, it) } ?: 0.0
        return if (blackContrast >= whiteContrast) BLACK else WHITE
    }
}
