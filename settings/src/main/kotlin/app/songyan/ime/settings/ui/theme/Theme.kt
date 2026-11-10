package app.songyan.ime.settings.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

// Brand palette: pine-soot ink on xuan paper. Used when dynamic color is off or unavailable.

private val PinePrimaryLight = Color(0xFF20513B)
private val PineOnPrimaryLight = Color(0xFFFFFFFF)
private val PinePrimaryContainerLight = Color(0xFFD3E7DA)
private val PineOnPrimaryContainerLight = Color(0xFF072014)

private val PinePrimaryDark = Color(0xFF8FD7AA)
private val PineOnPrimaryDark = Color(0xFF003820)
private val PinePrimaryContainerDark = Color(0xFF085233)
private val PineOnPrimaryContainerDark = Color(0xFFAAF4C5)

private val MistSecondaryLight = Color(0xFF506354)
private val MistOnSecondaryLight = Color(0xFFFFFFFF)
private val MistSecondaryContainerLight = Color(0xFFD3E8D5)
private val MistOnSecondaryContainerLight = Color(0xFF0E1F14)

private val MistSecondaryDark = Color(0xFFB7CCB9)
private val MistOnSecondaryDark = Color(0xFF233428)
private val MistSecondaryContainerDark = Color(0xFF394B3D)
private val MistOnSecondaryContainerDark = Color(0xFFD3E8D5)

private val StoneTertiaryLight = Color(0xFF3D6373)
private val StoneOnTertiaryLight = Color(0xFFFFFFFF)
private val StoneTertiaryContainerLight = Color(0xFFC1E8FB)
private val StoneOnTertiaryContainerLight = Color(0xFF001F29)

private val StoneTertiaryDark = Color(0xFFA5CDDE)
private val StoneOnTertiaryDark = Color(0xFF053543)
private val StoneTertiaryContainerDark = Color(0xFF244C5A)
private val StoneOnTertiaryContainerDark = Color(0xFFC1E8FB)

private val PaperLight = Color(0xFFF7F9F6)
private val PaperOnLight = Color(0xFF191C1A)
private val PaperVariantLight = Color(0xFFDDE4DC)
private val PaperOnVariantLight = Color(0xFF414942)
private val PaperOutlineLight = Color(0xFF717972)
private val PaperOutlineVariantLight = Color(0xFFC1C9C1)

private val InkNightDark = Color(0xFF111412)
private val InkNightOnDark = Color(0xFFE1E4DF)
private val InkNightVariantDark = Color(0xFF414942)
private val InkNightOnVariantDark = Color(0xFFC1C9C1)
private val InkNightOutlineDark = Color(0xFF8B938B)
private val InkNightOutlineVariantDark = Color(0xFF414942)

/** Fallback light scheme, used when dynamic color is off or the system is older than Android 12. */
val SongyanLightColorScheme: ColorScheme =
    lightColorScheme(
        primary = PinePrimaryLight,
        onPrimary = PineOnPrimaryLight,
        primaryContainer = PinePrimaryContainerLight,
        onPrimaryContainer = PineOnPrimaryContainerLight,
        secondary = MistSecondaryLight,
        onSecondary = MistOnSecondaryLight,
        secondaryContainer = MistSecondaryContainerLight,
        onSecondaryContainer = MistOnSecondaryContainerLight,
        tertiary = StoneTertiaryLight,
        onTertiary = StoneOnTertiaryLight,
        tertiaryContainer = StoneTertiaryContainerLight,
        onTertiaryContainer = StoneOnTertiaryContainerLight,
        background = PaperLight,
        onBackground = PaperOnLight,
        surface = PaperLight,
        onSurface = PaperOnLight,
        surfaceVariant = PaperVariantLight,
        onSurfaceVariant = PaperOnVariantLight,
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF1F4F0),
        surfaceContainer = Color(0xFFEBEEE9),
        surfaceContainerHigh = Color(0xFFE5E9E4),
        surfaceContainerHighest = Color(0xFFDFE3DE),
        outline = PaperOutlineLight,
        outlineVariant = PaperOutlineVariantLight,
    )

/** Fallback dark scheme, used when dynamic color is off or the system is older than Android 12. */
val SongyanDarkColorScheme: ColorScheme =
    darkColorScheme(
        primary = PinePrimaryDark,
        onPrimary = PineOnPrimaryDark,
        primaryContainer = PinePrimaryContainerDark,
        onPrimaryContainer = PineOnPrimaryContainerDark,
        secondary = MistSecondaryDark,
        onSecondary = MistOnSecondaryDark,
        secondaryContainer = MistSecondaryContainerDark,
        onSecondaryContainer = MistOnSecondaryContainerDark,
        tertiary = StoneTertiaryDark,
        onTertiary = StoneOnTertiaryDark,
        tertiaryContainer = StoneTertiaryContainerDark,
        onTertiaryContainer = StoneOnTertiaryContainerDark,
        background = InkNightDark,
        onBackground = InkNightOnDark,
        surface = InkNightDark,
        onSurface = InkNightOnDark,
        surfaceVariant = InkNightVariantDark,
        onSurfaceVariant = InkNightOnVariantDark,
        surfaceContainerLowest = Color(0xFF0C0E0C),
        surfaceContainerLow = Color(0xFF191D1A),
        surfaceContainer = Color(0xFF1D211E),
        surfaceContainerHigh = Color(0xFF272B28),
        surfaceContainerHighest = Color(0xFF323633),
        outline = InkNightOutlineDark,
        outlineVariant = InkNightOutlineVariantDark,
    )

/** Material 3 shape scale shared by the settings screens. */
val SongyanShapes =
    Shapes(
        extraSmall = RoundedCornerShape(4.dp),
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(16.dp),
        extraLarge = RoundedCornerShape(24.dp),
    )

/**
 * Strict CJK line breaking: the default lets a line start with ー or a small kana
 * (「デフォルト キ／ーボード」), which Japanese typesetting forbids. Chinese text is unaffected.
 */
private val StrictLineBreak =
    LineBreak(
        strategy = LineBreak.Strategy.Simple,
        strictness = LineBreak.Strictness.Strict,
        wordBreak = LineBreak.WordBreak.Default,
    )

/**
 * Typography tuned for Chinese glyphs: zero letter spacing (Latin negative tracking makes CJK
 * strokes touch) and line heights that leave headroom so OEM fonts are not clipped.
 */
val SongyanTypography =
    Typography(
        displayLarge =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Normal,
                fontSize = 57.sp,
                lineHeight = 64.sp,
                letterSpacing = 0.sp,
                lineBreak = StrictLineBreak,
            ),
        displayMedium =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Normal,
                fontSize = 45.sp,
                lineHeight = 52.sp,
                letterSpacing = 0.sp,
                lineBreak = StrictLineBreak,
            ),
        displaySmall =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Normal,
                fontSize = 36.sp,
                lineHeight = 44.sp,
                letterSpacing = 0.sp,
                lineBreak = StrictLineBreak,
            ),
        headlineLarge =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.SemiBold,
                fontSize = 32.sp,
                lineHeight = 40.sp,
                letterSpacing = 0.sp,
                lineBreak = StrictLineBreak,
            ),
        headlineMedium =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.SemiBold,
                fontSize = 28.sp,
                lineHeight = 36.sp,
                letterSpacing = 0.sp,
                lineBreak = StrictLineBreak,
            ),
        headlineSmall =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = 24.sp,
                lineHeight = 32.sp,
                letterSpacing = 0.sp,
                lineBreak = StrictLineBreak,
            ),
        titleLarge =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = 22.sp,
                lineHeight = 28.sp,
                letterSpacing = 0.sp,
                lineBreak = StrictLineBreak,
            ),
        titleMedium =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.1.sp,
                lineBreak = StrictLineBreak,
            ),
        titleSmall =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.1.sp,
                lineBreak = StrictLineBreak,
            ),
        bodyLarge =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.15.sp,
                lineBreak = StrictLineBreak,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.2.sp,
                lineBreak = StrictLineBreak,
            ),
        bodySmall =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                letterSpacing = 0.25.sp,
                lineBreak = StrictLineBreak,
            ),
        labelLarge =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.1.sp,
                lineBreak = StrictLineBreak,
            ),
        labelMedium =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.2.sp,
                lineBreak = StrictLineBreak,
            ),
        labelSmall =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.2.sp,
                lineBreak = StrictLineBreak,
            ),
    )

/**
 * Root theme. Light/dark follows the stored theme choice (system by default), and Android 12+
 * wallpaper colors are used when [dynamicColor] is on; otherwise the Songyan palette is used.
 */
@Composable
fun SongyanTheme(
    darkTheme: Boolean? = null,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val storedMode by ThemePreferencesHolder.current.mode.collectAsStateWithLifecycle()
    val dark =
        darkTheme ?: when (storedMode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
        }
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

            dark -> SongyanDarkColorScheme
            else -> SongyanLightColorScheme
        }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = SongyanTypography,
        shapes = SongyanShapes,
    ) {
        SystemBarAppearance(dark = dark)
        content()
    }
}

/**
 * Draws the status and navigation bar icons in the opposite tone of the effective in-app theme.
 *
 * The activity enables edge-to-edge with a system-driven bar style, so a light/dark choice that
 * differs from the system's leaves the clock and the navigation handle invisible against the
 * surface painted here. This runs on every theme change and only flips the icon tone.
 */
@Composable
private fun SystemBarAppearance(dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
