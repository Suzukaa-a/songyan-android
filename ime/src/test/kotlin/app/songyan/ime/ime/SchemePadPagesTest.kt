package app.songyan.ime.ime

import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.KeyboardComposition
import app.songyan.ime.layout.KeyboardCompositionKey
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutDefinition
import app.songyan.ime.layout.LayoutInput
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutRegistry
import app.songyan.ime.layout.LayoutRow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The scheme pads draw through the ordinary rows page, so the shell must not add the editing keys a
 * pad already declares. The zhuyin pad's fourth encoding row plus its six-key footer would otherwise
 * draw Delete twice and a Shift the pad has no use for, while the cangjie pad keeps the shell's pair
 * because its footer leaves Delete out. Every shipped three-row shape keeps the shell's behavior.
 */
class SchemePadPagesTest {
    @Before
    fun installTheBundledCatalog() {
        KeyboardLayoutCatalog.install(LayoutRegistry.create())
        KeyboardPages.trim()
    }

    @After
    fun restoreTheBundledCatalog() {
        KeyboardLayoutCatalog.install(LayoutRegistry.create())
        KeyboardPages.trim()
    }

    private fun pairing(
        schemaID: String,
        layout: LayoutInput.Builtin = LayoutInput.Builtin.QWERTY26,
    ): KeyboardComposition = LayoutRegistry.create().composition(KeyboardCompositionKey(schemaID, layout))

    private fun config(
        pairing: KeyboardComposition,
        layout: KeyboardLayoutKind = KeyboardLayoutKind.QWERTY26,
        showsGlobe: Boolean = true,
    ) = KeyboardPages.Config(
        layout = layout,
        englishDirectInput = false,
        symbolMarksAscii = false,
        languageSwitch = true,
        lettersReachable = true,
        catalogRevision = KeyboardLayoutCatalog.snapshot.value.revision,
        pairing = pairing,
        showsGlobe = showsGlobe,
    )

    private fun page(
        schemaID: String,
        showsGlobe: Boolean = true,
    ): LayoutDefinition = KeyboardPages.definition(null, config(pairing(schemaID), showsGlobe = showsGlobe))

    private fun editingCount(
        definition: LayoutDefinition,
        kind: LayoutActionKind,
    ) = definition.rows.flatMap { it.keys }.count { it.action.type == kind }

    @Test
    fun theZhuyinPageDrawsFourEncodingRowsAndItsSixKeyFooter() {
        val definition = page("bopomofo_tw")
        assertEquals(5, definition.rows.size)
        assertEquals(listOf(11, 10, 10, 10), definition.rows.dropLast(1).map { it.keys.size })
        assertEquals(
            listOf("ㄈ", "ㄌ", "ㄏ", "ㄒ", "ㄖ", "ㄙ", "ㄩ", "ㄝ", "ㄡ", "ㄥ"),
            definition.rows[3].keys.map { it.label },
        )
        assertEquals(
            listOf("🌐", "123", "中/英", "空格", "⌫", "⏎"),
            definition.rows
                .last()
                .keys
                .map { it.label },
        )
        // The pad has no case and declares its own Delete in the footer, so the shell adds neither.
        assertEquals(0, editingCount(definition, LayoutActionKind.SHIFT))
        assertEquals(1, editingCount(definition, LayoutActionKind.BACKSPACE))
    }

    @Test
    fun theZhuyinFooterKeepsItsDeleteWhenTheGlobeKeyIsOff() {
        val definition = page("bopomofo_tw", showsGlobe = false)
        assertEquals(
            listOf("123", "中/英", "空格", "⌫", "⏎"),
            definition.rows
                .last()
                .keys
                .map { it.label },
        )
        assertEquals(1, editingCount(definition, LayoutActionKind.BACKSPACE))
    }

    @Test
    fun theZhuyinCapsStillSendTheSpellerCodesNotLiterals() {
        val encoding = page("bopomofo_tw").rows.dropLast(1).flatMap { it.keys }
        assertEquals(41, encoding.size)
        for (key in encoding) {
            assertEquals(key.label + " sends a code", LayoutActionKind.CODE, key.action.type)
            assertTrue(key.label + " carries a value", !key.action.value.isNullOrEmpty())
        }
        assertEquals(41, encoding.map { it.action.value }.toSet().size)
        assertTrue(encoding.none { it.action.type == LayoutActionKind.LITERAL })
    }

    @Test
    fun theCangjiePageKeepsItsRadicalsAndExactlyOneShiftAndDelete() {
        val definition = page("cangjie5")
        assertEquals(4, definition.rows.size)
        assertEquals(listOf(10, 9, 9), definition.rows.take(3).map { it.keys.size })
        val firstRow = definition.rows[0].keys
        assertEquals("手", firstRow[0].label)
        assertEquals("Q", firstRow[0].secondaryLabel)
        assertEquals(LayoutActionKind.CODE, firstRow[0].action.type)
        assertEquals("q", firstRow[0].action.value)
        // 人 is o; the radical is the face TalkBack reads, the letter only its corner badge.
        assertEquals("人", firstRow[8].label)
        assertEquals("O", firstRow[8].secondaryLabel)
        val lastRow = definition.rows[2].keys
        assertEquals(LayoutActionKind.SHIFT, lastRow.first().action.type)
        assertEquals(LayoutActionKind.BACKSPACE, lastRow.last().action.type)
        // Its authored footer opens 123 and keeps the engine space, without a second Delete.
        assertEquals(
            listOf("🌐", "123", "中/英", "空格", "⏎"),
            definition.rows
                .last()
                .keys
                .map { it.label },
        )
        assertEquals(1, editingCount(definition, LayoutActionKind.SHIFT))
        assertEquals(1, editingCount(definition, LayoutActionKind.BACKSPACE))
    }

    @Test
    fun theShippedThreeRowLayoutsKeepTheShellEditingKeys() {
        for (layout in listOf(KeyboardLayoutKind.QWERTY26, KeyboardLayoutKind.MERGED14, KeyboardLayoutKind.MERGED18)) {
            val definition =
                KeyboardPages.definition(
                    null,
                    config(pairing("songyan_quanpin"), layout = layout, showsGlobe = false).copy(pairing = null),
                )
            assertEquals(layout.name, 4, definition.rows.size)
            val lastLetterRow = definition.rows[2].keys
            assertEquals(layout.name, LayoutActionKind.SHIFT, lastLetterRow.first().action.type)
            assertEquals(layout.name, LayoutActionKind.BACKSPACE, lastLetterRow.last().action.type)
            assertEquals(layout.name, 1, editingCount(definition, LayoutActionKind.SHIFT))
            assertEquals(layout.name, 1, editingCount(definition, LayoutActionKind.BACKSPACE))
        }
    }

    @Test
    fun anUnknownFourRowLayoutKeepsTheShellEditingKeys() {
        val base = BuiltinLayouts.qwerty26
        val extraRow =
            LayoutRow(
                "abcdefghij".map { letter ->
                    LayoutKey(letter.toString(), action = LayoutAction.code(letter.toString()))
                },
            )
        val mine = base.copyWithId("mine", "我的").copy(rows = base.rows.dropLast(1) + extraRow)
        KeyboardLayoutCatalog.install(LayoutRegistry.create().install(mine.encoded()))
        KeyboardPages.trim()
        val definition =
            KeyboardPages.definition(
                null,
                config(KeyboardComposition(mainLayoutID = "mine"), showsGlobe = false),
            )
        // The last row is letters only and the generated footer has no Delete, so the shell edits.
        assertEquals(5, definition.rows.size)
        val lastLetterRow = definition.rows[3].keys
        assertEquals(LayoutActionKind.SHIFT, lastLetterRow.first().action.type)
        assertEquals(LayoutActionKind.BACKSPACE, lastLetterRow.last().action.type)
        assertEquals(1, editingCount(definition, LayoutActionKind.SHIFT))
        assertEquals(1, editingCount(definition, LayoutActionKind.BACKSPACE))
    }

    /** The rule reads the pad's shape and footer, not its id: a user's copy of the zhuyin pad behaves alike. */
    @Test
    fun aUserCopyOfTheZhuyinPadUsesItsDeclaredFooterToo() {
        val copy = BuiltinLayouts.zhuyin41.copyWithId("my-zhuyin", "我的注音")
        KeyboardLayoutCatalog.install(LayoutRegistry.create().install(copy.encoded()))
        KeyboardPages.trim()
        val definition =
            KeyboardPages.definition(
                null,
                config(KeyboardComposition(mainLayoutID = "my-zhuyin"), showsGlobe = false),
            )
        assertEquals(5, definition.rows.size)
        assertEquals(listOf(11, 10, 10, 10), definition.rows.dropLast(1).map { it.keys.size })
        assertEquals(0, editingCount(definition, LayoutActionKind.SHIFT))
        assertEquals(1, editingCount(definition, LayoutActionKind.BACKSPACE))
    }
}
