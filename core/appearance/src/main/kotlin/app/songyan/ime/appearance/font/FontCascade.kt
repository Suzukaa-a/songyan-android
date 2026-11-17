package app.songyan.ime.appearance.font

/** The two places the display font is chosen separately. */
enum class FontRole { CANDIDATES, KEYS }

/**
 * Which imported fonts draw a piece of text, in order. [SYSTEM] is the stored value of "no imported
 * font"; the platform's own font always comes last and is not part of the list.
 */
object FontCascade {
    const val SYSTEM = ""

    /**
     * The chosen font first, then the other imported fonts in import order. Empty means the system
     * font alone: nothing was chosen, or the chosen file is not among [readable] (deleted, or it
     * cannot be read), in which case the others are not promoted either, so a broken choice looks
     * like the default rather than like a different font.
     *
     * @param order imported file names in import order
     * @param readable the subset of [order] that can be opened
     */
    fun files(
        chosen: String,
        order: List<String>,
        readable: Set<String>,
    ): List<String> {
        if (chosen == SYSTEM || chosen !in readable) return emptyList()
        val rest = order.filter { it != chosen && it in readable }.distinct()
        return listOf(chosen) + rest
    }
}

/** The ordered font files for candidate text and for key labels; both empty is the system font. */
data class DisplayFontFiles(
    val candidates: List<String> = emptyList(),
    val keys: List<String> = emptyList(),
) {
    fun of(role: FontRole): List<String> = if (role == FontRole.CANDIDATES) candidates else keys

    companion object {
        val SYSTEM = DisplayFontFiles()
    }
}

/** Turns an original file name into one safe to store: no path parts, no control or reserved characters. */
object FontFileNames {
    private const val MAX_LENGTH = 96

    /** [stored] is the names already taken (compared case-insensitively); the result is not among them. */
    fun storedName(
        original: String,
        stored: Collection<String>,
    ): String {
        val base = original.substringAfterLast('/').substringAfterLast('\\')
        val cleaned =
            base
                .map { if (it.isISOControl() || it in "<>:\"|?*") '_' else it }
                .joinToString("")
                .trim()
                .trimStart('.')
        val dot = cleaned.lastIndexOf('.')
        val stem = (if (dot > 0) cleaned.substring(0, dot) else cleaned).ifBlank { "font" }
        val ext = if (dot > 0) cleaned.substring(dot).lowercase() else ".ttf"
        val taken = stored.map { it.lowercase() }.toSet()
        var n = 1
        var name = stem.take(MAX_LENGTH) + ext
        while (name.lowercase() in taken) {
            n++
            name = stem.take(MAX_LENGTH) + "-" + n + ext
        }
        return name
    }
}

/** The stored form of the import order: one file name per line. File names never contain a line break. */
object FontOrderWire {
    fun encode(names: List<String>): String = names.joinToString("\n")

    fun decode(wire: String?): List<String> =
        wire
            .orEmpty()
            .lines()
            .filter { it.isNotBlank() }
            .distinct()
}
