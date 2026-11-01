package app.songyan.ime.settings.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** WCAG 2.x AA over the fallback schemes: 4.5:1 for text, 3:1 for the outline of controls. */
class ThemeContrastTest {
    private fun channel(value: Float): Double {
        val v = value.toDouble()
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(color: Color): Double =
        0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

    private fun ratio(
        a: Color,
        b: Color,
    ): Double {
        val (hi, lo) = luminance(a).let { x -> luminance(b).let { y -> maxOf(x, y) to minOf(x, y) } }
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun textPairs(s: ColorScheme): List<Triple<String, Color, Color>> =
        listOf(
            Triple("onSurface/surface", s.onSurface, s.surface),
            Triple("onSurface/containerLowest", s.onSurface, s.surfaceContainerLowest),
            Triple("onSurface/containerLow", s.onSurface, s.surfaceContainerLow),
            Triple("onSurface/container", s.onSurface, s.surfaceContainer),
            Triple("onSurface/containerHigh", s.onSurface, s.surfaceContainerHigh),
            Triple("onSurface/containerHighest", s.onSurface, s.surfaceContainerHighest),
            Triple("onSurfaceVariant/surface", s.onSurfaceVariant, s.surface),
            Triple("onSurfaceVariant/containerLow", s.onSurfaceVariant, s.surfaceContainerLow),
            Triple("onSurfaceVariant/container", s.onSurfaceVariant, s.surfaceContainer),
            Triple("onSurfaceVariant/containerHigh", s.onSurfaceVariant, s.surfaceContainerHigh),
            Triple("onSurfaceVariant/containerHighest", s.onSurfaceVariant, s.surfaceContainerHighest),
            Triple("onSurfaceVariant/surfaceVariant", s.onSurfaceVariant, s.surfaceVariant),
            Triple("primary/surface", s.primary, s.surface),
            Triple("primary/containerHigh", s.primary, s.surfaceContainerHigh),
            Triple("onPrimary/primary", s.onPrimary, s.primary),
            Triple("onPrimaryContainer/primaryContainer", s.onPrimaryContainer, s.primaryContainer),
            Triple("onSecondary/secondary", s.onSecondary, s.secondary),
            Triple("onSecondaryContainer/secondaryContainer", s.onSecondaryContainer, s.secondaryContainer),
            Triple("onTertiary/tertiary", s.onTertiary, s.tertiary),
            Triple("onTertiaryContainer/tertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer),
        )

    private fun check(
        name: String,
        s: ColorScheme,
    ) {
        val failures =
            textPairs(s).filter { ratio(it.second, it.third) < 4.5 }.map {
                it.first + "=" +
                    "%.2f".format(ratio(it.second, it.third))
            }
        assertTrue("$name text below 4.5:1: $failures", failures.isEmpty())
        // The outline draws the border of text fields and unselected switches and radios.
        for ((label, bg) in listOf("surface" to s.surface, "containerHigh" to s.surfaceContainerHigh)) {
            val r = ratio(s.outline, bg)
            assertTrue("$name outline on $label is %.2f, below 3:1".format(r), r >= 3.0)
        }
    }

    @Test
    fun lightSchemeMeetsAa() = check("light", SongyanLightColorScheme)

    @Test
    fun darkSchemeMeetsAa() = check("dark", SongyanDarkColorScheme)
}
