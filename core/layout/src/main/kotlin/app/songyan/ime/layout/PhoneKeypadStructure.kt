package app.songyan.ime.layout

/**
 * Structural recognition of a standard telephone keypad definition.
 *
 * The bundled T9 is matched by content equality, but an imported phone keypad with the same shape
 * under a different id/name must use the same shell, not the generic author renderer. This matcher
 * recognises the standard telephone mapping provably, not by approximation:
 *
 *  - exactly three main rows of exactly three keys, optionally followed by one non-encoding
 *    control row (the bundled T9 shape);
 *  - digits 2...9 each appear exactly once at their canonical telephone position and each sends a
 *    `.code` digit, never a literal;
 *  - the remaining row-0 column-0 slot is the telephone "1"/punctuation key.
 *
 * Reordering is not accepted: a calculator grid (7 8 9 / 4 5 6 / 1 2 3) is rejected by the
 * position rule even though it contains 2...9 exactly once. Anything ambiguous is rejected.
 */
object PhoneKeypadStructure {
    /** Canonical telephone positions; null marks the "1"/punctuation slot. */
    private val CANONICAL_DIGITS: List<List<String?>> =
        listOf(
            listOf(null, "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
        )

    /** The three digit rows, or null when the definition is not a standard telephone keypad. */
    fun mainRows(definition: LayoutDefinition): List<LayoutRow>? {
        val rows =
            when {
                definition.rows.size == 3 -> definition.rows
                definition.rows.size == 4 && isControlRow(definition.rows[3]) -> definition.rows.take(3)
                else -> return null
            }
        if (rows.any { it.keys.size != 3 }) return null
        val seen = mutableSetOf<String>()
        for ((rowIndex, row) in rows.withIndex()) {
            for ((columnIndex, key) in row.keys.withIndex()) {
                val expected = CANONICAL_DIGITS[rowIndex][columnIndex]
                if (expected != null) {
                    if (!isTelephoneDigit(key, expected) || !seen.add(expected)) return null
                } else {
                    if (!isOneSlot(key)) return null
                }
            }
        }
        // All eight telephone digits present exactly once.
        return if (seen.size == 8) rows else null
    }

    fun matches(definition: LayoutDefinition): Boolean = mainRows(definition) != null

    private fun isTelephoneDigit(
        key: LayoutKey,
        value: String,
    ): Boolean = key.action.type == LayoutActionKind.CODE && key.action.value == value

    private fun isOneSlot(key: LayoutKey): Boolean {
        if (emitsOne(key.action)) return true
        if ((key.longPress ?: emptyList()).any { emitsOne(it.action) }) return true
        return isPunctuationLiteral(key.action)
    }

    private fun emitsOne(action: LayoutAction): Boolean =
        (action.type == LayoutActionKind.CODE || action.type == LayoutActionKind.LITERAL) &&
            action.value == "1"

    private fun isPunctuationLiteral(action: LayoutAction): Boolean {
        if (action.type != LayoutActionKind.LITERAL) return false
        val value = action.value ?: return false
        if (value.length != 1) return false
        val character = value[0]
        return !character.isLetter() && !character.isDigit()
    }

    private fun isControlRow(row: LayoutRow): Boolean =
        row.keys.all {
            it.action.type != LayoutActionKind.CODE && it.action.type != LayoutActionKind.LITERAL
        }
}
