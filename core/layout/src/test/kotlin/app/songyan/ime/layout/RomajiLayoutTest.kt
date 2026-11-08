package app.songyan.ime.layout

import app.songyan.ime.keyboard.engine.InputLanguage
import app.songyan.ime.keyboard.engine.InputLayoutPolicy
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundled Japanese romaji board: the QWERTY 26-key letters with ー, typed by the scheme's minus
 * code, right of M, followed by the 26-key stand-in control row. The host replaces that row with its
 * own automatic footer, so the input language names the space, Return and language keys and puts
 * 、 and 。 on either side of the space bar.
 */
class RomajiLayoutTest {
    private val layout get() = BuiltinLayouts.romaji26

    /** The cap of every key of the three letter rows, in drawn order: the long vowel sits right of M. */
    private val letterCaps =
        listOf(
            listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
            listOf("z", "x", "c", "v", "b", "n", "m", "ー"),
        )

    /** The engine code of every letter key in the same order: ー is the scheme's minus. */
    private val letterCodes =
        listOf(
            listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
            listOf("z", "x", "c", "v", "b", "n", "m", "-"),
        )

    private fun key(
        schemaID: String,
        kind: KeyboardLayoutKind = KeyboardLayoutKind.QWERTY26,
    ) = KeyboardCompositionKey(schemaID, LayoutInput.Builtin.fromWire(kind.wire)!!)

    /** The JSON sample arrives with the iOS layout sync; this mirrors the iOS example test. */
    @Test
    fun theSyncedExampleIsTheBundledDefinition() {
        assertEquals(layout, LayoutFixtures.readDefinition(LayoutFixtures.ROMAJI26))
    }

    @Test
    fun theKeyboardIsRegisteredAsABundledLayout() {
        assertEquals("romaji26", layout.id)
        assertEquals(InputLayoutPolicy.ROMAJI_LAYOUT, layout.id)
        assertEquals("罗马字 26 键", layout.name)
        assertEquals(layout, BuiltinLayouts.byId("romaji26"))
        assertEquals(layout, BuiltinLayouts.byId(InputLayoutPolicy.ROMAJI_LAYOUT))

        val registry = LayoutRegistry.create()
        assertEquals("romaji26", registry.resolvedLayout("romaji26")!!.id)
        assertEquals(listOf("romaji26"), registry.usableLayouts { it.id == "romaji26" }.map { it.id })
        // A bundled board is reset, never deleted.
        val error = assertThrows(LayoutException::class.java) { LayoutRegistry.create().remove("romaji26") }
        assertEquals(LayoutInvalidCode.LAYOUT_PRESET_IS_READ_ONLY, (error as LayoutException.Invalid).code)
    }

    @Test
    fun theJapaneseBoardsAreTheSeventhAndEighthBundledPresets() {
        assertEquals(
            listOf(
                "qwerty26",
                "merged14",
                "merged18",
                "phone9",
                "zhuyin41",
                "cangjie26",
                "romaji26",
                "kana12",
            ),
            BuiltinLayouts.all.map { it.id },
        )
        // The six existing boards keep their row shapes and authored footers.
        assertEquals(listOf(10, 9, 7, 5), BuiltinLayouts.qwerty26.rows.map { it.keys.size })
        assertEquals(listOf(5, 5, 4, 5), BuiltinLayouts.merged14.rows.map { it.keys.size })
        assertEquals(listOf(7, 6, 5, 5), BuiltinLayouts.merged18.rows.map { it.keys.size })
        assertEquals(listOf(3, 3, 3, 3), BuiltinLayouts.phone9.rows.map { it.keys.size })
        assertEquals(listOf(11, 10, 10, 10, 6), BuiltinLayouts.zhuyin41.rows.map { it.keys.size })
        assertEquals(listOf(10, 9, 7, 5), BuiltinLayouts.cangjie26.rows.map { it.keys.size })
        assertNull(BuiltinLayouts.handSplit(BuiltinLayouts.zhuyin41))
        assertNull(BuiltinLayouts.handSplit(BuiltinLayouts.cangjie26))
    }

    @Test
    fun theLetterRowsAreQwertyWithTheLongVowelRightOfM() {
        assertEquals("three letter rows and one control row", 4, layout.rows.size)
        assertEquals(listOf(10, 9, 8, 5), layout.rows.map { it.keys.size })
        assertEquals(letterCaps, layout.rows.dropLast(1).map { row -> row.keys.map { it.label } })
    }

    @Test
    fun everyLetterKeySendsItsLatinCodeAndTheLongVowelSendsMinus() {
        val rows = layout.rows.dropLast(1)
        assertEquals(letterCodes.flatten(), rows.flatMap { row -> row.keys.map { it.action.value } })
        for (row in rows) {
            for (key in row.keys) {
                assertEquals(key.label, LayoutActionKind.CODE, key.action.type)
                key.action.validate()
                assertNull(key.label, key.width)
            }
        }
        // ー must reach the scheme as the minus code, never as an inserted literal: the scheme turns
        // the code into the long vowel mark mid-composition.
        val longVowel = rows.last().keys.last()
        assertEquals("ー", longVowel.label)
        assertEquals(LayoutAction.code("-"), longVowel.action)
        assertNull(longVowel.longPress)
    }

    @Test
    fun theLetterMenusStayTheBundledDefaultsSoTheInputLanguageNamesTheMarks() {
        // The identity transport keeps the bundled 26-key menus: the runtime replaces them with the
        // script's marks, so a Japanese board offers 、 。 「 」 and ー where a Chinese board does.
        val bundledRows = BuiltinLayouts.qwerty26.rows.dropLast(1)
        for ((row, presetRow) in layout.rows.dropLast(1).zip(bundledRows)) {
            assertEquals(presetRow.keys, row.keys.take(presetRow.keys.size))
        }
        assertEquals(listOf("、"), AlternateKeyPolicy.defaultLetterLiterals("a", InputLanguage.JA))
        assertEquals(listOf("「", "『"), AlternateKeyPolicy.defaultLetterLiterals("c", InputLanguage.JA))
        assertTrue(AlternateKeyPolicy.defaultLetterLiterals("x", InputLanguage.JA).contains("ー"))
    }

    @Test
    fun theFooterIsTheBundledStandInSoTheHostDrawsItsAutomaticOne() {
        assertEquals(BuiltinLayouts.qwerty26.rows.last(), layout.rows.last())
        assertEquals(
            listOf(
                LayoutActionKind.MODE_SWITCH,
                LayoutActionKind.GLOBE,
                LayoutActionKind.SPACE,
                LayoutActionKind.BACKSPACE,
                LayoutActionKind.ENTER,
            ),
            layout.rows
                .last()
                .keys
                .map { it.action.type },
        )
        assertEquals(3, LayoutBottomRow.index(layout))
        // An unedited stand-in is not authored data: the host draws its own footer, which is the only
        // place the space-side marks and the automatic key names come from.
        assertNull(LayoutBottomRow.authored(layout))
        assertNull(LayoutBottomRow.authored(BuiltinLayouts.qwerty26))
        assertNotNull(LayoutBottomRow.authored(BuiltinLayouts.zhuyin41))
    }

    @Test
    fun theBoardStaysInThe26KeyMainShellAndDrawsItsRowsByWeight() {
        // The encoding kind stays the 26-key one: the board is a layout of its own, identified by its
        // id, so no new kind and no new engine case come with it.
        assertEquals(LayoutInput(builtinLayout = LayoutInput.Builtin.QWERTY26), layout.input)
        assertNull(layout.input.schemaID)
        assertEquals(3, PhoneKeypadMetrics.MAIN_ROW_COUNT)
        assertTrue(layout.rows.take(3).all { it.keys.isNotEmpty() })
        assertTrue(
            layout.rows.last().keys.all {
                it.action.type != LayoutActionKind.CODE && it.action.type != LayoutActionKind.LITERAL
            },
        )
        // Every main key is one unit wide, so the shell's weight projection gives the eight-key third
        // row equal shares; the preset's fixed ten columns are not claimed.
        for (key in layout.rows.take(3).flatMap { it.keys }) {
            assertEquals(key.label, 1.0, key.widthWeight, 1e-9)
        }
        assertEquals(8, layout.rows[2].keys.size)
        assertEquals(
            7,
            BuiltinLayouts.qwerty26.rows[2]
                .keys.size,
        )
        // ー right of M is one more key in a main row, not a shape of its own.
        assertNull(BuiltinLayouts.handSplit(layout))
        assertFalse(PhoneKeypadStructure.matches(layout))
        assertFalse(CompactSpellingLayout.matches(layout))
        assertNull(LetterGroupLayout.groups(layout))
        assertTrue(CompactSpellingLayout.readingKeyMap(layout).isEmpty())
    }

    @Test
    fun theKeyboardUsesTheEngineAndTheCandidateBar() {
        assertTrue(layout.sendsEngineCode)
        assertEquals(LayoutCandidateArea(candidates = true, reading = false), layout.effectiveCandidateArea)
        assertNull(layout.note)
        assertNull(layout.split)
        layout.validate()
        assertEquals(layout, LayoutJson.decode(layout.encoded()))
    }

    @Test
    fun theJapaneseFamilyDefaultsToTheRomajiBoard() {
        val registry = LayoutRegistry.create()
        assertEquals("romaji26", registry.defaultMainLayoutId(key("kagiroi")))
        assertEquals("romaji26", registry.composition(key("kagiroi")).mainLayoutID)
        assertTrue("romaji26" in registry.mainLayoutCandidates(key("kagiroi")).map { it.id })
    }

    @Test
    fun aSavedPairingKeepsTheUsersChoice() {
        // The 26-key shape is still a legal choice for a Japanese scheme, so a saved one stays.
        val saved = KeyboardComposition(mainLayoutID = "qwerty26")
        val registry =
            LayoutRegistry.create(
                compositions = mapOf("kagiroi:qwerty26" to saved),
            )
        assertEquals("qwerty26", registry.composition(key("kagiroi")).mainLayoutID)
        assertEquals("qwerty26", registry.compositions.getValue("kagiroi:qwerty26").mainLayoutID)

        // A pairing saved on the romaji board itself also stays selected.
        val romaji =
            LayoutRegistry.create(
                compositions = mapOf("kagiroi:qwerty26" to KeyboardComposition(mainLayoutID = "romaji26")),
            )
        assertEquals("romaji26", romaji.composition(key("kagiroi")).mainLayoutID)
    }

    @Test
    fun theShippedKanaBoardIsNeverTheDefault() {
        // Both Japanese boards ship; the family still defaults to the romaji one and a kana board is
        // only ever chosen, never guessed from the catalog.
        assertEquals("kana12", requireNotNull(BuiltinLayouts.byId(InputLayoutPolicy.KANA_LAYOUT)).id)
        val registry = LayoutRegistry.create()
        assertEquals("kana12", requireNotNull(registry.resolvedLayout(InputLayoutPolicy.KANA_LAYOUT)).id)
        assertTrue(registry.mainLayoutCandidates(key("kagiroi")).map { it.id }.any { it == "kana12" })
        // A Japanese scheme cannot use 9 keys; the identity shape answers with the romaji board.
        assertEquals("romaji26", registry.defaultMainLayoutId(key("kagiroi", KeyboardLayoutKind.PHONE9)))
        // A catalog that predates the board still defaults to it: bundled boards ship in code.
        val older = LayoutRegistry.create(layouts = listOf(BuiltinLayouts.qwerty26))
        assertEquals("romaji26", older.defaultMainLayoutId(key("kagiroi")))
        assertEquals("romaji26", older.defaultMainLayoutId(key("kagiroi", KeyboardLayoutKind.PHONE9)))
    }

    @Test
    fun otherFamiliesKeepTheirBoards() {
        val registry = LayoutRegistry.create()
        assertEquals("zhuyin41", registry.defaultMainLayoutId(key("bopomofo_tw")))
        assertEquals("cangjie26", registry.defaultMainLayoutId(key("cangjie5")))
        assertEquals("qwerty26", registry.defaultMainLayoutId(key("jyut6ping3")))
        assertEquals("qwerty26", registry.defaultMainLayoutId(key("rime_ice")))
        assertFalse("romaji26" in registry.mainLayoutCandidates(key("bopomofo_tw")).map { it.id })
        assertFalse("romaji26" in registry.mainLayoutCandidates(key("cangjie5")).map { it.id })
        assertFalse("romaji26" in registry.mainLayoutCandidates(key("rime_ice")).map { it.id })
    }
}
