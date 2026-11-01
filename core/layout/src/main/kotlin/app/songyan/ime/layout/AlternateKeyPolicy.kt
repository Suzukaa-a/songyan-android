package app.songyan.ime.layout

/**
 * Pure long-press alternate policy for physical keycaps.
 *
 * Builtin 26-key and ABC letters share [defaultLetterLiterals]; layout-declared DIY literals remain
 * explicit choices and no letter gets automatic accent variants. The policy handles field width and
 * English shift, and emits either a verbatim literal or a valid schema-backed code. Merged 14-key
 * partners remain codes only when the live speller alphabet includes them; unknown capabilities fail
 * closed. Numeric/symbol pages use curated per-key lists for each punctuation width.
 */
object AlternateKeyPolicy {
    /** The engine mode the long press will dispatch into. */
    enum class Language { CHINESE, ENGLISH }

    /** The keyboard page the physical key belongs to. */
    enum class Page { LETTERS, NUMERIC, SYMBOLS }

    /**
     * Explicit dispatch semantics. [Code] is exactly one printable ASCII key handed to the engine as
     * a spelling code; [Literal] is text inserted verbatim. Nothing else is representable.
     */
    sealed class Payload {
        data class Code(
            val value: String,
        ) : Payload()

        data class Literal(
            val value: String,
        ) : Payload()
    }

    /** One ordered long-press option. */
    data class Option(
        val label: String,
        val payload: Payload,
    )

    /**
     * Every input the policy reads, as plain values. [schemaAlphabet] is the live speller/alphabet;
     * empty means unknown, which fails closed (no merged partner is offered).
     */
    data class Request(
        /** The physical key label, for example "a", "1", the Chinese period, "ABC". */
        val keyLabel: String,
        /** For a merged key, the full merged group (for example "qw"); its first char is the code. */
        val mergedGroup: String? = null,
        /** The declared, ordered secondary literals from the layout data. */
        val secondaryLiterals: List<String> = emptyList(),
        val page: Page,
        val language: Language,
        /** Shift is active, so letter variants render (and insert) uppercase. */
        val isUppercase: Boolean = false,
        /** The host field requires ASCII punctuation; CJK punctuation is filtered out. */
        val usesAsciiPunctuation: Boolean = false,
        /** The active schema speller/alphabet (any case). Empty = unknown. */
        val schemaAlphabet: String = "",
        /** Labels already shown as keys of the same page; a symbol page never offers them. */
        val visibleKeys: Set<String> = emptySet(),
    )

    /** The one entry point. */
    fun options(request: Request): List<Option> =
        when (request.page) {
            Page.LETTERS -> letterOptions(request)
            Page.NUMERIC, Page.SYMBOLS -> symbolOptions(request)
        }

    /** A code payload must be exactly one printable ASCII character, matching action validation. */
    fun isValidCode(value: String): Boolean = LayoutAction.isSinglePrintableAscii(value)

    /** True when the option obeys the payload contract (a valid code, or a nonempty literal). */
    fun isValid(option: Option): Boolean =
        when (val payload = option.payload) {
            is Payload.Code -> isValidCode(payload.value)
            is Payload.Literal -> payload.value.isNotEmpty()
        }

    /** Ordered builtin defaults only; DIY declarations are passed by the caller instead. */
    fun defaultLetterLiterals(letter: String): List<String> = DEFAULT_LETTERS[letter.lowercase()] ?: emptyList()

    /**
     * Each full-width mark is paired with the ASCII key a desktop Chinese IME types it from, so
     * English mode and ASCII fields still show a distinct mark; no two letters offer the same symbol.
     */
    private val DEFAULT_LETTERS: Map<String, List<String>> =
        mapOf(
            "q" to listOf("1"),
            "w" to listOf("2"),
            "e" to listOf("3"),
            "r" to listOf("4"),
            "t" to listOf("5"),
            "y" to listOf("6"),
            "u" to listOf("7"),
            "i" to listOf("8"),
            "o" to listOf("9"),
            "p" to listOf("0"),
            "a" to listOf("，", ","),
            "s" to listOf("。", ".", "…"),
            "d" to listOf("？", "?"),
            "f" to listOf("！", "!"),
            "g" to listOf("、", "\\"),
            "h" to listOf("：", ":"),
            "j" to listOf("；", ";"),
            "k" to listOf("（", "("),
            "l" to listOf("）", ")"),
            "z" to listOf("@"),
            "x" to listOf("-", "_"),
            "c" to listOf("“", "”", "\""),
            "v" to listOf("‘", "’", "'"),
            "b" to listOf("【", "[", "《", "<"),
            "n" to listOf("】", "]", "》", ">"),
            "m" to listOf("/"),
        )

    private fun letterOptions(request: Request): List<Option> {
        val label = request.keyLabel
        // A letter group (T9 "ABC"), a digit key or a symbol key has no letter alternates.
        if (label.length != 1 || !label[0].isLetter()) return emptyList()
        val secondary = literalOptions(request.secondaryLiterals, request)
        return when (request.language) {
            Language.CHINESE -> secondary + mergedPartnerOptions(request)
            Language.ENGLISH -> secondary
        }
    }

    /** Keep explicit layout choices, applying English shift and the field-width policy. */
    private fun literalOptions(
        literals: List<String>,
        request: Request,
    ): List<Option> {
        val uppercase = request.language == Language.ENGLISH && request.isUppercase
        val usable =
            deduplicated(
                literals.filter { it.isNotEmpty() && it != request.keyLabel }.map { cased(it, uppercase) },
            )
        return orderedByFieldWidth(usable, asciiFirst = request.usesAsciiPunctuation)
            .map { Option(it, Payload.Literal(it)) }
    }

    /** An accent/diacritic letter: alphabetic but not ASCII. */
    fun containsNonAsciiLetter(value: String): Boolean = value.any { it.isLetter() && it.code > 127 }

    /** Merged partners: a code iff the schema alphabet contains the letter, else hidden. */
    private fun mergedPartnerOptions(request: Request): List<Option> {
        val group = request.mergedGroup ?: return emptyList()
        if (group.length <= 1) return emptyList()
        val alphabet = alphabetSet(request.schemaAlphabet)
        if (alphabet.isEmpty()) return emptyList()
        return group.drop(1).mapNotNull { character ->
            val lower = character.lowercaseChar()
            if (lower !in alphabet) return@mapNotNull null
            val code = lower.toString()
            // The alphabet is a live schema field and may name a non-ASCII letter; a Code payload is
            // declared as one printable ASCII character, so such a partner stays hidden.
            if (!isValidCode(code)) return@mapNotNull null
            Option(if (request.isUppercase) code.uppercase() else code, Payload.Code(code))
        }
    }

    /**
     * The codes a sideways swipe on a two-letter key sends, left then right; null unless the schema
     * accepts both exact letters (same gate as the partners above).
     */
    fun letterSwipeCodes(
        group: List<Char>,
        schemaAlphabet: String,
    ): List<String>? {
        if (group.size != 2) return null
        val alphabet = alphabetSet(schemaAlphabet)
        val codes = group.map { it.lowercaseChar().toString() }
        if (codes.any { it.length != 1 || it[0] !in alphabet || !isValidCode(it) }) return null
        return codes
    }

    /**
     * The 123 page draws each key's first option as its corner hint, so the lists are curated per key
     * and per punctuation width: a list stays within its key's meaning and never offers a mark that
     * is already a key of the same page. An ASCII field still filters whatever a list holds.
     */
    private fun symbolOptions(request: Request): List<Option> {
        val label = request.keyLabel
        val table = if (request.usesAsciiPunctuation) ASCII_SYMBOL_ALTERNATES else CHINESE_SYMBOL_ALTERNATES
        var variants =
            deduplicated(
                (table[label] ?: SHARED_SYMBOL_ALTERNATES[label] ?: emptyList())
                    .filter { it != label && it !in request.visibleKeys },
            )
        if (request.usesAsciiPunctuation) {
            variants = orderedByFieldWidth(variants, asciiFirst = true)
        }
        return variants.map { Option(it, Payload.Literal(it)) }
    }

    /** Full-width punctuation: digits offer the Chinese numerals; marks offer half-width first. */
    private val CHINESE_SYMBOL_ALTERNATES: Map<String, List<String>> =
        mapOf(
            "1" to listOf("一", "壹", "①"),
            "2" to listOf("二", "贰", "②"),
            "3" to listOf("三", "叁", "③"),
            "4" to listOf("四", "肆", "④"),
            "5" to listOf("五", "伍", "⑤"),
            "6" to listOf("六", "陆", "⑥"),
            "7" to listOf("七", "柒", "⑦"),
            "8" to listOf("八", "捌", "⑧"),
            "9" to listOf("九", "玖", "⑨"),
            "0" to listOf("零", "〇", "°"),
            "-" to listOf("～", "—", "_"),
            "/" to listOf("\\", "|", "÷"),
            "：" to listOf(":"),
            "；" to listOf(";"),
            "（" to listOf("(", "【", "[", "{"),
            "）" to listOf(")", "】", "]", "}"),
            "¥" to listOf("$", "€", "£", "￥"),
            "“" to listOf("「", "『", "\"", "‘"),
            "”" to listOf("」", "』", "\"", "’"),
            "，" to listOf(","),
            "、" to listOf("·"),
            "？" to listOf("?"),
            "！" to listOf("!"),
        )

    /** Half-width punctuation (English mode and ASCII fields): only what an ASCII field accepts. */
    private val ASCII_SYMBOL_ALTERNATES: Map<String, List<String>> =
        mapOf(
            "0" to listOf("°"),
            "-" to listOf("_"),
            "/" to listOf("\\", "|"),
            "(" to listOf("[", "{", "<"),
            ")" to listOf("]", "}", ">"),
            "$" to listOf("¥", "€", "£"),
            "'" to listOf("`"),
        )

    /** Operators and the other forms a symbol key outside the 123 rows may carry. */
    private val SHARED_SYMBOL_ALTERNATES: Map<String, List<String>> =
        mapOf(
            "*" to listOf("×", "°"),
            "×" to listOf("*", "°"),
            "÷" to listOf("/"),
            "+" to listOf("±"),
            "=" to listOf("≈", "≠"),
            "#" to listOf("№"),
            "%" to listOf("‰"),
            "<" to listOf("≤", "‹", "〈"),
            ">" to listOf("≥", "›", "〉"),
            "·" to listOf("•"),
            "•" to listOf("·"),
            "¥" to listOf("$", "€", "£", "￥"),
            "￥" to listOf("¥", "$", "€", "£"),
            "$" to listOf("¥", "€", "£"),
            // ¥ only on $ and ￥: a page showing $ € £ would otherwise hint ¥ on all three.
            "€" to listOf("$", "£"),
            "£" to listOf("$", "€"),
            "「" to listOf("『"),
            "」" to listOf("』"),
            "《" to listOf("〈"),
            "》" to listOf("〉"),
            "【" to listOf("〖", "["),
            "】" to listOf("〗", "]"),
        )

    // MARK: Full/half-width field policy

    /** A value is ASCII width when every character is ASCII. */
    fun isAsciiWidth(value: String): Boolean = value.isNotEmpty() && value.all { it.code in 0..127 }

    /**
     * Full-width or CJK punctuation: CJK Symbols and Punctuation (U+3000-303F), CJK Compatibility
     * Forms (U+FE30-FE4F) and Halfwidth/Fullwidth Forms (U+FF00-FFEF). This is the CJK-priority
     * grouping predicate for a CJK field and the first thing an ASCII field drops, but on its own it
     * is not the ASCII contract: forms like the ellipsis, dashes and smart quotes live outside those
     * blocks and are also filtered (see [isAllowedInAsciiField]).
     */
    fun isFullwidthOrCjkPunctuation(value: String): Boolean =
        value.any { character ->
            when (character.code) {
                in 0x3000..0x303F, in 0xFE30..0xFE4F, in 0xFF00..0xFFEF -> true
                else -> false
            }
        }

    /**
     * The non-ASCII currency/math symbols explicitly allowed in an ASCII field, one by one, because
     * they are not width variants of an ASCII key: euro, pound, yen; plus-minus, division,
     * multiplication, approximately, not-equal; numero, per-mille, degree.
     */
    val ASCII_FIELD_ALLOWED_SYMBOLS: Set<Char> = "€£¥±÷×≈≠№‰°".toSet()

    /**
     * Whether a value may be offered in an ASCII-constrained field (URL/email/number): pure ASCII, or
     * a letter variant (accented Latin letters), or made of nothing but the allowed currency/math
     * symbols. Every other non-ASCII punctuation/symbol is rejected.
     */
    fun isAllowedInAsciiField(value: String): Boolean {
        if (value.isEmpty()) return false
        if (isAsciiWidth(value)) return true
        if (containsNonAsciiLetter(value)) return true
        return value.all { it.code in 0..127 || it in ASCII_FIELD_ALLOWED_SYMBOLS }
    }

    /**
     * The field policy. An ASCII field filters rather than reorders: only ASCII characters, letter
     * variants and the allowed currency/math symbols survive. A CJK field puts the full-width/CJK
     * forms first and keeps the half-width ones as secondaries. Order is otherwise stable.
     */
    fun orderedByFieldWidth(
        values: List<String>,
        asciiFirst: Boolean,
    ): List<String> {
        if (asciiFirst) return values.filter { isAllowedInAsciiField(it) }
        val cjk = values.filter { isFullwidthOrCjkPunctuation(it) }
        val rest = values.filter { !isFullwidthOrCjkPunctuation(it) }
        return cjk + rest
    }

    private fun cased(
        value: String,
        uppercase: Boolean,
    ): String {
        if (!uppercase) return value
        val upper = value.uppercase()
        // A few forms expand to multiple characters; a keycap label must stay one character, so
        // those keep their base form.
        return if (upper.length == 1) upper else value
    }

    private fun alphabetSet(alphabet: String): Set<Char> = alphabet.lowercase().toSet()

    private fun deduplicated(values: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        return values.filter { seen.add(it) }
    }
}
