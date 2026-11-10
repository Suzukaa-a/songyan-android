package app.songyan.ime.layout

import app.songyan.ime.keyboard.engine.InputLanguage
import java.text.BreakIterator

/**
 * The categories of the categorized symbol page, organised like mainstream nine-key pinyin
 * keyboards: 常用 first, then 最近 once something was typed, then the marks split by width and the
 * themed sets of the user's symbol library.
 *
 * The page's own data ([KeyboardPageCategory]) is what the keyboard draws and the page editor edits;
 * this object builds the snapshot a copy of the built-in page starts from, so the keyboard and the
 * editor cannot drift apart. It is pure: the symbol library and the recents are read by the caller.
 */
object SymbolPageCategories {
    const val COMMON_ID: String = "common"
    const val RECENT_ID: String = "recent"

    /** A themed set of the symbol library, in the library's order. */
    data class LibrarySet(
        val id: String,
        val title: String,
        val symbols: List<String>,
    )

    /** One tab: its symbols in display order. */
    data class Category(
        val id: String,
        val title: String,
        val symbols: List<String>,
    ) {
        /** Kaomoji and other multi-glyph entries need two wide columns instead of four. */
        val columns: Int
            get() = if (symbols.any { glyphCount(it) > WIDE_GLYPHS }) WIDE_COLUMNS else NARROW_COLUMNS
    }

    private const val WIDE_GLYPHS = 3
    private const val WIDE_COLUMNS = 2
    private const val NARROW_COLUMNS = 4

    /**
     * 常用 opens the page. Its first three rows (all the page shows before a scroll) are the marks
     * typed most in the current punctuation width, in a fixed order so their positions can be learned.
     */
    fun commonSymbols(ascii: Boolean): List<String> =
        if (ascii) {
            listOf(
                ",",
                ".",
                "?",
                "!",
                ":",
                ";",
                "'",
                "\"",
                "(",
                ")",
                "@",
                "#",
                "&",
                "*",
                "-",
                "_",
                "/",
                "\\",
                "~",
                "%",
                "+",
                "=",
                "$",
                "^",
            )
        } else {
            listOf(
                "，",
                "。",
                "？",
                "！",
                "、",
                "：",
                "；",
                "……",
                "“",
                "”",
                "（",
                "）",
                "《",
                "》",
                "——",
                "·",
                "@",
                "#",
                "%",
                "&",
                "*",
                "/",
                "~",
                "-",
            )
        }

    /** Full-width marks; 「……」 and 「——」 are the doubled forms Chinese text uses. */
    val chineseSymbols: List<String> =
        listOf(
            "，",
            "。",
            "？",
            "！",
            "、",
            "：",
            "；",
            "……",
            "“",
            "”",
            "‘",
            "’",
            "（",
            "）",
            "《",
            "》",
            "——",
            "·",
            "…",
            "—",
            "「",
            "」",
            "『",
            "』",
            "【",
            "】",
            "〈",
            "〉",
            "〔",
            "〕",
            "～",
            "￥",
        )

    val englishSymbols: List<String> =
        listOf(
            ",",
            ".",
            "?",
            "!",
            ":",
            ";",
            "'",
            "\"",
            "(",
            ")",
            "[",
            "]",
            "{",
            "}",
            "<",
            ">",
            "@",
            "#",
            "$",
            "%",
            "&",
            "*",
            "-",
            "_",
            "+",
            "=",
            "/",
            "\\",
            "|",
            "~",
            "^",
            "`",
            "–",
            "¥",
            "€",
            "£",
            "•",
        )

    /** Pairs side by side, two pairs per row. */
    val bracketSymbols: List<String> =
        listOf(
            "（",
            "）",
            "【",
            "】",
            "《",
            "》",
            "「",
            "」",
            "『",
            "』",
            "〈",
            "〉",
            "〔",
            "〕",
            "〖",
            "〗",
            "(",
            ")",
            "[",
            "]",
            "{",
            "}",
            "<",
            ">",
        )

    /**
     * 常用, then 最近 (once something has been typed from this page or the symbol library), 中文,
     * 英文, 括号, then the library's sets. A library set whose every symbol is already in a built-in
     * tab (the default 标点 set) is not repeated. [leading] are the everyday marks the pairing reaches
     * nowhere else (see [KeyboardPageMarkCoverage]); 常用 lists them first.
     */
    fun display(
        ascii: Boolean,
        recents: List<String> = emptyList(),
        library: List<LibrarySet> = emptyList(),
        leading: List<String> = emptyList(),
        inputLanguage: InputLanguage = InputLanguage.ZH_HANS,
    ): List<Category> {
        fun category(
            id: String,
            title: String,
            symbols: List<String>,
        ) = Category(id, title, symbols.filter { it.isNotEmpty() }.distinct())

        val everyday = commonSymbols(ascii) + if (inputLanguage == InputLanguage.JA) JAPANESE_COMMON else emptyList()
        val common = category(COMMON_ID, "常用", KeyboardPageMarkCoverage.commonSymbols(everyday, leading))
        val widths =
            listOf(
                category("chinese", "中文", chineseSymbols),
                category("english", "英文", englishSymbols),
                category("brackets", "括号", bracketSymbols),
            )
        val covered = (listOf(common) + widths).flatMap { it.symbols }.toSet()
        val themed =
            library.mapNotNull { set ->
                category("library-" + set.id, set.title, set.symbols).takeIf { result ->
                    result.symbols.any { it !in covered }
                }
            }
        val recent = if (recents.isEmpty()) emptyList() else listOf(category(RECENT_ID, "最近", recents))
        return listOf(common) + recent + widths + themed
    }

    /**
     * What the built-in categorized page draws: [display] as page categories. The recent tab is an
     * ordinary tab here, present once something was typed.
     */
    fun builtin(
        ascii: Boolean,
        recents: List<String>,
        library: List<LibrarySet>,
        leading: List<String> = emptyList(),
        inputLanguage: InputLanguage = InputLanguage.ZH_HANS,
    ): List<KeyboardPageCategory> =
        display(ascii, recents, library, leading, inputLanguage).map { category ->
            KeyboardPageCategory(
                id = category.id,
                name = category.title,
                columns = category.columns,
                keys = category.symbols.map(::literalKey),
            )
        }

    /**
     * The data a copy of the built-in page starts from: the categories the keyboard shows, with the
     * recent tab always present (as a live source, never a frozen list) and the user's quick symbols
     * kept in 常用 even when its fixed marks do not contain them.
     */
    fun snapshot(
        recents: List<String>,
        library: List<LibrarySet>,
        quickSymbols: List<String>,
        inputLanguage: InputLanguage = InputLanguage.ZH_HANS,
    ): List<KeyboardPageCategory> {
        val result =
            display(ascii = false, recents = recents, library = library, inputLanguage = inputLanguage)
                .map { category ->
                    val recent = category.id == RECENT_ID
                    KeyboardPageCategory(
                        id = category.id,
                        name = category.title,
                        columns = category.columns,
                        keys = if (recent) emptyList() else category.symbols.map(::literalKey),
                        source = if (recent) KeyboardPageCategory.Source.RECENT else KeyboardPageCategory.Source.FIXED,
                    )
                }.toMutableList()
        if (result.none { it.source == KeyboardPageCategory.Source.RECENT }) {
            val recent =
                KeyboardPageCategory(RECENT_ID, "最近", keys = emptyList(), source = KeyboardPageCategory.Source.RECENT)
            result.add(minOf(1, result.size), recent)
        }
        val common = result.indexOfFirst { it.id == COMMON_ID }
        if (common >= 0) {
            val existing = result[common].keys.mapNotNull { it.action.value }.toSet()
            val added = quickSymbols.filter { it !in existing }.map(::literalKey)
            result[common] = result[common].copy(keys = result[common].keys + added)
        }
        return result
    }

    /** Marks Japanese typing needs that the Chinese common set has no home for. */
    private val JAPANESE_COMMON = listOf("・", "ー", "～")

    /** What a categorized page draws for [categories]: a recent tab lists [recents], every other tab its own keys. */
    fun resolved(
        categories: List<KeyboardPageCategory>,
        recents: List<String>,
    ): List<KeyboardPageCategory> =
        categories.map { category ->
            if (category.source == KeyboardPageCategory.Source.RECENT) {
                category.copy(keys = recents.take(LayoutResourceBudget.RECENT_CATEGORY_KEYS).map(::literalKey))
            } else {
                category
            }
        }

    /** A key typing [text], titled with it; the one shape every library symbol takes. */
    fun literalKey(text: String): LayoutKey = LayoutKey(label = text, action = LayoutAction.literal(text))

    /** Grapheme clusters, the way a reader counts the glyphs of a mark. */
    private fun glyphCount(text: String): Int {
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(text)
        var count = 0
        while (iterator.next() != BreakIterator.DONE) count++
        return count
    }
}
