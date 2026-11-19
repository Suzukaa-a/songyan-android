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
 *  - merged letter keys (14-key, 18-key, ...), where the keys' codes ([LetterGroupLayout]) split
 *    a-z into groups, each key sends a letter of its group, and at least one key covers more than one.
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
     * Code to the key code it is typed with on a compact layout (QW -> q, ABC -> 2), read with
     * [LayoutFold]'s rule; empty for a definition that is not a compact layout or does not fold.
     */
    fun readingKeyMap(definition: LayoutDefinition?): Map<Char, Char> {
        if (definition == null || !matches(definition)) return emptyMap()
        val kind = LayoutFold.kindOf(definition) ?: return emptyMap()
        return LayoutFold.of(definition, kind)?.map.orEmpty()
    }
}
