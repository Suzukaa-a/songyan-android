package app.songyan.ime.ime

import android.graphics.Typeface
import java.util.concurrent.ConcurrentHashMap

/**
 * Maps a skin's font family and weight onto Android typefaces.
 *
 * `system` is the platform sans, `serif` and `monospaced` are the platform's own serif and
 * monospace families, and `rounded` asks for the `sans-serif-rounded` family: stock Android ships no
 * rounded face, so a device without one keeps the platform sans. Weights are 400, 500 and 600; a
 * platform sans without a variable weight axis rounds to the nearest face it has. `system` with
 * `regular` is the untouched default typeface, so a keyboard that names no font looks as before.
 */
internal object KeyboardTypefaces {
    /** The platform family name a skin family resolves to; null is the default sans. */
    fun familyName(family: String): String? =
        when (family) {
            "serif" -> "serif"
            "monospaced" -> "monospace"
            "rounded" -> "sans-serif-rounded"
            else -> null
        }

    /** The CSS-style weight of a skin weight name. */
    fun weightOf(weight: String): Int =
        when (weight) {
            "semibold" -> SEMIBOLD
            "medium" -> MEDIUM
            else -> REGULAR
        }

    private val cache = ConcurrentHashMap<String, Typeface>()

    fun of(
        family: String,
        weight: String,
    ): Typeface {
        val name = familyName(family)
        val value = weightOf(weight)
        if (name == null && value == REGULAR) return Typeface.DEFAULT
        return cache.getOrPut("$family|$weight") {
            Typeface.create(
                if (name ==
                    null
                ) {
                    Typeface.DEFAULT
                } else {
                    Typeface.create(name, Typeface.NORMAL)
                },
                value,
                false,
            )
        }
    }

    private const val REGULAR = 400
    private const val MEDIUM = 500
    private const val SEMIBOLD = 600
}
