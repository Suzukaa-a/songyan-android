package app.songyan.ime.keyboard.engine

import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import app.songyan.ime.keyboard.layout.CompactFold
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind

/**
 * Projects canonical pinyin syllables to the input code a built-in scheme expects on a layout:
 * full pinyin is the pinyin itself, Xiaohe is initial key + final key, and a compact layout folds
 * that code through its [CompactFold].
 *
 * Only the demo engine and its tests use this; at runtime librime's prism decides how a code
 * produces candidates and the front end only maps physical keys to logical codes.
 */
object LayoutCodeProjector {
    /** The code an encoding produces, keyed by engine schema (both bundled schemes share every layout). */
    data class Encoding(
        val base: Base,
        val fold: CompactFold? = null,
    ) {
        enum class Base { FULL_PINYIN, XIAOHE }

        /** The keys this encoding accepts into a composition. */
        fun accepts(key: Char): Boolean = fold?.let { key in it.keysSent } ?: (key in 'a'..'z')

        companion object {
            val FULL_PINYIN = Encoding(Base.FULL_PINYIN)
            val XIAOHE26 = Encoding(Base.XIAOHE)
            val XIAOHE14 = Encoding(Base.XIAOHE, CompactFold.MERGED14)
            val XIAOHE18 = Encoding(Base.XIAOHE, CompactFold.MERGED18)
            val PHONE_DIGITS = Encoding(Base.FULL_PINYIN, CompactFold.PHONE9)

            /** Any bundled scheme or companion; null for other schemas. */
            fun forSchemaID(schemaID: String): Encoding? {
                val layout = layoutForSchemaID(schemaID) ?: return null
                val source =
                    BuiltinTemplateSchemas.ADAPTERS.firstOrNull { it.schemaID == schemaID }?.sourceSchemaID
                        ?: schemaID
                return Encoding(
                    base = if (source == BuiltinTemplateSchemas.FLYPY) Base.XIAOHE else Base.FULL_PINYIN,
                    fold = layout.compactFold,
                )
            }

            private fun layoutForSchemaID(schemaID: String): KeyboardLayoutKind? {
                if (schemaID in BuiltinTemplateSchemas.SOURCE_SCHEMA_IDS) return KeyboardLayoutKind.QWERTY26
                return BuiltinTemplateSchemas.ADAPTERS.firstOrNull { it.schemaID == schemaID }?.layout
            }
        }
    }

    /**
     * Projects canonical pinyin syllables to the full input code (continuous, no separator):
     * full pinyin 你好 = "nihao", Xiaohe 26 = "nihc", 14-key = "bugc", 9-key = "64426".
     */
    fun composeCode(
        syllables: List<String>,
        encoding: Encoding,
    ): String = syllables.mapNotNull { codeForSyllable(it, encoding) }.joinToString("")

    /** Code of a single syllable under an encoding. */
    fun codeForSyllable(
        syllable: String,
        encoding: Encoding,
    ): String? {
        val code =
            when (encoding.base) {
                Encoding.Base.FULL_PINYIN -> syllable.lowercase()
                Encoding.Base.XIAOHE -> flypyCode(syllable.lowercase()) ?: return null
            }
        val fold = encoding.fold?.map ?: return code
        return code.map { fold[it] ?: it }.joinToString("")
    }

    // MARK: Xiaohe 26-key algebra

    /** Final key positions (Xiaohe): final -> key. */
    private val FINAL_KEYS =
        mapOf(
            "iu" to 'q',
            "ei" to 'w',
            "uan" to 'r',
            "ue" to 't',
            "ve" to 't',
            "un" to 'y',
            "uo" to 'o',
            "ie" to 'p',
            "iong" to 's',
            "ong" to 's',
            "ing" to 'k',
            "uai" to 'k',
            "ai" to 'd',
            "en" to 'f',
            "eng" to 'g',
            "iang" to 'l',
            "uang" to 'l',
            "ang" to 'h',
            "ian" to 'm',
            "an" to 'j',
            "ou" to 'z',
            "ia" to 'x',
            "ua" to 'x',
            "iao" to 'n',
            "ao" to 'c',
            "ui" to 'v',
            "in" to 'b',
        )

    private val SINGLE_LETTER_FINALS = setOf('a', 'e', 'i', 'o', 'u', 'v')
    private val SINGLE_CONSONANTS =
        setOf(
            'b',
            'p',
            'm',
            'f',
            'd',
            't',
            'n',
            'l',
            'g',
            'k',
            'h',
            'j',
            'q',
            'x',
            'r',
            'z',
            'c',
            's',
            'y',
            'w',
        )

    /** Zero-initial syllables (ending in a/o/e/ng; three-letter ang/eng/ong are special-cased). */
    private val ZERO_INITIAL_CODES =
        mapOf(
            "a" to "aa",
            "o" to "oo",
            "e" to "ee",
            "ai" to "ai",
            "ei" to "ei",
            "ao" to "ao",
            "ou" to "ou",
            "an" to "an",
            "en" to "en",
            "er" to "er",
            "ang" to "ah",
            "eng" to "eg",
            "ong" to "os",
        )

    /** The Xiaohe 26-key code of one syllable; null when it is outside the bundled algebra. */
    fun flypyCode(syllable: String): String? {
        val text = syllable.lowercase()
        if (text.isEmpty()) return null
        ZERO_INITIAL_CODES[text]?.let { return it }

        val initial: String
        val rest: String
        when {
            text.startsWith("zh") -> {
                initial = "zh"
                rest = text.substring(2)
            }
            text.startsWith("ch") -> {
                initial = "ch"
                rest = text.substring(2)
            }
            text.startsWith("sh") -> {
                initial = "sh"
                rest = text.substring(2)
            }
            text[0] in SINGLE_CONSONANTS -> {
                initial = text.substring(0, 1)
                rest = text.substring(1)
            }
            else -> {
                // Uncovered zero-initial: conservative code (first letter as is, rest by single-letter rules).
                initial = ""
                rest = text
            }
        }
        val initialCode =
            when (initial) {
                "zh" -> "v"
                "ch" -> "i"
                "sh" -> "u"
                else -> initial
            }
        val finalCode =
            when {
                // ju/qu/xu/yu -> jv/qv/xv/yv (the dictionary writes ju; the engine derives them).
                initial in setOf("j", "q", "x", "y") && rest == "u" -> "v"
                FINAL_KEYS.containsKey(rest) -> FINAL_KEYS.getValue(rest).toString()
                rest.length == 1 && rest[0] in SINGLE_LETTER_FINALS -> rest
                else -> return null
            }
        return initialCode + finalCode
    }
}
