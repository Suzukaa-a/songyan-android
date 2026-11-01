package app.songyan.ime.keyboard.layout

import app.songyan.ime.keyboard.engine.KeyboardResourceSet

/**
 * Physical keyboard layout. The layout says nothing about the input scheme typed on it; scheme
 * compatibility follows from [keyFold], never from the layout's name.
 */
enum class KeyboardLayoutKind(
    val wire: String,
) {
    QWERTY26("qwerty26"),
    MERGED14("merged14"),
    MERGED18("merged18"),
    PHONE9("phone9"),
    ;

    /** How the keys cover the 26 letters. */
    enum class KeyFold {
        /** One letter per key. */
        IDENTITY,

        /** Neighbouring letters share a key that sends the group's first letter. */
        LETTER_GROUPS,

        /** Letters share the telephone digit keys 2-9. */
        PHONE_DIGITS,
    }

    val keyFold: KeyFold
        get() =
            when (this) {
                QWERTY26 -> KeyFold.IDENTITY
                MERGED14, MERGED18 -> KeyFold.LETTER_GROUPS
                PHONE9 -> KeyFold.PHONE_DIGITS
            }

    /** The letter fold a generated adapter applies; null for 26-key. */
    val compactFold: CompactFold?
        get() =
            when (this) {
                QWERTY26 -> null
                MERGED14 -> CompactFold.MERGED14
                MERGED18 -> CompactFold.MERGED18
                PHONE9 -> CompactFold.PHONE9
            }

    val shortTitle: String
        get() =
            when (this) {
                QWERTY26 -> "26键"
                MERGED14 -> "14键"
                MERGED18 -> "18键"
                PHONE9 -> "9键"
            }

    /** User-facing "keyboard layout" name (see [app.songyan.ime.keyboard.support.UserTerminology]). */
    val userTitle: String
        get() =
            when (this) {
                QWERTY26 -> "26 键"
                MERGED14 -> "14 键"
                MERGED18 -> "18 键"
                PHONE9 -> "9 键"
            }

    val detail: String
        get() =
            when (this) {
                QWERTY26 -> "每个字母一个键。"
                MERGED14 -> "两个字母合用一个键。"
                MERGED18 -> "部分字母单独一个键，其余两个字母合用一个键。"
                PHONE9 -> "数字键 2–9 各对应几个字母。"
            }

    /** Folded layouts need the candidate's real spelling, since the preedit is a folded code. */
    val requiresSpellingDisplay: Boolean get() = keyFold != KeyFold.IDENTITY

    companion object {
        fun fromWire(value: String): KeyboardLayoutKind? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Which key each of the 26 letters lands on in a compact layout: every letter maps to the letter
 * (or digit) its key sends. Adapter generation, identifiers and binding checks read this one value.
 */
class CompactFold(
    /** Identifier suffix of generated adapters (`_14`, `_18`, `_9`), also the user-facing key count. */
    val id: String,
    /** The folded letters, in a fixed order. */
    private val source: String,
    /** What the key of each [source] letter sends, position by position. */
    private val target: String,
) {
    init {
        require(source.length == target.length) { "fold source and target must align" }
    }

    /** What an exact pick of a merged key's own letter sends (tap sends the group's first letter). */
    val exactTokens: Map<Char, Char>

    init {
        val shared =
            source
                .zip(target)
                .filter { it.first != it.second }
                .map { it.second }
                .toSet()
        val leaders = source.filter { it in shared }
        require(leaders.length <= EXACT_TOKEN_POOL.length) { "too many merged keys for exact tokens" }
        exactTokens = leaders.toList().zip(EXACT_TOKEN_POOL.toList()).toMap()
    }

    /** The characters the layout can physically send. */
    val keysSent: Set<Char> get() = target.toSet()

    /** Telephone digits rather than letter groups. */
    val isPhoneDigits: Boolean get() = target.all { it.isDigit() }

    val map: Map<Char, Char> get() = source.zip(target).toMap()

    companion object {
        private const val EXACT_TOKEN_POOL = "!#%*+<>@^_|~"

        val MERGED14 =
            CompactFold(
                id = "14",
                source = "qwertyuiopasdfghjklzxcvbnm",
                target = "qqeettuuooaaddggjjlzzccbbm",
            )
        val MERGED18 =
            CompactFold(
                id = "18",
                source = "qwertyuiopasdfghjklzxcvbnm",
                target = "qwwrryuiipassffhjjlzxxvbbm",
            )
        val PHONE9 =
            CompactFold(
                id = "9",
                source = "abcdefghijklmnopqrstuvwxyz",
                target = "22233344455566677778889999",
            )
    }
}

/** The bundled input schemes (plain 26-key schemes; compact layouts run on generated companions). */
object BuiltinTemplateSchemas {
    const val QUANPIN = "songyan_quanpin"
    const val FLYPY = "songyan_flypy"
    val SOURCE_SCHEMA_IDS = listOf(QUANPIN, FLYPY)

    /** Same as `schema/name` in the YAML. */
    val NAMES = mapOf(QUANPIN to "内置全拼", FLYPY to "内置小鹤双拼")

    /** Engine schema for a (source scheme, layout) pair; null when either is unknown. */
    fun engineSchemaID(
        sourceID: String,
        layout: KeyboardLayoutKind,
    ): String? {
        if (sourceID !in SOURCE_SCHEMA_IDS) return null
        if (layout == KeyboardLayoutKind.QWERTY26) return sourceID
        return ADAPTERS.firstOrNull { it.sourceSchemaID == sourceID && it.layout == layout }?.schemaID
    }

    /** Engine schema for a layout when no resource set maps it: the first scheme. */
    fun defaultSchemaID(layout: KeyboardLayoutKind): String? = engineSchemaID(QUANPIN, layout)

    /** The generated companions shipped in the bundle; ids follow the compact adapter identifier. */
    val ADAPTERS =
        listOf(
            KeyboardResourceSet.EncodingAdapter(
                QUANPIN,
                "keyboard_47246213a538cb99d374_14",
                KeyboardLayoutKind.MERGED14,
                NAMES[QUANPIN],
            ),
            KeyboardResourceSet.EncodingAdapter(
                QUANPIN,
                "keyboard_47246213a538cb99d374_18",
                KeyboardLayoutKind.MERGED18,
                NAMES[QUANPIN],
            ),
            KeyboardResourceSet.EncodingAdapter(
                QUANPIN,
                "keyboard_47246213a538cb99d374_9",
                KeyboardLayoutKind.PHONE9,
                NAMES[QUANPIN],
            ),
            KeyboardResourceSet.EncodingAdapter(
                FLYPY,
                "keyboard_3d7821f984693864ae91_14",
                KeyboardLayoutKind.MERGED14,
                NAMES[FLYPY],
            ),
            KeyboardResourceSet.EncodingAdapter(
                FLYPY,
                "keyboard_3d7821f984693864ae91_18",
                KeyboardLayoutKind.MERGED18,
                NAMES[FLYPY],
            ),
            KeyboardResourceSet.EncodingAdapter(
                FLYPY,
                "keyboard_3d7821f984693864ae91_9",
                KeyboardLayoutKind.PHONE9,
                NAMES[FLYPY],
            ),
        )

    val layoutEntries: List<KeyboardResourceSet.LayoutEntry>
        get() = SOURCE_SCHEMA_IDS.map { KeyboardResourceSet.LayoutEntry(KeyboardLayoutKind.QWERTY26, it) }
}

/** A keycap action produced by a layout definition. */
sealed class KeyAction {
    /** A logical code (single character: a letter or digit; the representative letter on 14-key). */
    data class Code(
        val value: String,
    ) : KeyAction()

    /** A digit/symbol key that commits text directly; during composition it confirms first. */
    data class Literal(
        val text: String,
    ) : KeyAction()

    /** An explicit alphabetic alternate (e.g. á): English Shift applies. */
    data class LetterVariant(
        val text: String,
    ) : KeyAction()

    data object Space : KeyAction()

    data object Backspace : KeyAction()

    data object Enter : KeyAction()

    /** Globe key (system input-method switch). */
    data object SwitchKeyboard : KeyAction()

    /** Cycles the built-in layouts. */
    data object CycleLayout : KeyAction()
}
