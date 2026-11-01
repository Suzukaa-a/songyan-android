package app.songyan.ime.layout

/**
 * Letter keys read from their labels: each encoding key covers the letters on its cap (QW covers q
 * and w) and sends the first of them. This is the one place that decides whether a definition letter
 * keys split a-z cleanly, for compact-spelling eligibility and for per-letter hints alike.
 */
object LetterGroupLayout {
    val ALPHABET: Set<Char> = "abcdefghijklmnopqrstuvwxyz".toSet()

    /**
     * One group per encoding key, in row order; null unless the main rows are all letter keys whose
     * labels cover a-z exactly once and each key sends its group first letter. A 26-key layout
     * yields 26 single-letter groups.
     */
    fun groups(definition: LayoutDefinition?): List<List<Char>>? {
        if (definition == null) return null
        val keys = CompactSpellingLayout.mainRows(definition).flatMap { it.keys }
        if (keys.isEmpty()) return null
        val seen = mutableSetOf<Char>()
        val result = mutableListOf<List<Char>>()
        for (key in keys) {
            val group = groupOf(key) ?: return null
            if (seen.any { it in group }) return null
            seen.addAll(group)
            result.add(group)
        }
        return if (seen == ALPHABET) result else null
    }

    /** The letters on one key, first letter first; null when the key does not send its first letter. */
    fun groupOf(key: LayoutKey): List<Char>? {
        if (key.action.type != LayoutActionKind.CODE) return null
        val value = key.action.value ?: return null
        if (value.length != 1) return null
        val sent = value.lowercase()[0]
        if (sent !in ALPHABET) return null
        val letters = mutableListOf<Char>()
        for (character in key.label.lowercase()) {
            if (character in ALPHABET && character !in letters) letters.add(character)
        }
        return if (letters.firstOrNull() == sent) letters else null
    }
}
