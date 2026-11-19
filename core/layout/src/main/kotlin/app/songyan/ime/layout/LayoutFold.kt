package app.songyan.ime.layout

import app.songyan.ime.keyboard.engine.PublishedKeyboard
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
     * What [definition] folds into on a [kind] layout. Labels whose letters cover the 26 letters once
     * make the layout's own grouping. Labels that do not, on keys that still send exactly what the
     * bundled layout's keys send, are only what the keys show (a 9-key JKL key titled 我的 still
     * types 5): the bundled grouping applies, as it always did. Anything else does not fold: null.
     */
    fun of(
        definition: LayoutDefinition,
        kind: KeyboardLayoutKind,
    ): CompactFold? {
        val builtin = kind.compactFold ?: return null
        labelled(definition)?.let { map -> CompactFold.of(kind, map)?.let { return it } }
        return builtin.takeIf { sentCharacters(definition) == builtin.keysSent }
    }

    /** The letter-to-key mapping the labels describe; null when a letter is on two keys. */
    private fun labelled(definition: LayoutDefinition): Map<Char, Char>? {
        val map = HashMap<Char, Char>()
        for (group in groups(definition)) {
            for (letter in group.letters) {
                if (map.put(letter, group.sends) != null) return null
            }
        }
        return map
    }

    /** The letters and digits 2-9 the layout's one-character code keys send. */
    private fun sentCharacters(definition: LayoutDefinition): Set<Char> =
        definition.rows
            .flatMap { it.keys }
            .mapNotNull { key ->
                key.action.value?.takeIf { key.action.type == LayoutActionKind.CODE && it.length == 1 }
            }.map { it.lowercase()[0] }
            .filterTo(HashSet()) { it in ALPHABET || it in '2'..'9' }

    /**
     * The fold a keyboard on [layout] drawing the layout [board] types with: the bundled fold for
     * no board or one [registry] does not hold, the board's own fold otherwise (null when that
     * board does not fold cleanly, so the keyboard is never run on a fold it does not have).
     */
    fun of(
        registry: LayoutRegistry,
        layout: KeyboardLayoutKind,
        board: String?,
    ): CompactFold? {
        val definition = board?.let(registry::resolvedLayout) ?: return layout.compactFold
        return of(definition, layout)
    }

    /**
     * The deploy status name of what [keyboard] folds into: its layout's wire for the bundled
     * grouping (`phone9`), `merged14:14u1a2b3c4d` for a layout's own, null when it does not fold.
     */
    fun shapeWire(
        registry: LayoutRegistry,
        keyboard: PublishedKeyboard,
    ): String? {
        val fold = of(registry, keyboard.layout, keyboard.board) ?: return null
        return if (fold.isBuiltin) keyboard.layout.wire else keyboard.layout.wire + ":" + fold.id
    }

    /** [fold]'s targets of the 26 letters in a-z order, the form the deploy request carries. */
    fun targets(fold: CompactFold): String = ALPHABET.map { fold.map.getValue(it) }.joinToString("")

    /**
     * Whether [definition]'s titles write its grouping: every key that sends one letter or a digit
     * 2-9 has letters on its title. A layout with a key titled otherwise (a 9-key 5 titled 我的) only
     * changes what the keys show.
     */
    fun titlesWriteGroups(definition: LayoutDefinition): Boolean =
        definition.rows.flatMap { it.keys }.all { key ->
            val code =
                key.action.value
                    ?.takeIf { key.action.type == LayoutActionKind.CODE && it.length == 1 }
                    ?.lowercase()
                    ?.first()
            code == null || (code !in ALPHABET && code !in '2'..'9') || lettersOf(key.label).isNotEmpty()
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
