package app.songyan.ime.layout

import app.songyan.ime.keyboard.engine.PublishedKeyboard
import app.songyan.ime.keyboard.layout.CompactFold
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind

/**
 * The codes each key of a merged layout covers, and what the layout folds them into. One rule for
 * every merged layout, 14, 18 and 9 keys alike, bundled or written by hand: a key covers the codes
 * written on it ([LayoutKey.codes]: letters, strokes, marks) and sends its one-character code. A key's
 * title is only what it shows. A layout that writes no codes keeps the bundled grouping of its kind.
 */
object LayoutFold {
    private const val LETTERS = "abcdefghijklmnopqrstuvwxyz"

    /** One merged key: the codes it covers, the one it sends first, and what it sends. */
    data class Group(
        val codes: List<Char>,
        val sends: Char,
    )

    /**
     * The kind of merged layout [definition] is: the layout it is based on, else the bundled merged
     * layout whose keys send exactly what its keys send (an imported layout that names none); null for
     * a layout that is not merged.
     */
    fun kindOf(definition: LayoutDefinition): KeyboardLayoutKind? {
        val declared = definition.input.builtinLayout?.let { KeyboardLayoutKind.fromWire(it.wire) }
        if (declared != null) return declared.takeIf { it.compactFold != null }
        val sent = sentCharacters(definition)
        return KeyboardLayoutKind.entries.firstOrNull { it.compactFold?.keysSent == sent }
    }

    /** Whether [definition] writes its own grouping: some key carries [LayoutKey.codes]. */
    fun writesGroups(definition: LayoutDefinition): Boolean =
        definition.rows.any { row ->
            row.keys.any {
                it.codes !=
                    null
            }
        }

    /** The codes written in a key's [LayoutKey.codes], each once, in order. */
    fun codesOf(text: String?): List<Char> =
        text
            .orEmpty()
            .toList()
            .filterNot { it.isWhitespace() }
            .distinct()

    /**
     * The merged keys of [definition] on a [kind] layout, in row order: each one-character code key
     * with the codes it covers. Written codes win; without any, each key covers the codes the bundled
     * grouping sends to its code.
     */
    fun groups(
        definition: LayoutDefinition,
        kind: KeyboardLayoutKind,
    ): List<Group> =
        definition.rows.flatMap { it.keys }.mapNotNull { key ->
            val sends = sentCode(key) ?: return@mapNotNull null
            codesOn(definition, kind, key).takeIf { it.isNotEmpty() }?.let { Group(it, sends) }
        }

    /** The codes [key] covers on [definition], the one it sends first; empty for a key that covers none. */
    fun codesOn(
        definition: LayoutDefinition,
        kind: KeyboardLayoutKind,
        key: LayoutKey,
    ): List<Char> {
        val sends = sentCode(key) ?: return emptyList()
        val covered =
            when {
                key.codes != null -> codesOf(key.codes)
                writesGroups(definition) -> emptyList()
                else ->
                    kind.compactFold
                        ?.map
                        ?.filterValues { it == sends }
                        ?.keys
                        ?.sorted()
                        .orEmpty()
            }
        return listOf(sends).filter { it in covered } + (covered - sends)
    }

    /**
     * What [definition] folds into on a [kind] layout: its written grouping, or the bundled one when it
     * writes none and its keys send what the bundled layout's keys send. Null when it does not fold: a
     * code on two keys, keys sending something the fold rule refuses (see [CompactFold.of]), or keys
     * of a layout without written codes that send other characters than the bundled ones.
     */
    fun of(
        definition: LayoutDefinition,
        kind: KeyboardLayoutKind,
    ): CompactFold? {
        val builtin = kind.compactFold ?: return null
        if (!writesGroups(definition)) return builtin.takeIf { sentCharacters(definition) == builtin.keysSent }
        val map = HashMap<Char, Char>()
        for (group in groups(definition, kind)) {
            for (code in group.codes) {
                if (map.put(code, group.sends) != null) return null
            }
        }
        return CompactFold.of(kind, map)
    }

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

    /** [fold]'s mapping as the deploy request carries it: the codes, a newline, what each one's key sends. */
    fun encode(fold: CompactFold): String {
        val codes = fold.map.keys.sorted()
        return codes.joinToString("") + "\n" + codes.map { fold.map.getValue(it) }.joinToString("")
    }

    /** The mapping [encode] wrote; null for anything else. */
    fun decode(text: String): Map<Char, Char>? {
        val parts = text.split('\n')
        if (parts.size != 2 || parts[0].length != parts[1].length || parts[0].isEmpty()) return null
        return parts[0].zip(parts[1]).toMap()
    }

    /**
     * [definition] with every key's current codes written on it ([LayoutKey.codes]), so a change to
     * one key's codes never drops the bundled grouping of the others. A layout that writes codes
     * already is returned as it is.
     */
    fun writtenOut(
        definition: LayoutDefinition,
        kind: KeyboardLayoutKind,
    ): LayoutDefinition {
        if (writesGroups(definition)) return definition
        return definition.copy(
            rows =
                definition.rows.map { row ->
                    LayoutRow(
                        row.keys.map { key ->
                            val current = codesOn(definition, kind, key)
                            if (current.isEmpty()) key else key.copy(codes = current.joinToString(""))
                        },
                    )
                },
        )
    }

    /**
     * [key] covering [codes]. A key that sends a code no longer among its own sends the first of
     * them; a key sending a digit keeps it.
     */
    fun keyCovering(
        key: LayoutKey,
        codes: String,
    ): LayoutKey {
        val normalized = codesOf(codes)
        val sends = sentCode(key)
        val action =
            if (sends != null && sends !in '0'..'9' && sends !in normalized && normalized.isNotEmpty()) {
                LayoutAction.code(normalized.first().toString())
            } else {
                key.action
            }
        return key.copy(codes = normalized.joinToString(""), action = action)
    }

    /**
     * The codes [definition] writes on two keys of a merged layout; empty for a layout that writes
     * none or is not merged. Such a layout does not fold until each code is on one key.
     */
    fun repeated(definition: LayoutDefinition): List<Char> {
        val kind = kindOf(definition) ?: return emptyList()
        if (!writesGroups(definition)) return emptyList()
        val counts = groups(definition, kind).flatMap { it.codes }.groupingBy { it }.eachCount()
        return counts.filterValues { it > 1 }.keys.sorted()
    }

    /**
     * Every code [definition]'s keys put into the input method: what each key and each long-press
     * choice sends, and on a merged layout the codes its keys cover. librime takes one printable
     * ASCII character per key (`speller.cc`), so a code is one character.
     */
    fun coverage(definition: LayoutDefinition): Set<Char> {
        val keys = definition.rows.flatMap { it.keys }
        val sent =
            keys
                .flatMap { key -> listOf(key.action) + key.longPress.orEmpty().map { it.action } }
                .filter { it.type == LayoutActionKind.CODE }
                .flatMap { it.value.orEmpty().toList() }
        val written = keys.flatMap { codesOf(it.codes) }
        val merged = kindOf(definition)?.let { kind -> groups(definition, kind).flatMap { it.codes } }.orEmpty()
        return (sent + written + merged).toSet()
    }

    /**
     * The codes on [definition]'s keys the input method can never take: librime reads a key as one
     * printable ASCII character other than space (`speller.cc` refuses anything below 0x20 or from
     * 0x7f), so a stroke drawn as 一 or a full-width mark cannot be a code.
     */
    fun unusable(definition: LayoutDefinition): List<Char> =
        definition.rows
            .flatMap { it.keys }
            .flatMap { key ->
                val sent =
                    (listOf(key.action) + key.longPress.orEmpty().map { it.action })
                        .filter { it.type == LayoutActionKind.CODE }
                        .flatMap { it.value.orEmpty().toList() }
                sent + codesOf(key.codes)
            }.filter { it.code < 0x21 || it.code > 0x7e }
            .distinct()

    private fun sentCode(key: LayoutKey): Char? =
        key.action.value
            ?.takeIf { key.action.type == LayoutActionKind.CODE && it.length == 1 }
            ?.first()

    /** The letters and digits 2-9 the layout's one-character code keys send. */
    private fun sentCharacters(definition: LayoutDefinition): Set<Char> =
        definition.rows
            .flatMap { it.keys }
            .mapNotNull { sentCode(it)?.lowercaseChar() }
            .filterTo(HashSet()) { it in LETTERS || it in '2'..'9' }
}
