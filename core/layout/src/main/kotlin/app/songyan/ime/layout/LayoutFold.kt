package app.songyan.ime.layout

import app.songyan.ime.keyboard.layout.CompactFold
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind

/**
 * The letters each key of a merged layout covers, and what the layout folds them into. One rule for
 * every merged layout, 14, 18 and 9 keys alike, bundled or written by hand: an encoding key covers
 * the latin letters of its label and sends its one-character code.
 */
object LayoutFold {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz"

    /** One merged key: the letters on it, in label order, and what it sends. */
    data class Group(
        val letters: List<Char>,
        val sends: Char,
    )

    /** The merged keys of [definition] in row order: every one-character code key with letters on its label. */
    fun groups(definition: LayoutDefinition): List<Group> =
        definition.rows.flatMap { it.keys }.mapNotNull { key ->
            val code = key.action.value?.takeIf { key.action.type == LayoutActionKind.CODE && it.length == 1 }
            val letters = lettersOf(key.label)
            if (code == null || letters.isEmpty()) null else Group(letters, code.lowercase()[0])
        }

    /** The latin letters of a label, lowercased, each once, in order. */
    fun lettersOf(label: String): List<Char> =
        label
            .lowercase()
            .filter { it in ALPHABET }
            .toList()
            .distinct()

    /**
     * What [definition] folds into on a [kind] layout, or null when it is not a clean merge: a letter
     * missing or on two keys, or keys sending something the fold rule refuses (see [CompactFold.of]).
     */
    fun of(
        definition: LayoutDefinition,
        kind: KeyboardLayoutKind,
    ): CompactFold? {
        val map = HashMap<Char, Char>()
        for (group in groups(definition)) {
            for (letter in group.letters) {
                if (map.put(letter, group.sends) != null) return null
            }
        }
        return CompactFold.of(kind, map)
    }

    /** What keeps [definition] from folding, for the editor to show before it is saved. */
    data class Problems(
        val missing: List<Char>,
        val repeated: List<Char>,
    ) {
        val isEmpty: Boolean get() = missing.isEmpty() && repeated.isEmpty()
    }

    fun problems(definition: LayoutDefinition): Problems {
        val counts = groups(definition).flatMap { it.letters }.groupingBy { it }.eachCount()
        return Problems(
            missing = ALPHABET.filter { it !in counts }.toList(),
            repeated = ALPHABET.filter { (counts[it] ?: 0) > 1 }.toList(),
        )
    }
}
