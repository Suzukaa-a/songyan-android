package app.songyan.ime.ime

import app.songyan.ime.keyboard.layout.CompactFold
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.layout.KeyboardPageKind
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutPageTarget
import app.songyan.ime.layout.LayoutRow
import app.songyan.ime.layout.ShuangpinHintPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardPagesTest {
    private val letters =
        KeyboardPages.Config(
            layout = KeyboardLayoutKind.QWERTY26,
            englishDirectInput = false,
            symbolMarksAscii = false,
            languageSwitch = true,
            lettersReachable = true,
        )

    private fun actions(
        config: KeyboardPages.Config,
        page: KeyboardPageKind? = null,
    ) = KeyboardPages.definition(page, config).rows.map { row -> row.keys.map { it.action.type } }

    @Test
    fun lettersPageIsFourRowsWithShiftAndDeleteFoldedIn() {
        val definition = KeyboardPages.definition(null, letters)
        assertEquals(4, definition.rows.size)
        assertEquals("q", definition.rows[0].keys[0].label)
        val lastLetterRow = definition.rows[2].keys
        assertEquals(LayoutActionKind.SHIFT, lastLetterRow.first().action.type)
        assertEquals(LayoutActionKind.BACKSPACE, lastLetterRow.last().action.type)
    }

    @Test
    fun lettersFooterFollowsTheEditor() {
        val footer =
            KeyboardPages
                .definition(null, letters)
                .rows
                .last()
                .keys
        // 123 | 中/英 | ，| 空格 | 。| 回车. No globe key: the navigation bar owns the switcher.
        assertEquals(
            listOf(
                LayoutActionKind.PAGE_SWITCH,
                LayoutActionKind.LANGUAGE_SWITCH,
                LayoutActionKind.LITERAL,
                LayoutActionKind.SPACE,
                LayoutActionKind.LITERAL,
                LayoutActionKind.ENTER,
            ),
            footer.map { it.action.type },
        )
        assertEquals(listOf("123", "中/英", "，", "空格", "。", "⏎"), footer.map { it.label })
        // The comma holds the pauses and the full stop the sentence ends.
        assertEquals(listOf("、", "：", "；"), footer[2].longPress?.map { it.label })
        assertEquals(listOf("？", "！", "…"), footer[4].longPress?.map { it.label })
        // The space bar keeps one share on every page, so every bottom row lines up.
        assertEquals(KeyboardPages.FooterWeight.SPACE, footer[3].widthWeight, 0.001)
        // The marks either side of the space bar match; Return takes the rest of the right side.
        assertEquals(footer[2].widthWeight, footer[4].widthWeight, 0.001)
        assertTrue(footer[5].widthWeight > footer[4].widthWeight)
        val forcedAscii =
            KeyboardPages
                .definition(null, letters.copy(languageSwitch = false))
                .rows
                .last()
                .keys
        assertTrue(forcedAscii.none { it.action.type == LayoutActionKind.LANGUAGE_SWITCH })
        val numericEditor =
            KeyboardPages
                .definition(null, letters.copy(lettersReachable = false))
                .rows
                .last()
                .keys
        assertTrue(
            numericEditor.none { it.action.type == LayoutActionKind.PAGE_SWITCH && it.action.value == "letters" },
        )
    }

    @Test
    fun englishPageUsesCodesSoShiftOwnsTheCase() {
        val definition = KeyboardPages.definition(null, letters.copy(englishDirectInput = true))
        val key = definition.rows[0].keys[0]
        assertEquals(LayoutActionKind.CODE, key.action.type)
        assertEquals("q", key.action.value)
        assertEquals(
            LayoutActionKind.SHIFT,
            definition.rows[2]
                .keys
                .first()
                .action.type,
        )
        assertEquals(
            LayoutActionKind.BACKSPACE,
            definition.rows[2]
                .keys
                .last()
                .action.type,
        )
    }

    @Test
    fun numericPageCarriesTenColumnsAndAThirdRowOfSwitchMarksAndDelete() {
        val definition = KeyboardPages.definition(KeyboardPageKind.NUMERIC, letters)
        assertEquals(4, definition.rows.size)
        assertEquals(listOf(10, 10), definition.rows.take(2).map { it.keys.size })
        val lastDataRow = definition.rows[2].keys
        assertEquals(9, lastDataRow.size)
        assertEquals(LayoutPageTarget.SYMBOLS, lastDataRow.first().action.pageSwitchTarget)
        assertEquals("符号", lastDataRow.first().label)
        assertEquals(LayoutActionKind.BACKSPACE, lastDataRow.last().action.type)
        assertEquals(
            LayoutActionKind.LITERAL,
            definition.rows[0]
                .keys[0]
                .action.type,
        )
        assertEquals(
            "1",
            definition.rows[0]
                .keys[0]
                .action.value,
        )
        assertEquals(
            "1",
            definition.rows[0]
                .keys[0]
                .label
                .let { it },
        )
    }

    @Test
    fun numericFooterHasNoSymbolKeyAndKeepsTheCommaSlot() {
        for (ascii in listOf(false, true)) {
            val config = letters.copy(symbolMarksAscii = ascii)
            val footer =
                KeyboardPages
                    .definition(KeyboardPageKind.NUMERIC, config)
                    .rows
                    .last()
                    .keys
            assertEquals(
                if (ascii) {
                    listOf("返回", "中/英", "空格", ",", "⏎")
                } else {
                    listOf("返回", "中/英", "，", "空格", "。", "⏎")
                },
                footer.map { it.label },
            )
            assertTrue(footer.none { it.action.pageSwitchTarget == LayoutPageTarget.SYMBOLS })
            // The comma holds the slot it has on the letters footer, so it stays put when the page flips.
            val lettersFooter = KeyboardPages.lettersFooter(config.copy(englishDirectInput = ascii))
            assertEquals(
                lettersFooter.first { it.label == if (ascii) "," else "，" }.label,
                footer.first { it.label == if (ascii) "," else "，" }.label,
            )
        }
    }

    @Test
    fun theLanguageKeyReadsTheSameOnLettersNumbersAndSymbols() {
        for (page in listOf(null, KeyboardPageKind.NUMERIC, KeyboardPageKind.SYMBOLS)) {
            for (ascii in listOf(false, true)) {
                val keys =
                    KeyboardPages
                        .definition(page, letters.copy(symbolMarksAscii = ascii))
                        .rows
                        .flatMap { it.keys }
                        .filter { it.action.type == LayoutActionKind.LANGUAGE_SWITCH }
                assertEquals("$page $ascii", listOf("中/英"), keys.map { it.label })
            }
        }
    }

    @Test
    fun numericPageMarksFollowTheSymbolBranch() {
        val chinese = KeyboardPages.definition(KeyboardPageKind.NUMERIC, letters)
        val ascii = KeyboardPages.definition(KeyboardPageKind.NUMERIC, letters.copy(symbolMarksAscii = true))
        assertEquals("（", chinese.rows[1].keys[4].label)
        assertEquals("(", ascii.rows[1].keys[4].label)
    }

    @Test
    fun symbolPageSwitchesToNumbersFromItsThirdRowAndKeepsTheCommaSlot() {
        val definition = KeyboardPages.definition(KeyboardPageKind.SYMBOLS, letters)
        val third = definition.rows[2].keys
        assertEquals(9, third.size)
        assertEquals(LayoutPageTarget.NUMERIC, third.first().action.pageSwitchTarget)
        assertEquals("123", third.first().label)
        assertEquals(LayoutActionKind.BACKSPACE, third.last().action.type)
        val footer = definition.rows.last().keys
        // The page types every mark itself, so 更多 takes the full stop's place beside the space bar.
        assertEquals(listOf("返回", "中/英", "，", "空格", "更多", "⏎"), footer.map { it.label })
        assertTrue(footer.none { it.action.type == LayoutActionKind.SYMBOL_LOCK })
        assertTrue(footer.none { it.action.pageSwitchTarget == LayoutPageTarget.NUMERIC })
        assertEquals(
            "",
            definition.rows[0]
                .keys[0]
                .label
                .let { if (it == "【") "" else it },
        )
    }

    @Test
    fun mergedKeysKeepTheirGroupAndAlternates() {
        val definition = KeyboardPages.definition(null, letters.copy(layout = KeyboardLayoutKind.MERGED14))
        val first = definition.rows[0].keys[0]
        assertEquals("qw", first.label)
        assertEquals("q", first.action.value)
        assertEquals(listOf("w", "1", "!"), first.longPress?.map { it.label })
    }

    @Test
    fun mergeSwipeNeedsBothLettersInTheSchemaAlphabet() {
        val key =
            LayoutKey(
                "QW",
                action =
                    app.songyan.ime.layout.LayoutAction
                        .code("q"),
            )
        val config =
            MergedSwipeConfig(
                CompactFold.MERGED14,
                "!#%*+<>@^_abcdefghijklmnopqrstuvwxyz|~",
                useExactTokens = true,
            )
        assertEquals(listOf("!", "w"), config.codesFor(key))
        assertEquals(listOf("q", "w"), config.lettersFor(key))
        assertNull(MergedSwipeConfig(CompactFold.MERGED14, "q", useExactTokens = true).codesFor(key))
        assertNull(
            config.codesFor(
                LayoutKey(
                    "q",
                    action =
                        app.songyan.ime.layout.LayoutAction
                            .code("q"),
                ),
            ),
        )
    }

    @Test
    fun mergeSwipeWithoutAProvenAlphabetNeverSendsAToken() {
        val key =
            LayoutKey(
                "QW",
                action =
                    app.songyan.ime.layout.LayoutAction
                        .code("q"),
            )
        val config = MergedSwipeConfig(CompactFold.MERGED14, "abcdefghijklmnopqrstuvwxyz", useExactTokens = false)
        assertEquals(listOf("q", "w"), config.codesFor(key))
        val zx = MergedSwipeConfig(CompactFold.MERGED14, "abcdefghijklmnopqrstuvwxyz", useExactTokens = false)
        assertEquals(
            listOf("z", "x"),
            zx.codesFor(
                LayoutKey(
                    "ZX",
                    action =
                        app.songyan.ime.layout.LayoutAction
                            .code("z"),
                ),
            ),
        )
    }

    @Test
    fun mergedKeyCornerJargonIsNeverDrawn() {
        val definition = KeyboardPages.definition(null, letters.copy(layout = KeyboardLayoutKind.MERGED14))
        assertTrue(definition.rows.flatMap { it.keys }.none { it.secondaryLabel == "合并键" })
        assertEquals("1", definition.rows[0].keys[0].secondaryLabel)
    }

    @Test
    fun theSpaceBarSitsInTheSamePlaceOnEveryChineseLayout() {
        val shares =
            KeyboardLayoutKind.entries.filter { it != KeyboardLayoutKind.PHONE9 }.map { layout ->
                val footer =
                    KeyboardPages
                        .definition(null, letters.copy(layout = layout))
                        .rows
                        .last()
                        .keys
                val total = footer.sumOf { it.widthWeight }
                val before = footer.takeWhile { it.action.type != LayoutActionKind.SPACE }.sumOf { it.widthWeight }
                before / total to footer.first { it.action.type == LayoutActionKind.SPACE }.widthWeight / total
            }
        assertEquals(1, shares.distinct().size)
    }

    @Test
    fun theMarksBesideTheSpaceBarFollowLanguageAndField() {
        fun marks(config: KeyboardPages.Config): List<String> {
            val footer =
                KeyboardPages
                    .definition(null, config)
                    .rows
                    .last()
                    .keys
            return footer.filter { it.action.type == LayoutActionKind.LITERAL }.map { it.label }
        }
        assertEquals(listOf("，", "。"), marks(letters))
        assertEquals(
            listOf("、", "。"),
            marks(letters.copy(inputLanguage = app.songyan.ime.keyboard.engine.InputLanguage.JA)),
        )
        assertEquals(listOf(","), marks(letters.copy(englishDirectInput = true)))
        val email = letters.copy(englishDirectInput = true, languageSwitch = false, field = FieldKind.EMAIL)
        assertEquals(listOf("@", "."), marks(email))
        val url = letters.copy(englishDirectInput = true, field = FieldKind.URL)
        assertEquals(listOf("."), marks(url))
        val urlStop = KeyboardPages.lettersFooter(url).first { it.label == "." }
        assertEquals("/", urlStop.longPress?.first()?.label)
        // The URL field keeps its 中/英 key; an email field has none.
        assertTrue(
            KeyboardPages.definition(null, url).rows.last().keys.any {
                it.action.type ==
                    LayoutActionKind.LANGUAGE_SWITCH
            },
        )
        assertTrue(
            KeyboardPages.definition(null, email).rows.last().keys.none {
                it.action.type ==
                    LayoutActionKind.LANGUAGE_SWITCH
            },
        )
    }

    /** The keys left of the space bar weigh what the keys right of it weigh, whatever the editor drops. */
    @Test
    fun theSpaceBarIsCentredOnEveryFooter() {
        val configs =
            listOf(
                letters,
                letters.copy(languageSwitch = false),
                letters.copy(englishDirectInput = true, languageSwitch = false, field = FieldKind.EMAIL),
                letters.copy(englishDirectInput = true, field = FieldKind.URL),
                letters.copy(englishDirectInput = true, languageSwitch = false, field = FieldKind.PASSWORD),
            )
        for (config in configs) {
            for (page in listOf(null, KeyboardPageKind.NUMERIC, KeyboardPageKind.SYMBOLS)) {
                val footer =
                    KeyboardPages
                        .definition(page, config)
                        .rows
                        .last()
                        .keys
                val space = footer.indexOfFirst { it.action.type == LayoutActionKind.SPACE }
                val left = footer.take(space).sumOf { it.widthWeight }
                val right = footer.drop(space + 1).sumOf { it.widthWeight }
                assertEquals("$page $config", left, right, 0.0001)
            }
        }
    }

    @Test
    fun aPasswordFieldGetsADigitRowAndKeepsTheFourRowHeight() {
        val password =
            KeyboardPages.definition(
                null,
                letters.copy(englishDirectInput = true, field = FieldKind.PASSWORD),
            )
        assertEquals(5, password.rows.size)
        assertEquals("1234567890".map { it.toString() }, password.rows[0].keys.map { it.label })
    }

    @Test
    fun chineseLetterKeysCarryTheIosAlternatesAndShowTheFirstInTheCorner() {
        val definition = KeyboardPages.definition(null, letters)
        val keys =
            definition.rows
                .take(3)
                .flatMap { it.keys }
                .filter { it.action.type == LayoutActionKind.CODE }

        fun key(letter: String) = keys.first { it.label == letter }
        assertEquals("1", key("q").secondaryLabel)
        assertEquals("，", key("a").secondaryLabel)
        assertEquals("。", key("s").secondaryLabel)
        assertEquals("？", key("d").secondaryLabel)
        assertEquals("！", key("f").secondaryLabel)
        assertEquals(listOf("。", ".", "…"), key("s").longPress?.map { it.label })
        assertTrue(keys.all { !it.longPress.isNullOrEmpty() && !it.secondaryLabel.isNullOrEmpty() })
    }

    @Test
    fun singleLettersOnMergedLayoutsAreCompletedLikeThe26KeyPage() {
        val merged = KeyboardPages.definition(null, letters.copy(layout = KeyboardLayoutKind.MERGED18))
        val single =
            merged.rows
                .take(3)
                .flatMap { it.keys }
                .first { it.label == "a" }
        assertEquals("，", single.secondaryLabel)
        val l =
            merged.rows
                .take(3)
                .flatMap { it.keys }
                .first { it.label == "l" }
        assertEquals("，", l.longPress?.first()?.label)
    }

    @Test
    fun mergedPairsShowAMarkTheirLongPressTypes() {
        fun pair(
            layout: KeyboardLayoutKind,
            label: String,
        ) = KeyboardPages
            .definition(null, letters.copy(layout = layout))
            .rows
            .take(3)
            .flatMap { it.keys }
            .first { it.label == label }
        val df = pair(KeyboardLayoutKind.MERGED14, "df")
        assertEquals("。", df.secondaryLabel)
        assertEquals(listOf("f", "。", ".", "…"), df.longPress?.map { it.label })
        // The QW key's decorative "1 !" is not one alternate, so the corner shows the one a flick types.
        val qw = pair(KeyboardLayoutKind.MERGED14, "qw")
        assertEquals("1", qw.secondaryLabel)
        assertEquals("w", qw.longPress?.first()?.label)
        assertEquals("？", pair(KeyboardLayoutKind.MERGED18, "fg").secondaryLabel)
        assertEquals("-", pair(KeyboardLayoutKind.MERGED18, "xc").secondaryLabel)
    }

    @Test
    fun shuangpinHintsGoOnLayoutsWhoseKeysCoverTheAlphabetOnce() {
        val xiaohe = ShuangpinHintPresets.builtinXiaohe.hints
        assertEquals(xiaohe, KeyboardPages.syllableHints(letters, xiaohe))
        assertEquals(xiaohe, KeyboardPages.syllableHints(letters.copy(layout = KeyboardLayoutKind.MERGED14), xiaohe))
        // Not on the nine-key pad, and not while typing English.
        assertTrue(KeyboardPages.syllableHints(letters.copy(layout = KeyboardLayoutKind.PHONE9), xiaohe).isEmpty())
        assertTrue(KeyboardPages.syllableHints(letters.copy(englishDirectInput = true), xiaohe).isEmpty())
        // The corner keeps the long-press mark either way.
        assertEquals(
            "1",
            KeyboardPages
                .definition(null, letters)
                .rows[0]
                .keys[0]
                .secondaryLabel,
        )
    }

    @Test
    fun theSwitchKeyJoinsTheFootersOnlyWhenTheUserAskedForIt() {
        fun types(
            config: KeyboardPages.Config,
            page: KeyboardPageKind?,
        ) = KeyboardPages
            .definition(page, config)
            .rows
            .last()
            .keys
            .map { it.action.type }

        val on = letters.copy(showsGlobe = true)
        for (page in listOf(null, KeyboardPageKind.NUMERIC, KeyboardPageKind.SYMBOLS)) {
            assertTrue(LayoutActionKind.GLOBE !in types(letters, page))
            assertEquals(LayoutActionKind.GLOBE, types(on, page).first())
            assertEquals(types(letters, page), types(on, page).drop(1))
        }
        // The key takes its width from the space bar, so the row keeps its total width.
        val width = { config: KeyboardPages.Config ->
            KeyboardPages
                .definition(null, config)
                .rows
                .last()
                .keys
                .sumOf { it.widthWeight }
        }
        assertEquals(width(letters), width(on), 1e-9)
    }

    @Test
    fun aLayoutsSwitchKeyIsDroppedFromTheRowsUnlessShown() {
        val rows =
            listOf(
                LayoutRow(
                    listOf(
                        LayoutKey("a", action = LayoutAction.code("a")),
                        LayoutKey("g", action = LayoutAction(LayoutActionKind.GLOBE)),
                    ),
                ),
            )
        assertEquals(listOf(LayoutActionKind.CODE), KeyRowSolver.columns(rows)[0].map { it.action.type })
        assertEquals(
            listOf(LayoutActionKind.CODE, LayoutActionKind.GLOBE),
            KeyRowSolver.columns(rows, showsGlobe = true)[0].map { it.action.type },
        )
    }
}
