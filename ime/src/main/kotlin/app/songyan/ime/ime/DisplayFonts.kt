package app.songyan.ime.ime

import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import java.io.File

/** The imported font files, in cascade order, that draw candidate text and key labels. */
data class DisplayFontSpec(
    val candidates: List<File> = emptyList(),
    val keys: List<File> = emptyList(),
) {
    companion object {
        val SYSTEM = DisplayFontSpec()
    }
}

/**
 * The typefaces the user's imported fonts make: the chosen font first, then the other imported fonts
 * in import order, then the platform's sans for what none of them has. One process-wide pair, because
 * the keyboard and the settings previews draw from the same choice. Nothing is loaded while the
 * choice is the system font, and only the fonts of a chosen cascade are ever opened.
 */
object DisplayFonts {
    @Volatile
    var candidates: Typeface? = null
        private set

    @Volatile
    var keys: Typeface? = null
        private set

    private var spec = DisplayFontSpec.SYSTEM

    /** Loads the typefaces of [next] (call off the main thread); false when nothing changed. */
    @Synchronized
    fun configure(next: DisplayFontSpec): Boolean {
        if (next == spec && stamp(next.candidates) == stamps.first && stamp(next.keys) == stamps.second) return false
        val c = build(next.candidates)
        val k = if (next.keys == next.candidates) c else build(next.keys)
        spec = next
        stamps = stamp(next.candidates) to stamp(next.keys)
        candidates = c
        keys = k
        return true
    }

    private var stamps: Pair<String, String> = "" to ""

    /** A rewritten file under the same name must not keep the old typeface. */
    private fun stamp(files: List<File>): String =
        files.joinToString("|") { "${it.path}:${it.length()}:${it.lastModified()}" }

    /**
     * The typeface for [files] (the first is the chosen font); null when the chosen font cannot be
     * opened, which is the system font, or when [files] is empty. A fallback that cannot be opened is
     * left out.
     */
    fun build(files: List<File>): Typeface? {
        if (files.isEmpty()) return null
        val families = ArrayList<FontFamily>(files.size)
        files.forEachIndexed { index, file ->
            val family =
                runCatching {
                    FontFamily
                        .Builder(
                            Font.Builder(file).setTtcIndex(0).build(),
                        ).build()
                }.getOrNull()
            if (family == null && index == 0) return null
            if (family != null) families.add(family)
        }
        return runCatching {
            Typeface
                .CustomFallbackBuilder(families[0])
                .also { builder -> families.drop(1).forEach(builder::addCustomFallback) }
                .build()
        }.getOrNull()
    }
}
