package app.songyan.ime.ime

import app.songyan.ime.keyboard.engine.InputLanguage
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.KeyboardComposition
import app.songyan.ime.layout.KeyboardCompositionKey
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutDefinition
import app.songyan.ime.layout.LayoutInput
import app.songyan.ime.layout.LayoutKeyTitleMode
import app.songyan.ime.layout.LayoutOption
import app.songyan.ime.layout.LayoutRegistry
import app.songyan.ime.layout.LayoutRow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Japanese romaji board as the keyboard really draws it: a scheme of the Japanese family gets
 * romaji26 from the catalog, its letter menus follow the Japanese input language, the long vowel is
 * the scheme's minus code, one Shift and one Delete edge the letter rows, and the automatic footer
 * puts 、 and 。 beside the space bar. Layouts the user owns keep their own menus.
 */
class KeyboardPagesRomajiTest {
    private val shape = KeyboardLayoutKind.QWERTY26
    private val kagiroi = KeyboardCompositionKey("kagiroi", LayoutInput.Builtin.QWERTY26)

    @Before
    fun installTheBundledCatalog() {
        KeyboardLayoutCatalog.install(LayoutRegistry.create())
    }

    @After
    fun restoreTheBundledCatalog() {
        KeyboardLayoutCatalog.install(LayoutRegistry.create())
    }

    private fun install(registry: LayoutRegistry) {
        KeyboardLayoutCatalog.install(registry)
    }

    /** The config the keyboard builds: the pairing comes from the catalog, the language from the scheme. */
    private fun config(
        pairing: KeyboardComposition,
        language: InputLanguage = InputLanguage.JA,
    ) = KeyboardPages.Config(
        layout = shape,
        englishDirectInput = false,
        symbolMarksAscii = false,
        languageSwitch = true,
        lettersReachable = true,
        catalogRevision = KeyboardLayoutCatalog.snapshot.value.revision,
        pairing = pairing,
        inputLanguage = language,
    )

    /** The pairing the keyboard resolves for the running scheme when the user saved no choice. */
    private fun defaultConfig(language: InputLanguage = InputLanguage.JA) =
        config(
            KeyboardLayoutCatalog.snapshot.value.registry
                .composition(kagiroi),
            language,
        )

    private fun letters(config: KeyboardPages.Config): LayoutDefinition = KeyboardPages.definition(null, config)

    private fun keys(definition: LayoutDefinition) = definition.rows.flatMap { it.keys }.associateBy { it.label }

    private fun option(text: String) = LayoutOption(text, LayoutAction.literal(text))

    /** The author's own menus: one Japanese mark on a letter, one menu explicitly cleared. */
    private fun authorMenu(layout: LayoutDefinition): LayoutDefinition =
        layout.copy(
            rows =
                layout.rows.map { row ->
                    LayoutRow(
                        row.keys.map { key ->
                            when (key.label) {
                                "a" ->
                                    key.copy(
                                        secondaryLabel = "あ",
                                        longPress = listOf(option("あ"), option("ア")),
                                    )
                                "g" -> key.copy(secondaryLabel = null, longPress = null)
                                else -> key
                            }
                        },
                    )
                },
        )

    @Test
    fun theJapaneseFamilyDrawsTheRomajiBoard() {
        val registry = KeyboardLayoutCatalog.snapshot.value.registry
        assertEquals("romaji26", registry.composition(kagiroi).mainLayoutID)
        // The catalog rule is the schema id, so a kagiroi variant resolves the same board.
        assertEquals(
            "romaji26",
            registry.composition(KeyboardCompositionKey("kagiroi_romaji", LayoutInput.Builtin.QWERTY26)).mainLayoutID,
        )
        val definition = letters(config(registry.composition(kagiroi)))
        assertEquals(4, definition.rows.size)
        val caps =
            definition.rows.take(3).map { row ->
                row.keys.filter { it.action.type == LayoutActionKind.CODE }.joinToString("") { it.label }
            }
        assertEquals(listOf("qwertyuiop", "asdfghjklー", "zxcvbnm"), caps)
    }

    @Test
    fun theLongVowelSendsTheSchemesMinusCode() {
        val page = letters(defaultConfig())
        val longVowel = page.rows[1].keys.first { it.label == "ー" }
        assertEquals(LayoutActionKind.CODE, longVowel.action.type)
        assertEquals("-", longVowel.action.value)
        assertNull(longVowel.longPress)
        // The page types the long vowel once, as a code: a literal would bypass the scheme's own
        // handling of the spelling.
        val all = page.rows.flatMap { it.keys }
        assertEquals(1, all.count { it.action.type == LayoutActionKind.CODE && it.action.value == "-" })
        assertTrue(all.none { it.action.type == LayoutActionKind.LITERAL && it.action.value == "ー" })
    }

    @Test
    fun theLetterMenusFollowTheJapaneseLanguage() {
        val page = keys(letters(defaultConfig()))

        fun longPress(letter: String) = page.getValue(letter).longPress?.map { it.label }
        assertEquals("、", page.getValue("a").secondaryLabel)
        assertEquals(listOf("、"), longPress("a"))
        assertEquals("・", page.getValue("g").secondaryLabel)
        assertEquals(listOf("・"), longPress("g"))
        assertEquals("「", page.getValue("c").secondaryLabel)
        assertEquals(listOf("「", "『"), longPress("c"))
        assertEquals("」", page.getValue("v").secondaryLabel)
        assertEquals(listOf("」", "』"), longPress("v"))
        assertEquals("ー", page.getValue("x").secondaryLabel)
        assertEquals(listOf("ー", "～", "-", "_"), longPress("x"))
        assertEquals("。", page.getValue("s").secondaryLabel)
        assertTrue("s must long-press into 。", longPress("s")?.contains("。") == true)
        // The Simplified Chinese menus must not survive on the Japanese board.
        assertTrue("a kept the Simplified menu", longPress("a")?.contains("，") != true)
        assertTrue("c kept the Simplified menu", longPress("c")?.contains("“") != true)
    }

    @Test
    fun theShiftAndDeleteKeysEdgeTheLastLetterRowOnce() {
        val page = letters(defaultConfig())
        val all = page.rows.flatMap { it.keys }
        assertEquals(1, all.count { it.action.type == LayoutActionKind.SHIFT })
        assertEquals(1, all.count { it.action.type == LayoutActionKind.BACKSPACE })
        assertEquals(
            LayoutActionKind.SHIFT,
            page.rows[2]
                .keys
                .first()
                .action.type,
        )
        assertEquals(
            LayoutActionKind.BACKSPACE,
            page.rows[2]
                .keys
                .last()
                .action.type,
        )
        assertEquals(
            listOf("ー"),
            page.rows[1]
                .keys
                .map { it.label }
                .filter { it == "ー" },
        )
    }

    @Test
    fun theAutomaticFooterBracketsTheSpaceBarWithJapaneseMarks() {
        val footer = letters(defaultConfig()).rows.last().keys
        assertEquals(listOf("123", "中/英", "、", "空格", "。", "⏎"), footer.map { it.label })
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
        // The control keys are automatic: the host names them from the input language, so 空白 and
        // 変換/確定 replace the fallback faces without the layout carrying them.
        for (label in listOf("123", "中/英", "空格", "⏎")) {
            assertEquals(label, LayoutKeyTitleMode.AUTOMATIC, footer.first { it.label == label }.titleMode)
        }
        assertNull(footer.first { it.label == "、" }.titleMode)
        assertNull(footer.first { it.label == "。" }.titleMode)
        assertEquals(listOf("・", "：", "；"), footer.first { it.label == "、" }.longPress?.map { it.label })
        assertEquals(listOf("？", "！", "…"), footer.first { it.label == "。" }.longPress?.map { it.label })
        // The authored stand-in row is not drawn: no layout switch, globe or second Delete.
        assertTrue(footer.none { it.action.type == LayoutActionKind.GLOBE })
        assertTrue(footer.none { it.action.type == LayoutActionKind.MODE_SWITCH })
        assertTrue(footer.none { it.action.type == LayoutActionKind.BACKSPACE })
        assertEquals(
            KeyboardPages.FooterWeight.SPACE,
            footer.first { it.action.type == LayoutActionKind.SPACE }.widthWeight,
            0.001,
        )
    }

    @Test
    fun aSavedPairingOnTheShippedShapeKeepsItsRows() {
        install(
            LayoutRegistry
                .create()
                .withComposition(KeyboardComposition(mainLayoutID = "qwerty26"), kagiroi.rawValue),
        )
        val page = letters(defaultConfig())
        // The saved 26-key pairing wins; only the language menus follow the scheme.
        assertTrue(page.rows.flatMap { it.keys }.none { it.label == "ー" })
        assertEquals("、", keys(page).getValue("a").secondaryLabel)
    }

    @Test
    fun anAuthorCopyKeepsItsOwnLetterMenus() {
        val authored = authorMenu(BuiltinLayouts.romaji26.copyWithId("my-romaji", "我的罗马字"))
        install(LayoutRegistry.create(layouts = BuiltinLayouts.all + authored))
        val page = keys(letters(config(KeyboardComposition(mainLayoutID = "my-romaji"))))
        assertEquals("あ", page.getValue("a").secondaryLabel)
        assertEquals(listOf("あ", "ア"), page.getValue("a").longPress?.map { it.label })
        // An explicitly cleared menu stays empty instead of gaining the language defaults.
        assertNull(page.getValue("g").secondaryLabel)
        assertNull(page.getValue("g").longPress)
    }

    @Test
    fun anEditedBundledBoardKeepsItsOwnLetterMenus() {
        val edited =
            BuiltinLayouts.all.map { layout ->
                if (layout.id == BuiltinLayouts.romaji26.id) authorMenu(layout) else layout
            }
        install(LayoutRegistry.create(layouts = edited))
        val page = keys(letters(config(KeyboardComposition(mainLayoutID = "romaji26"))))
        assertEquals("あ", page.getValue("a").secondaryLabel)
        assertEquals(listOf("あ", "ア"), page.getValue("a").longPress?.map { it.label })
        assertNull(page.getValue("g").longPress)
    }

    @Test
    fun theBundledBoardsWithTheirOwnFacesKeepThem() {
        val zhuyin = letters(config(KeyboardComposition(mainLayoutID = "zhuyin41"), InputLanguage.ZH_HANT))
        assertEquals(
            listOf("ㄅ", "ㄉ", "ˇ", "ˋ", "ㄓ", "ˊ", "˙", "ㄚ", "ㄞ", "ㄢ", "ㄦ"),
            zhuyin.rows[0].keys.map { it.label },
        )
        assertTrue(zhuyin.rows.flatMap { it.keys }.all { it.secondaryLabel == null })
        val cangjie = letters(config(KeyboardComposition(mainLayoutID = "cangjie26"), InputLanguage.ZH_HANT))
        assertEquals(
            listOf("手", "田", "水", "口", "廿", "卜", "山", "戈", "人", "心"),
            cangjie.rows[0].keys.map { it.label },
        )
        assertEquals(
            listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"),
            cangjie.rows[0].keys.map { it.secondaryLabel },
        )
    }
}
