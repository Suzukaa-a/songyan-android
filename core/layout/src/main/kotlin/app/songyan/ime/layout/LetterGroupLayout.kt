package app.songyan.ime.layout

/**
 * The letters each letter key of a definition covers, read with [LayoutFold]'s rule: the codes a
 * merged key covers, or the letter a plain key sends. This is the one place that decides whether a
 * definition's letter keys split a-z cleanly, for compact-spelling eligibility and for per-letter
 * hints alike.
 */
object LetterGroupLayout {
    val ALPHABET: Set<Char> = "abcdefghijklmnopqrstuvwxyz".toSet()

    /**
     * One group per letter key, in row order, the letter it sends first; null unless the main rows'
     * letter keys cover a-z exactly once. A 26-key layout yields 26 single-letter groups.
     */
    fun groups(definition: LayoutDefinition?): List<List<Char>>? {
        if (definition == null) return null
        val keys = CompactSpellingLayout.mainRows(definition).flatMap { it.keys }
        if (keys.isEmpty()) return null
        val seen = mutableSetOf<Char>()
        val result = mutableListOf<List<Char>>()
        for (key in keys) {
            val group = groupOf(definition, key)?.takeIf { it.isNotEmpty() } ?: return null
            if (group.any { it !in ALPHABET } || seen.any { it in group }) return null
            seen.addAll(group)
            result.add(group)
        }
        return if (seen == ALPHABET) result else null
    }

    /** The letters [key] covers on [definition], the one it sends first; null for a key that sends no code. */
    fun groupOf(
        definition: LayoutDefinition,
        key: LayoutKey,
    ): List<Char>? {
        if (key.action.type != LayoutActionKind.CODE) return null
        val value = key.action.value?.takeIf { it.length == 1 } ?: return null
        val kind = LayoutFold.kindOf(definition)
        if (kind != null) return LayoutFold.codesOn(definition, kind, key)
        // A layout that is not a merged one: written codes, else the letters its title shows, which
        // must include the one it sends (a 26-key letter; not a radical key, which shows no letter).
        val written = LayoutFold.codesOf(key.codes)
        if (written.isNotEmpty()) return listOf(value[0]).filter { it in written } + (written - value[0])
        val sent = value.lowercase()[0]
        val shown =
            key.label
                .lowercase()
                .filter { it in ALPHABET }
                .toList()
                .distinct()
        return if (shown.firstOrNull() == sent) shown else null
    }
}
