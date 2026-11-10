package app.songyan.ime.layout

import app.songyan.ime.keyboard.engine.InputLanguage
import app.songyan.ime.layout.AlternateKeyPolicy.Language
import app.songyan.ime.layout.AlternateKeyPolicy.Page
import app.songyan.ime.layout.AlternateKeyPolicy.Payload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from LayoutKitTests/AlternateKeyPolicyTests: the set of options, their order, their explicit
 * code/literal semantics and the alphabet/full-width gates.
 */
class AlternateKeyPolicyTest {
    private val fullAlphabet = "abcdefghijklmnopqrstuvwxyz"
    private val clover14Alphabet = "0123456789abcdegjlmoqtuz"

    private fun options(
        key: String,
        mergedGroup: String? = null,
        secondary: List<String> = emptyList(),
        page: Page = Page.LETTERS,
        language: Language = Language.CHINESE,
        uppercase: Boolean = false,
        asciiPunctuation: Boolean = false,
        alphabet: String = "",
        inputLanguage: InputLanguage = InputLanguage.ZH_HANS,
    ): List<AlternateKeyPolicy.Option> =
        AlternateKeyPolicy.options(
            AlternateKeyPolicy.Request(
                keyLabel = key,
                mergedGroup = mergedGroup,
                secondaryLiterals = secondary,
                page = page,
                language = language,
                isUppercase = uppercase,
                usesAsciiPunctuation = asciiPunctuation,
                schemaAlphabet = alphabet,
                inputLanguage = inputLanguage,
            ),
        )

    private fun bundled(key: String) = AlternateKeyPolicy.defaultLetterLiterals(key)

    // MARK: 26-key letters keep their declared digits/symbols

    @Test
    fun chineseLetterKeepsItsDigitsAndSymbolsInOrder() {
        val q = options(key = "q", secondary = bundled("q"), alphabet = fullAlphabet)
        assertEquals(listOf(AlternateKeyPolicy.Option("1", Payload.Literal("1"))), q)
        val a = options(key = "a", secondary = listOf("1", "@"), alphabet = fullAlphabet)
        assertEquals(listOf("1", "@"), a.map { it.label })
        assertTrue(a.all { it.payload == Payload.Literal(it.label) })
    }

    @Test
    fun chineseLetterPreservesExplicitDiyAccentSecondaries() {
        val a = options(key = "a", secondary = listOf("á", "à", "â", "ä"), alphabet = fullAlphabet)
        assertEquals(listOf("á", "à", "â", "ä"), a.map { it.label })
        val mixed = options(key = "a", secondary = listOf("1", "á", "@"), alphabet = fullAlphabet)
        assertEquals(listOf("1", "á", "@"), mixed.map { it.label })
    }

    @Test
    fun theFirstOptionIsTheKeyFaceHint() {
        for (letter in "qwertyuiop".map { it.toString() }) {
            val first = options(key = letter, secondary = bundled(letter), alphabet = fullAlphabet).first()
            assertEquals(letter, bundled(letter).first(), first.label)
        }
    }

    @Test
    fun chineseLetterWithoutDeclaredSecondariesOffersNothing() {
        for (letter in listOf("q", "a", "e", "z")) {
            assertTrue(options(key = letter, alphabet = fullAlphabet).isEmpty())
        }
    }

    @Test
    fun englishLetterKeepsOnlyExplicitOptions() {
        assertEquals(
            listOf("1", "@"),
            options(key = "a", secondary = listOf("1", "@"), language = Language.ENGLISH).map { it.label },
        )
        for (letter in fullAlphabet.map { it.toString() }) {
            assertTrue(options(key = letter, language = Language.ENGLISH).isEmpty())
            assertTrue(options(key = letter, language = Language.CHINESE).isEmpty())
        }
    }

    @Test
    fun englishShiftUppercasesExplicitLettersButNeverDigitsSymbols() {
        val upper =
            options(
                key = "a",
                secondary = listOf("1", "@", "á", "é", "ß"),
                language = Language.ENGLISH,
                uppercase = true,
                alphabet = fullAlphabet,
            )
        assertEquals(listOf("1", "@", "Á", "É", "ß"), upper.map { it.label })
        assertTrue(upper.all { it.payload == Payload.Literal(it.label) })
    }

    @Test
    fun everyDefaultLetterHasUsefulSymbolsWithoutAccents() {
        for (letter in fullAlphabet.map { it.toString() }) {
            val values = bundled(letter)
            assertFalse(letter, values.isEmpty())
            assertEquals(values, bundled(letter.uppercase()))
            assertFalse(values.any { AlternateKeyPolicy.containsNonAsciiLetter(it) })
            for (language in listOf(Language.CHINESE, Language.ENGLISH)) {
                val result =
                    options(
                        key = letter,
                        secondary = values,
                        language = language,
                        asciiPunctuation = language == Language.ENGLISH,
                    )
                assertFalse(letter, result.isEmpty())
                assertTrue(result.all { it.payload == Payload.Literal(it.label) })
                if (language == Language.ENGLISH) {
                    assertTrue(result.all { AlternateKeyPolicy.isAsciiWidth(it.label) })
                }
            }
        }
        for ((letter, digit) in "qwertyuiop".zip("1234567890")) {
            assertEquals(listOf(digit.toString()), bundled(letter.toString()))
        }
        assertEquals(listOf("？", "?"), options(key = "d", secondary = bundled("d")).map { it.label })
        assertEquals(listOf("！", "!"), options(key = "f", secondary = bundled("f")).map { it.label })
        val asciiMarks =
            fullAlphabet
                .flatMap { letter ->
                    options(
                        key = letter.toString(),
                        secondary = bundled(letter.toString()),
                        language = Language.ENGLISH,
                        asciiPunctuation = true,
                    ).map { it.label }
                }.toSet()
        for (
        mark in
        listOf(
            ",",
            ".",
            "?",
            "!",
            "@",
            "/",
            "\\",
            ":",
            ";",
            "-",
            "_",
            "(",
            ")",
            "[",
            "]",
            "<",
            ">",
            "\"",
            "'",
        )
        ) {
            assertTrue("missing everyday symbol " + mark, mark in asciiMarks)
        }
        assertTrue(bundled("ABC").isEmpty())
    }

    @Test
    fun noTwoLettersOfferTheSameSymbolInAnyMode() {
        val modes =
            listOf(
                Triple(Language.CHINESE, false, false),
                Triple(Language.CHINESE, true, true),
                Triple(Language.ENGLISH, true, true),
            )
        for ((language, ascii, _) in modes) {
            val owner = mutableMapOf<String, String>()
            val hints = mutableMapOf<String, String>()
            for (letter in fullAlphabet.map { it.toString() }) {
                val labels =
                    options(
                        key = letter,
                        secondary = bundled(letter),
                        language = language,
                        asciiPunctuation = ascii,
                        alphabet = fullAlphabet,
                    ).map { it.label }
                for (label in labels) {
                    val other = owner.put(label, letter)
                    assertNull(
                        "" + language + " ascii=" + ascii + ": " + label + " on " + other + " and " + letter,
                        other,
                    )
                }
                labels.firstOrNull()?.let { hint ->
                    assertNull(
                        "" + language + " ascii=" + ascii + ": hint " + hint + " repeats",
                        hints.put(hint, letter),
                    )
                }
            }
        }
    }

    @Test
    fun mergedPadDeclaredSymbolsDoNotRepeat() {
        for (layout in listOf(BuiltinLayouts.merged14, BuiltinLayouts.merged18)) {
            val seen = mutableMapOf<String, String>()
            for (key in layout.rows.flatMap { it.keys }) {
                if (key.action.type != LayoutActionKind.CODE) continue
                val partners =
                    key.label
                        .lowercase()
                        .map { it.toString() }
                        .toSet()
                val symbols =
                    (key.longPress ?: emptyList()).mapNotNull { it.action.value }.filter { it !in partners }
                for (symbol in symbols) {
                    val other = seen.put(symbol, key.label)
                    assertNull(layout.id + ": " + symbol + " on " + other + " and " + key.label, other)
                }
            }
        }
    }

    @Test
    fun explicitDiyAccentsHaveMatchingLabelAndLiteral() {
        assertEquals(
            listOf(AlternateKeyPolicy.Option("á", Payload.Literal("á"))),
            options(key = "a", secondary = listOf("á"), language = Language.ENGLISH),
        )
        assertEquals(
            listOf(AlternateKeyPolicy.Option("Á", Payload.Literal("Á"))),
            options(key = "a", secondary = listOf("á"), language = Language.ENGLISH, uppercase = true),
        )
    }

    // MARK: 14-key partners gated by the schema alphabet

    @Test
    fun clover14RepresentativeOnlyAlphabetHidesEveryPartner() {
        for (
        group in
        listOf("qw", "er", "ty", "ui", "op", "as", "df", "gh", "jk", "zx", "cv", "bn")
        ) {
            assertTrue(
                group,
                options(
                    key = group.take(1),
                    mergedGroup = group,
                    alphabet = clover14Alphabet,
                ).isEmpty(),
            )
        }
    }

    @Test
    fun mergedPartnerIsACodeWhenTheAlphabetContainsIt() {
        assertEquals(
            listOf(AlternateKeyPolicy.Option("w", Payload.Code("w"))),
            options(key = "q", mergedGroup = "qw", alphabet = fullAlphabet),
        )
    }

    @Test
    fun mergedPartnersPreserveGroupOrderAndNeverLiteral() {
        val result = options(key = "q", mergedGroup = "qwe", alphabet = fullAlphabet)
        assertEquals(listOf(Payload.Code("w"), Payload.Code("e")), result.map { it.payload })
        assertTrue(result.all { it.payload is Payload.Code && AlternateKeyPolicy.isValid(it) })
    }

    @Test
    fun mergedPartnerAlphabetMatchIsCaseInsensitive() {
        assertEquals(
            listOf(Payload.Code("w")),
            options(key = "q", mergedGroup = "qw", alphabet = fullAlphabet.uppercase()).map { it.payload },
        )
    }

    @Test
    fun unknownAlphabetFailsClosed() {
        assertTrue(options(key = "q", mergedGroup = "qw", alphabet = "").isEmpty())
    }

    // MARK: T9 ABC is deliberately deferred

    @Test
    fun phoneLetterGroupYieldsNothingYet() {
        assertTrue(
            options(key = "ABC", alphabet = "0123456789abcdefghijklmnopqrstuvwxyz").isEmpty(),
        )
        assertTrue(options(key = "WXYZ", language = Language.ENGLISH).isEmpty())
        assertTrue(options(key = "2", alphabet = "0123456789").isEmpty())
    }

    // MARK: Numeric / symbol pages

    @Test
    fun numericPageDigitsOfferChineseNumeralsInFullWidthOnly() {
        val one = options(key = "1", page = Page.NUMERIC)
        assertEquals(listOf("一", "壹", "①"), one.map { it.label })
        assertTrue(one.all { it.payload == Payload.Literal(it.label) })
        assertEquals(listOf("零", "〇", "°"), options(key = "0", page = Page.NUMERIC).map { it.label })
        assertTrue(
            options(key = "1", page = Page.NUMERIC, language = Language.ENGLISH, asciiPunctuation = true).isEmpty(),
        )
        assertEquals(
            listOf("°"),
            options(key = "0", page = Page.NUMERIC, language = Language.ENGLISH, asciiPunctuation = true)
                .map { it.label },
        )
    }

    @Test
    fun numericPageListsNeverOfferAVisibleKey() {
        val pages =
            listOf(
                false to
                    (
                        "1234567890".map { it.toString() } +
                            listOf("-", "/", "：", "；", "（", "）", "¥", "@", "“", "”") +
                            listOf("。", "，", "、", "？", "！", ".")
                    ),
                true to "1234567890-/:;()\$&@\".,?!'".map { it.toString() },
            )
        for ((ascii, keys) in pages) {
            val visible = keys.toSet()
            for (key in keys) {
                val labels = options(key = key, page = Page.NUMERIC, asciiPunctuation = ascii).map { it.label }
                assertTrue(key + " offers a visible key: " + labels, visible.intersect(labels.toSet()).isEmpty())
            }
        }
    }

    @Test
    fun symbolPageNeverOffersAKeyItShows() {
        assertEquals(listOf("·"), options(key = "、", page = Page.NUMERIC).map { it.label })
        assertTrue(
            AlternateKeyPolicy
                .options(
                    AlternateKeyPolicy.Request(
                        keyLabel = "、",
                        page = Page.SYMBOLS,
                        language = Language.CHINESE,
                        visibleKeys = setOf("、", "·", "——"),
                    ),
                ).isEmpty(),
        )
        val yen =
            AlternateKeyPolicy.options(
                AlternateKeyPolicy.Request(
                    keyLabel = "¥",
                    page = Page.SYMBOLS,
                    language = Language.ENGLISH,
                    usesAsciiPunctuation = true,
                    visibleKeys = setOf("€", "£", "¥"),
                ),
            )
        assertEquals(listOf("$"), yen.map { it.label })
    }

    /** Three keys side by side never carry the same corner hint: with $ € £ on the page, only $ hints ¥. */
    @Test
    fun currencyKeysOnOnePageDoNotAllHintYen() {
        fun hint(key: String) =
            AlternateKeyPolicy
                .options(
                    AlternateKeyPolicy.Request(
                        keyLabel = key,
                        page = Page.SYMBOLS,
                        language = Language.CHINESE,
                        visibleKeys = setOf("$", "€", "£"),
                    ),
                ).firstOrNull()
                ?.label
        assertEquals("¥", hint("$"))
        assertNull(hint("€"))
        assertNull(hint("£"))
    }

    @Test
    fun numericPagePunctuationVariantDoesNotRepeatTheKey() {
        val dash = options(key = "-", page = Page.NUMERIC)
        assertFalse(dash.any { it.label == "-" })
        assertTrue(dash.any { it.label == "—" })
    }

    @Test
    fun symbolsPageOperatorsOfferVariants() {
        val star = options(key = "*", page = Page.SYMBOLS)
        assertTrue(star.any { it.label == "×" })
        assertTrue(star.any { it.label == "°" })
        assertTrue(options(key = "☃", page = Page.SYMBOLS).isEmpty())
    }

    // MARK: Full/half-width field policy

    @Test
    fun fullwidthPredicateCoversCjkAndFullwidthFormsOnly() {
        for (value in listOf("，", "。", "、", "；", "：", "？", "！", "「", "」", "￥")) {
            assertTrue(value, AlternateKeyPolicy.isFullwidthOrCjkPunctuation(value))
        }
        for (
        value in
        listOf(
            ",",
            ".",
            "?",
            "!",
            ";",
            ":",
            "…",
            "—",
            "–",
            "“",
            "”",
            "$",
            "€",
            "¥",
            "£",
            "÷",
            "×",
            "±",
            "≈",
            "≠",
            "№",
            "‰",
            "°",
            "¦",
            "·",
            "•",
        )
        ) {
            assertFalse(value, AlternateKeyPolicy.isFullwidthOrCjkPunctuation(value))
        }
    }

    @Test
    fun asciiFieldAllowListIsExactlyTheNamedCurrencyAndMathSymbols() {
        for (
        value in
        listOf("€", "£", "¥", "±", "÷", "×", "≈", "≠", "№", "‰", "°", ".", ",", "a", "1", "á", "é")
        ) {
            assertTrue(value, AlternateKeyPolicy.isAllowedInAsciiField(value))
        }
        for (
        value in
        listOf(
            "…",
            "—",
            "–",
            "“",
            "”",
            "‘",
            "’",
            "¿",
            "¡",
            "·",
            "•",
            "〈",
            "〉",
            "‹",
            "›",
            "¦",
            "©",
            "®",
            "™",
            "§",
            "′",
            "￥",
            "，",
            "。",
            "「",
            "」",
            "？！",
        )
        ) {
            assertFalse(value, AlternateKeyPolicy.isAllowedInAsciiField(value))
        }
    }

    @Test
    fun asciiFieldOffersNoFullwidthPunctuation() {
        for (key in listOf("。", "，", "、", "“", "（", "-", "/", "¥", "￥", "1")) {
            val labels = options(key = key, page = Page.NUMERIC, asciiPunctuation = true).map { it.label }
            assertTrue(key, labels.all { AlternateKeyPolicy.isAllowedInAsciiField(it) })
            assertFalse(key, labels.any { AlternateKeyPolicy.isFullwidthOrCjkPunctuation(it) })
        }
    }

    @Test
    fun asciiFieldRejectsNonAsciiPunctuationOutsideFullwidthBlocks() {
        assertFalse(
            options(key = "。", page = Page.NUMERIC, asciiPunctuation = true).any { it.label == "…" },
        )
        val dash = options(key = "-", page = Page.NUMERIC, asciiPunctuation = true)
        assertFalse(dash.any { it.label == "—" || it.label == "–" || it.label == "·" })
        assertTrue(dash.all { AlternateKeyPolicy.isAsciiWidth(it.label) })
        val quote = options(key = "\"", page = Page.SYMBOLS, asciiPunctuation = true)
        assertFalse(quote.any { it.label == "“" || it.label == "”" || it.label == "「" })
        assertTrue(quote.all { AlternateKeyPolicy.isAsciiWidth(it.label) })
        val bracket = options(key = "<", page = Page.SYMBOLS, asciiPunctuation = true)
        assertFalse(bracket.any { it.label == "〈" })
        assertTrue(bracket.all { AlternateKeyPolicy.isAsciiWidth(it.label) })
    }

    @Test
    fun asciiFieldFiltersSecondariesToo() {
        val ascii =
            options(
                key = "a",
                secondary = listOf("1", "，", "@", "…", "—"),
                asciiPunctuation = true,
                alphabet = fullAlphabet,
            )
        assertEquals(listOf("1", "@"), ascii.map { it.label })
    }

    @Test
    fun asciiFieldKeepsEnglishAccentVariants() {
        val a =
            options(
                key = "a",
                secondary = listOf("1", "…", "á"),
                language = Language.ENGLISH,
                asciiPunctuation = true,
                alphabet = fullAlphabet,
            )
        assertEquals("1", a.first().label)
        assertFalse(a.any { it.label == "…" })
        assertTrue(a.any { it.label == "á" })
        assertTrue(
            options(
                key = "e",
                secondary = listOf("é"),
                language = Language.ENGLISH,
                uppercase = true,
                asciiPunctuation = true,
            ).any { it.label == "É" },
        )
    }

    @Test
    fun fullwidthMarksOfferTheirHalfwidthFormFirst() {
        assertEquals(listOf(","), options(key = "，", page = Page.NUMERIC).map { it.label })
        assertEquals(listOf(":"), options(key = "：", page = Page.NUMERIC).map { it.label })
        assertEquals(listOf("(", "【", "[", "{"), options(key = "（", page = Page.NUMERIC).map { it.label })
        assertEquals("「", options(key = "“", page = Page.NUMERIC).first().label)
    }

    @Test
    fun currencyAndMathFormsAreNotWidthVariantsAndSurviveAscii() {
        assertEquals(
            listOf("$", "€", "£"),
            options(key = "¥", page = Page.NUMERIC, asciiPunctuation = true).map { it.label },
        )
        assertEquals(
            listOf("¥", "$", "€", "£"),
            options(key = "￥", page = Page.NUMERIC, asciiPunctuation = true).map { it.label },
        )
        assertEquals(
            listOf("*", "°"),
            options(key = "×", page = Page.SYMBOLS, asciiPunctuation = true).map { it.label },
        )
        assertEquals(listOf("/"), options(key = "÷", page = Page.SYMBOLS, asciiPunctuation = true).map { it.label })
        assertEquals(listOf("±"), options(key = "+", page = Page.SYMBOLS, asciiPunctuation = true).map { it.label })
        assertEquals(
            listOf("≈", "≠"),
            options(key = "=", page = Page.SYMBOLS, asciiPunctuation = true).map { it.label },
        )
        assertEquals(listOf("№"), options(key = "#", page = Page.SYMBOLS, asciiPunctuation = true).map { it.label })
        assertEquals(listOf("‰"), options(key = "%", page = Page.SYMBOLS, asciiPunctuation = true).map { it.label })
    }

    @Test
    fun orderedByFieldWidthIsStableAndFiltering() {
        assertEquals(
            listOf(",", ";"),
            AlternateKeyPolicy.orderedByFieldWidth(listOf("，", ",", "、", ";"), asciiFirst = true),
        )
        assertEquals(
            listOf("，", "、", ",", ";"),
            AlternateKeyPolicy.orderedByFieldWidth(listOf("，", ",", "、", ";"), asciiFirst = false),
        )
        val mixed =
            listOf(
                ".",
                ",",
                "…",
                "—",
                "“",
                "·",
                "•",
                "〈",
                "€",
                "£",
                "¥",
                "±",
                "÷",
                "×",
                "≈",
                "≠",
                "№",
                "‰",
                "°",
            )
        assertEquals(
            listOf(".", ",", "€", "£", "¥", "±", "÷", "×", "≈", "≠", "№", "‰", "°"),
            AlternateKeyPolicy.orderedByFieldWidth(mixed, asciiFirst = true),
        )
        assertEquals(
            mixed.toSet(),
            AlternateKeyPolicy.orderedByFieldWidth(mixed, asciiFirst = false).toSet(),
        )
    }

    @Test
    fun asciiWidthHelper() {
        assertTrue(AlternateKeyPolicy.isAsciiWidth(","))
        assertFalse(AlternateKeyPolicy.isAsciiWidth("，"))
        assertFalse(AlternateKeyPolicy.isAsciiWidth(""))
    }

    @Test
    fun everyEmittableOptionObeysThePayloadContract() {
        val requests = mutableListOf<AlternateKeyPolicy.Request>()
        for (key in listOf("a", "q", "1", ".", "，", "—", "*", "ABC", "☃")) {
            for (page in listOf(Page.LETTERS, Page.NUMERIC, Page.SYMBOLS)) {
                for (language in listOf(Language.CHINESE, Language.ENGLISH)) {
                    for (ascii in listOf(false, true)) {
                        requests.add(
                            AlternateKeyPolicy.Request(
                                keyLabel = key,
                                mergedGroup = "qw",
                                secondaryLiterals = listOf("1", "，", "@"),
                                page = page,
                                language = language,
                                isUppercase = ascii,
                                usesAsciiPunctuation = ascii,
                                schemaAlphabet = fullAlphabet,
                            ),
                        )
                    }
                }
            }
        }
        for (request in requests) {
            for (option in AlternateKeyPolicy.options(request)) {
                assertTrue(request.toString() + " -> " + option, AlternateKeyPolicy.isValid(option))
            }
        }
    }

    @Test
    fun aNonAsciiMergedPartnerIsNeverEmittedAsACode() {
        // The alphabet is a live schema field; a non-ASCII letter there must not produce a Code
        // payload (one printable ASCII character is the declared contract).
        val partner =
            AlternateKeyPolicy.options(
                AlternateKeyPolicy.Request(
                    keyLabel = "q",
                    mergedGroup = "qé",
                    page = Page.LETTERS,
                    language = Language.CHINESE,
                    usesAsciiPunctuation = false,
                    schemaAlphabet = "qé",
                ),
            )
        assertTrue(partner.none { it.payload is AlternateKeyPolicy.Payload.Code })
        assertNull(AlternateKeyPolicy.letterSwipeCodes(listOf('q', 'é'), "qé"))
    }

    @Test
    fun letterSwipeNeedsBothExactLetters() {
        assertEquals(
            listOf("w", "e"),
            AlternateKeyPolicy.letterSwipeCodes(listOf('w', 'e'), fullAlphabet),
        )
        assertEquals(
            listOf("w", "e"),
            AlternateKeyPolicy.letterSwipeCodes(listOf('W', 'E'), "zyxwvutsrqponmlkjihgfedcba"),
        )
        assertNull(AlternateKeyPolicy.letterSwipeCodes(listOf('u', 'i'), clover14Alphabet))
        assertNull(AlternateKeyPolicy.letterSwipeCodes(listOf('q'), "q"))
        assertNull(AlternateKeyPolicy.letterSwipeCodes(listOf('a', 'b', 'c'), "abc"))
    }

    // MARK: Input script language selects the bundled defaults only

    @Test
    fun theScriptLanguageChoosesTheBundledLetterMarks() {
        assertEquals(
            listOf("「", "『", "“", "\""),
            AlternateKeyPolicy.defaultLetterLiterals("c", InputLanguage.ZH_HANT),
        )
        assertEquals(listOf("、"), AlternateKeyPolicy.defaultLetterLiterals("a", InputLanguage.JA))
        assertEquals(listOf("・"), AlternateKeyPolicy.defaultLetterLiterals("g", InputLanguage.JA))
        val jaX = AlternateKeyPolicy.defaultLetterLiterals("x", InputLanguage.JA)
        assertTrue(jaX.containsAll(listOf("ー", "～")))
        assertTrue(AlternateKeyPolicy.defaultLetterLiterals("g", InputLanguage.JA).contains("・"))
        // ー is a letter, not punctuation: the payload contract must keep it as a literal.
        assertTrue(AlternateKeyPolicy.containsNonAsciiLetter("ー"))
        val options =
            options(
                key = "x",
                secondary = jaX,
                inputLanguage = InputLanguage.JA,
                alphabet = fullAlphabet,
            )
        assertTrue(options.map { it.label }.containsAll(jaX))
        assertEquals(jaX.toSet(), options.map { it.label }.toSet())
        assertTrue(options.all { it.payload is Payload.Literal })
        assertTrue(AlternateKeyPolicy.isAllowedInAsciiField("ー"))
    }

    @Test
    fun declaredLiteralsStayAsWrittenInEveryScriptLanguage() {
        val diy = listOf("自", "定")
        for (language in InputLanguage.entries) {
            val shown =
                options(
                    key = "a",
                    secondary = diy,
                    inputLanguage = language,
                    alphabet = fullAlphabet,
                )
            assertEquals("$language", diy, shown.map { it.label })
        }
    }

    @Test
    fun japaneseSymbolLongPressOffersItsOwnMarks() {
        val marks =
            options(
                key = "-",
                page = Page.SYMBOLS,
                inputLanguage = InputLanguage.JA,
            ).map { it.label }
        assertTrue(marks.containsAll(listOf("ー", "～")))
        val stop =
            options(
                key = "。",
                page = Page.SYMBOLS,
                inputLanguage = InputLanguage.JA,
            ).map { it.label }
        assertEquals("、", stop.firstOrNull())
    }

    @Test
    fun theEngineDispatchLanguageKeepsItsOwnMeaning() {
        // The script language never changes the engine mode: merged partners stay codes.
        val merged =
            options(
                key = "q",
                mergedGroup = "qw",
                inputLanguage = InputLanguage.JA,
                alphabet = fullAlphabet,
            )
        assertEquals(
            listOf(AlternateKeyPolicy.Option("w", Payload.Code("w"))),
            merged,
        )
    }

    @Test
    fun theDollarSignOffersTheYuanSignFirst() {
        val options =
            AlternateKeyPolicy
                .options(
                    AlternateKeyPolicy.Request(
                        keyLabel = "$",
                        page = AlternateKeyPolicy.Page.SYMBOLS,
                        language = AlternateKeyPolicy.Language.CHINESE,
                    ),
                ).map { it.label }
        assertEquals("¥", options.first())
    }
}
