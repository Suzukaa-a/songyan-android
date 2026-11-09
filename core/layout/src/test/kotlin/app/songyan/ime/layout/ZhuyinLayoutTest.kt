package app.songyan.ime.layout

import app.songyan.ime.keyboard.engine.InputLayoutPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Dachen zhuyin pad as a physical layout: the caps are bopomofo, while every key sends the ASCII
 * code the rime-bopomofo speller declares. The engine alphabet is the contract here, so a literal
 * cap would commit text instead of spelling a syllable.
 *
 * The pad is a physical layout of one scheme family rather than one of the shipped key shapes, so it
 * keeps the 26-key identity transport the pairings and the family rules already use.
 */
class ZhuyinLayoutTest {
    private val layout get() = BuiltinLayouts.zhuyin41

    /** rime-bopomofo's speller alphabet without the space. */
    private val alphabet = "1234567890qwertyuiopasdfghjklzxcvbnm-;,./".map { it.toString() }.toSet()

    private val punctuationCodes =
        setOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-", ";", ",", ".", "/")

    private fun key(schemaID: String) = KeyboardCompositionKey(schemaID, LayoutInput.Builtin.QWERTY26)

    /** The 41 caps of the four letter rows; the fourth row's closing Delete is not one of them. */
    private fun encodingKeys() =
        layout.rows.take(4).flatMap { it.keys }.filter {
            it.action.type !=
                LayoutActionKind.BACKSPACE
        }

    @Test
    fun theIdentityTransportIsTheBundled26KeyShape() {
        assertEquals("zhuyin41", layout.id)
        assertEquals(InputLayoutPolicy.ZHUYIN_LAYOUT, layout.id)
        // A pairings key is still "<scheme>:qwerty26": the physical layout keeps the identity shape.
        assertEquals(LayoutInput(builtinLayout = LayoutInput.Builtin.QWERTY26), layout.input)
        assertEquals(layout, BuiltinLayouts.byId("zhuyin41"))
    }

    @Test
    fun theFourEncodingRowsHoldFortyOneKeysAsElevenTenTenTen() {
        // Four encoding rows plus the shared control row every authored layout ends with.
        assertEquals(5, layout.rows.size)
        assertEquals(listOf(11, 10, 10, 11), layout.rows.take(4).map { it.keys.size })
        assertEquals(41, encodingKeys().size)
        assertEquals(41, encodingKeys().map { it.label }.toSet().size)
    }

    /**
     * The pad had its own footer without the punctuation keys either side of the space bar, so it
     * looked unlike every other board. The last row is now the 26-key stand-in and the keyboard
     * draws the shared footer; Delete closes the fourth row, and the board has no Shift.
     */
    @Test
    fun theFooterIsTheBundledStandInAndDeleteClosesTheFourthRow() {
        assertEquals(BuiltinLayouts.qwerty26.rows.last(), layout.rows.last())
        assertEquals(layout.rows.lastIndex, LayoutBottomRow.index(layout))
        assertNull(LayoutBottomRow.authored(layout))
        val delete = layout.rows[3].keys.last()
        assertEquals(LayoutActionKind.BACKSPACE, delete.action.type)
        assertEquals(LayoutKeyTitleMode.AUTOMATIC, delete.titleMode)
        assertTrue(layout.rows.flatMap { it.keys }.none { it.action.type == LayoutActionKind.SHIFT })
    }

    @Test
    fun everyEncodingKeySendsOneEngineCodeAndTheSetIsTheSpellerAlphabet() {
        val codes =
            encodingKeys().map { key ->
                assertEquals(key.label + " must send a code", LayoutActionKind.CODE, key.action.type)
                key.action.value!!
            }
        assertEquals(41, codes.toSet().size)
        assertEquals(alphabet, codes.toSet())
        // Digits and the tone marks are part of the alphabet, not text to commit.
        assertEquals(
            punctuationCodes,
            codes
                .filterNot { it in "abcdefghijklmnopqrstuvwxyz" }
                .toSet(),
        )
    }

    @Test
    fun theDigitAndPunctuationCapsSendEngineCodesNotLiterals() {
        assertTrue(encodingKeys().none { it.action.type == LayoutActionKind.LITERAL })
        for (code in punctuationCodes) {
            val key = encodingKeys().firstOrNull { it.action.value == code }
            assertNotNull("missing code " + code, key)
            assertEquals(code, LayoutActionKind.CODE, key!!.action.type)
            assertTrue(code, LayoutAction.isSinglePrintableAscii(code))
        }
    }

    @Test
    fun theCapsAreTheDachenSymbolsInTheDocumentedOrder() {
        assertEquals("ㄅㄉˇˋㄓˊ˙ㄚㄞㄢㄦ", layout.rows[0].keys.joinToString("") { it.label })
        assertEquals("ㄆㄊㄍㄐㄔㄗㄧㄛㄟㄣ", layout.rows[1].keys.joinToString("") { it.label })
        assertEquals("ㄇㄋㄎㄑㄕㄘㄨㄜㄠㄤ", layout.rows[2].keys.joinToString("") { it.label })
        assertEquals("ㄈㄌㄏㄒㄖㄙㄩㄝㄡㄥ⌫", layout.rows[3].keys.joinToString("") { it.label })
        // The tone marks travel on the same keys as on the standard Dachen pad.
        assertEquals(
            listOf("3", "4", "6", "7"),
            layout.rows[0]
                .keys
                .filter { it.label in "ˇˋˊ˙" }
                .map { it.action.value },
        )
    }

    /**
     * A self-contained fixture: the model itself must accept digits and `- ; , . /` as engine codes.
     * Waiting for the synced layout file to prove the code contract would hide a model regression.
     */
    @Test
    fun aSmallFixtureRoundTripsDigitAndPunctuationCodes() {
        val fixture =
            LayoutDefinition(
                id = "fixture-codes",
                name = "键码夹具",
                input = LayoutInput(builtinLayout = LayoutInput.Builtin.QWERTY26),
                rows =
                    listOf(
                        LayoutRow(
                            listOf("1", "-", ";", ",", ".", "/").map { value ->
                                LayoutKey(label = value, action = LayoutAction.code(value))
                            },
                        ),
                        BuiltinLayouts.controls,
                    ),
            )
        fixture.validate()
        assertEquals(fixture, LayoutJson.decode(fixture.encoded()))
        assertEquals(listOf("1", "-", ";", ",", ".", "/"), fixture.rows[0].keys.map { it.action.value })
    }

    /** The pad is a dedicated shape: it must not enter the folded-shape or telephone policies. */
    @Test
    fun thePadKeepsItsOwnShapePolicies() {
        assertEquals(true, layout.sendsEngineCode)
        assertTrue(layout.effectiveCandidateArea.candidates)
        assertFalse(PhoneKeypadStructure.matches(layout))
        assertFalse(CompactSpellingLayout.matches(layout))
        assertNull(LetterGroupLayout.groups(layout))
        assertTrue(CompactSpellingLayout.readingKeyMap(layout).isEmpty())
        assertNull(BuiltinLayouts.handSplit(layout))
        assertTrue(layout.sendsEngineCode)
    }

    @Test
    fun theZhuyinFamilyDefaultsToThisLayout() {
        val registry = LayoutRegistry.create()
        assertEquals("zhuyin41", registry.defaultMainLayoutId(key("bopomofo_tw")))
        assertEquals("zhuyin41", registry.defaultMainLayoutId(key("bopomofo")))
        assertEquals("zhuyin41", registry.composition(key("bopomofo_tw")).mainLayoutID)
        assertFalse(
            "the cangjie pad is not a zhuyin keyboard",
            "cangjie26" in registry.mainLayoutCandidates(key("bopomofo_tw")).map { it.id },
        )
        assertTrue("zhuyin41" in registry.mainLayoutCandidates(key("bopomofo_tw")).map { it.id })
    }

    @Test
    fun otherSchemesKeepTodaysLayouts() {
        val registry = LayoutRegistry.create()
        // A generic scheme and a Latin phonetic one keep the shipped 26-key layout.
        assertEquals("qwerty26", registry.defaultMainLayoutId(key("rime_ice")))
        assertEquals("qwerty26", registry.defaultMainLayoutId(key("jun_da_ba")))
        assertEquals("qwerty26", registry.defaultMainLayoutId(key("jyut6ping3")))
        // The zhuyin pad is offered to its family only.
        assertFalse("zhuyin41" in registry.mainLayoutCandidates(key("rime_ice")).map { it.id })
        assertFalse("zhuyin41" in registry.mainLayoutCandidates(key("cangjie5")).map { it.id })
    }

    @Test
    fun aSavedPairingKeepsTheUsersChoice() {
        // A user who already picked the 26-key shape keeps it; saving it again is legal for the family.
        val saved =
            LayoutRegistry.create(
                compositions =
                    mapOf(
                        "bopomofo_tw:qwerty26" to KeyboardComposition(mainLayoutID = "qwerty26"),
                    ),
            )
        assertEquals("qwerty26", saved.composition(key("bopomofo_tw")).mainLayoutID)
        assertEquals("qwerty26", saved.compositions.getValue("bopomofo_tw:qwerty26").mainLayoutID)

        // A user copy of another layout stays selectable and stays selected.
        val mine = BuiltinLayouts.qwerty26.copyWithId("mine", "我的 26 键")
        val userLayout =
            LayoutRegistry.create(
                layouts = BuiltinLayouts.all + mine,
                compositions =
                    mapOf(
                        "bopomofo_tw:qwerty26" to KeyboardComposition(mainLayoutID = "mine"),
                    ),
            )
        assertEquals("mine", userLayout.composition(key("bopomofo_tw")).mainLayoutID)
        assertTrue("mine" in userLayout.mainLayoutCandidates(key("bopomofo_tw")).map { it.id })
    }
}
