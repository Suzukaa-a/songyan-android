package app.songyan.ime.ime.palette

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build

/**
 * Reads the wallpaper-derived tonal palettes the platform publishes since Android 12.
 *
 * The anchors are the framework's own `android.R.color.system_*` values, which the system overlays
 * from the wallpaper; nothing here reads a file or talks to another process. The ids are inlined
 * constants that only resolve on Android 12+, which is safe here: [read] returns before touching
 * them on older versions and every lookup stays inside its try/catch.
 */
@SuppressLint("InlinedApi")
object SystemTonalPalettes {
    /** The palettes of the current wallpaper, or null before Android 12 or when unreadable. */
    fun read(context: Context): TonalPalettes? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return try {
            fun palette(ids: IntArray): TonalPalette =
                TonalPalette(ids.map { color -> context.getColor(color).toLong() and 0xFFFFFFFFL })
            TonalPalettes(
                accent1 = palette(ACCENT1),
                accent2 = palette(ACCENT2),
                accent3 = palette(ACCENT3),
                neutral1 = palette(NEUTRAL1),
                neutral2 = palette(NEUTRAL2),
            )
        } catch (error: RuntimeException) {
            // A device without dynamic colour support may not resolve every anchor; callers then
            // fall back to the applied appearance instead of painting a partial palette.
            null
        }
    }

    private val ACCENT1 =
        intArrayOf(
            android.R.color.system_accent1_0,
            android.R.color.system_accent1_10,
            android.R.color.system_accent1_50,
            android.R.color.system_accent1_100,
            android.R.color.system_accent1_200,
            android.R.color.system_accent1_300,
            android.R.color.system_accent1_400,
            android.R.color.system_accent1_500,
            android.R.color.system_accent1_600,
            android.R.color.system_accent1_700,
            android.R.color.system_accent1_800,
            android.R.color.system_accent1_900,
            android.R.color.system_accent1_1000,
        )

    private val ACCENT2 =
        intArrayOf(
            android.R.color.system_accent2_0,
            android.R.color.system_accent2_10,
            android.R.color.system_accent2_50,
            android.R.color.system_accent2_100,
            android.R.color.system_accent2_200,
            android.R.color.system_accent2_300,
            android.R.color.system_accent2_400,
            android.R.color.system_accent2_500,
            android.R.color.system_accent2_600,
            android.R.color.system_accent2_700,
            android.R.color.system_accent2_800,
            android.R.color.system_accent2_900,
            android.R.color.system_accent2_1000,
        )

    private val ACCENT3 =
        intArrayOf(
            android.R.color.system_accent3_0,
            android.R.color.system_accent3_10,
            android.R.color.system_accent3_50,
            android.R.color.system_accent3_100,
            android.R.color.system_accent3_200,
            android.R.color.system_accent3_300,
            android.R.color.system_accent3_400,
            android.R.color.system_accent3_500,
            android.R.color.system_accent3_600,
            android.R.color.system_accent3_700,
            android.R.color.system_accent3_800,
            android.R.color.system_accent3_900,
            android.R.color.system_accent3_1000,
        )

    private val NEUTRAL1 =
        intArrayOf(
            android.R.color.system_neutral1_0,
            android.R.color.system_neutral1_10,
            android.R.color.system_neutral1_50,
            android.R.color.system_neutral1_100,
            android.R.color.system_neutral1_200,
            android.R.color.system_neutral1_300,
            android.R.color.system_neutral1_400,
            android.R.color.system_neutral1_500,
            android.R.color.system_neutral1_600,
            android.R.color.system_neutral1_700,
            android.R.color.system_neutral1_800,
            android.R.color.system_neutral1_900,
            android.R.color.system_neutral1_1000,
        )

    private val NEUTRAL2 =
        intArrayOf(
            android.R.color.system_neutral2_0,
            android.R.color.system_neutral2_10,
            android.R.color.system_neutral2_50,
            android.R.color.system_neutral2_100,
            android.R.color.system_neutral2_200,
            android.R.color.system_neutral2_300,
            android.R.color.system_neutral2_400,
            android.R.color.system_neutral2_500,
            android.R.color.system_neutral2_600,
            android.R.color.system_neutral2_700,
            android.R.color.system_neutral2_800,
            android.R.color.system_neutral2_900,
            android.R.color.system_neutral2_1000,
        )
}
