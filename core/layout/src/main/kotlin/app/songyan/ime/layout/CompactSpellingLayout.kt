package app.songyan.ime.layout

/**
 * Compact-spelling layouts: physical key structures that need combination disambiguation.
 *
 * Eligibility cannot look only at the declared builtin layout, which describes input method/encoding
 * rather than physical keys: a definition with 26-key rows but a mislabeled builtin would otherwise
 * get the disambiguation UI and send real ambiguity queries to the engine. This policy proves the
 * shape from the definition own rows/keys and rejects everything except two provable shapes:
 *
 *  - standard phone 9-key, delegated to [PhoneKeypadStructure];
 *  - merged letter keys (14-key, 18-key, ...), where the key labels split a-z into groups, each key
 *    sends its group first letter, and at least one key carries more than one letter.
 *
 * A 26-key layout never matches: every group has one letter.
 */
object CompactSpellingLayout {
    /** Whether the physical definition supports combination disambiguation (fail-closed on null). */
    fun matches(definition: LayoutDefinition?): Boolean {
        if (definition == null) return false
        if (PhoneKeypadStructure.matches(definition)) return true
        return isMergedLetterLayout(definition)
    }

    /** Main rows: drops the definition own non-encoding control row, if any. */
    fun mainRows(definition: LayoutDefinition): List<LayoutRow> {
        if (PhoneKeypadRailPolicy.controlRowKeys(definition) == null) return definition.rows
        return definition.rows.dropLast(1)
    }

    private fun isMergedLetterLayout(definition: LayoutDefinition): Boolean {
        val groups = LetterGroupLayout.groups(definition) ?: return false
        return groups.size < LetterGroupLayout.ALPHABET.size
    }

    /**
     * Letter to key code sent by a compact encoding key, derived from the key own label (ABC -> 2,
     * QW -> q). Ambiguous or non-compact definitions return an empty map.
     */
    fun readingKeyMap(definition: LayoutDefinition?): Map<Char, Char> {
        if (definition == null || !matches(definition)) return emptyMap()
        val map = mutableMapOf<Char, Char>()
        for (key in mainRows(definition).flatMap { it.keys }) {
            if (key.action.type != LayoutActionKind.CODE) continue
            val value = key.action.value ?: continue
            if (value.length != 1) continue
            val code = value.lowercase()[0]
            val letters = (key.label + value).lowercase().filter { it in LetterGroupLayout.ALPHABET }
            for (letter in letters.toSet()) {
                val existing = map[letter]
                if (existing != null && existing != code) return emptyMap()
                map[letter] = code
            }
        }
        return map
    }
}
