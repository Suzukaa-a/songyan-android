package app.songyan.ime.ime.palette

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import app.songyan.ime.appearance.AppearancePresets
import app.songyan.ime.ime.KeyboardDesignTokens
import app.songyan.ime.ime.KeyboardPalette
import app.songyan.ime.appearance.KeyboardPalette as AppearancePalette

/**
 * Resolves the palette the Canvas keyboard paints with.
 *
 * Precedence: the wallpaper palette when the user enabled dynamic colour on Android 12+, the
 * platform exposes one and it passes the contrast audit; then the applied appearance; then the
 * Android default appearance (`builtin-songyan`). Resolution reads no disk and at most the
 * platform's colour resources, so it is safe on the keyboard's show path; the result is cached
 * per configuration and appearance revision.
 */
object KeyboardPalettes {
    /** The palette for [context]'s current night mode; what the keyboard views call. */
    fun current(context: Context): KeyboardPalette {
        val uiMode = context.resources.configuration.uiMode
        val dark = (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val revision = KeyboardAppearanceRegistry.revision
        val cached = if (dark) darkEntry else lightEntry
        if (cached != null && cached.revision == revision && cached.uiMode == uiMode) {
            return cached.palette
        }
        val palette = runCatching { resolveUncached(context, dark) }.getOrElse { fallback(dark) }
        val entry = CacheEntry(revision, uiMode, palette)
        if (dark) {
            darkEntry = entry
        } else {
            lightEntry = entry
        }
        return palette
    }

    /** Drops the resolved palettes; the next [current] resolves them again from the appearance. */
    fun trim() {
        lightEntry = null
        darkEntry = null
    }

    /** The palette for an explicit light/dark state; previews and tests use this. */
    fun resolve(
        context: Context,
        dark: Boolean,
    ): KeyboardPalette = runCatching { previewUncached(context, dark) }.getOrElse { fallback(dark) }

    /** Whether this device can paint the wallpaper palette at all. */
    fun dynamicColorAvailable(context: Context): Boolean =
        Build.VERSION.SDK_INT >= PaletteSelection.DYNAMIC_COLOR_MIN_API && SystemTonalPalettes.read(context) != null

    private data class CacheEntry(
        val revision: Long,
        val uiMode: Int,
        val palette: KeyboardPalette,
    )

    @Volatile
    private var lightEntry: CacheEntry? = null

    @Volatile
    private var darkEntry: CacheEntry? = null

    private fun resolveUncached(
        context: Context,
        systemDark: Boolean,
    ): KeyboardPalette {
        val selection = PaletteSelection.of(KeyboardAppearanceRegistry.current(), Build.VERSION.SDK_INT, systemDark)
        val appearance = appearanceTokens(selection)
        dynamicPalette(context, selection, appearance)?.let { return it }
        return KeyboardPalette.fromTokens(appearance)
    }

    /** Resolves one explicit variant, ignoring the user's theme choice: previews paint both. */
    private fun previewUncached(
        context: Context,
        dark: Boolean,
    ): KeyboardPalette {
        val selection = PaletteSelection.variant(KeyboardAppearanceRegistry.current(), Build.VERSION.SDK_INT, dark)
        val appearance = appearanceTokens(selection)
        dynamicPalette(context, selection, appearance)?.let { return it }
        return KeyboardPalette.fromTokens(appearance)
    }

    /**
     * The wallpaper palette with the appearance's metrics, or null when dynamic colour is off or the
     * wallpaper palette fails the contrast audit.
     */
    private fun dynamicPalette(
        context: Context,
        selection: PaletteSelection,
        appearance: KeyboardDesignTokens,
    ): KeyboardPalette? {
        if (!selection.dynamic) return null
        val palettes = SystemTonalPalettes.read(context) ?: return null
        val colors = DynamicAppearancePalette.tokens(palettes, selection.dark)
        if (!DynamicAppearancePalette.isReadable(colors)) return null
        return KeyboardPalette.fromTokens(ImePalettes.withMetricsOf(colors, appearance))
    }

    private fun appearanceTokens(selection: PaletteSelection): KeyboardDesignTokens =
        ImePalettes.tokensOf(AppearancePalette.of(selection.document, selection.dark))

    /** The Android default appearance, used when neither the app graph nor the wallpaper is usable. */
    private fun fallback(dark: Boolean): KeyboardPalette =
        ImePalettes.of(AppearancePalette.of(AppearancePresets.androidDefault, dark))
}
