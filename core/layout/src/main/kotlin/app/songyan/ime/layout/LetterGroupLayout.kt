package app.songyan.ime.layout

/**
 * Letter keys read from their labels: each encoding key covers the letters on its cap (QW covers q
 * and w) and sends one of them. This is the one place that decides whether a definition letter
 * keys split a-z cleanly, for compact-spelling eligibility and for per-letter hints alike.
 */
object LetterGroupLayout {
    val ALPHABET: Set<Char> = "abcdefghijklmnopqrstuvwxyz".toSet()

    /**
     * One group per encoding key, in row order; null unless the main rows are all letter keys whose
     * labels cover a-z exactly once and each key sends a letter of its group. A 26-key layout
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

    /**
     * The letters on one key, the letter it sends first; null when it sends no letter of its own
     * group. Read with [LayoutFold]'s rule, the one every merged layout follows.
     */
    fun groupOf(key: LayoutKey): List<Char>? {
        if (key.action.type != LayoutActionKind.CODE) return null
        val value = key.action.value ?: return null
        if (value.length != 1) return null
        val sent = value.lowercase()[0]
        val letters = LayoutFold.lettersOf(key.label)
        if (sent !in letters) return null
        return listOf(sent) + (letters - sent)
    }
}
