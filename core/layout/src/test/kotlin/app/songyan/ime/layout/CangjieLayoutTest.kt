package app.songyan.ime.layout

import app.songyan.ime.keyboard.engine.InputLayoutPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cangjie pad: a QWERTY key arrangement whose main caps are radicals and whose corner badges are
 * the letters the engine actually receives. The radical is a display face, never the value sent.
 */
class CangjieLayoutTest {
    private val layout get() = BuiltinLayouts.cangjie26

    /** The cangjie radical of each Latin key, as the standard assignment lists them. */
    private val radicalOf =
        mapOf(
            "q" to "手",
            "w" to "田",
            "e" to "水",
            "r" to "口",
            "t" to "廿",
            "y" to "卜",
            "u" to "山",
            "i" to "戈",
            "o" to "人",
            "p" to "心",
            "a" to "日",
            "s" to "尸",
            "d" to "木",
            "f" to "火",
            "g" to "土",
            "h" to "竹",
            "j" to "十",
            "k" to "大",
            "l" to "中",
            "z" to "重",
            "x" to "難",
            "c" to "金",
            "v" to "女",
            "b" to "月",
            "n" to "弓",
            "m" to "一",
        )

    private fun key(schemaID: String) = KeyboardCompositionKey(schemaID, LayoutInput.Builtin.QWERTY26)

    private fun encodingKeys() = layout.rows.take(3).flatMap { it.keys }

    @Test
    fun theIdentityTransportIsTheBundled26KeyShape() {
        assertEquals("cangjie26", layout.id)
        assertEquals(InputLayoutPolicy.CANGJIE_LAYOUT, layout.id)
        assertEquals(LayoutInput(builtinLayout = LayoutInput.Builtin.QWERTY26), layout.input)
        assertEquals(layout, BuiltinLayouts.byId("cangjie26"))
    }

    @Test
    fun theThreeEncodingRowsHoldTheTwentySixLetterKeys() {
        assertEquals(4, layout.rows.size)
        assertEquals(listOf(10, 9, 7), layout.rows.take(3).map { it.keys.size })
        assertEquals(26, encodingKeys().size)
        assertEquals(
            5,
            layout.rows
                .last()
                .keys.size,
        )
    }

    /**
     * The board had its own footer without the marks either side of the space bar, so it looked
     * unlike every other 26-key board. It now uses the 26-key stand-in, which the keyboard replaces
     * with the same footer it draws on the others.
     */
    @Test
    fun theControlRowIsTheStandInSoTheKeyboardDrawsItsOwnFooter() {
        assertEquals(BuiltinLayouts.qwerty26.rows.last(), layout.rows.last())
        assertNull(LayoutBottomRow.authored(layout))
    }

    @Test
    fun everyKeyShowsARadicalAndSendsItsLetter() {
        for (key in encodingKeys()) {
            assertEquals(LayoutActionKind.CODE, key.action.type)
            val code = key.action.value!!
            assertEquals("one lowercase letter", 1, code.length)
            assertTrue(code, code[0] in 'a'..'z')
            assertEquals("the corner badge is the letter", code.uppercase(), key.secondaryLabel)
            assertEquals("the main cap is the radical", radicalOf.getValue(code), key.label)
            // A radical is a face, never a literal: tapping must spell, not commit.
            assertTrue(key.label, key.action.type != LayoutActionKind.LITERAL)
        }
    }

    @Test
    fun theLetterToRadicalMapIsTheDocumentedOne() {
        assertEquals(radicalOf.keys, encodingKeys().map { it.action.value!! }.toSet())
        for ((letter, radical) in radicalOf) {
            val key = encodingKeys().first { it.action.value == letter }
            assertEquals(letter, radical, key.label)
        }
        assertEquals("日", encodingKeys().first { it.action.value == "a" }.label)
        assertEquals("弓", encodingKeys().first { it.action.value == "n" }.label)
        assertEquals("難", encodingKeys().first { it.action.value == "x" }.label)
    }

    /** Radical caps are not letter groups, so no folded-shape UI may claim this pad. */
    @Test
    fun thePadKeepsItsOwnShapePolicies() {
        assertEquals(true, layout.sendsEngineCode)
        assertTrue(layout.effectiveCandidateArea.candidates)
        assertFalse(PhoneKeypadStructure.matches(layout))
        assertFalse(CompactSpellingLayout.matches(layout))
        assertNull(LetterGroupLayout.groups(layout))
        assertTrue(CompactSpellingLayout.readingKeyMap(layout).isEmpty())
        assertNull(BuiltinLayouts.handSplit(layout))
    }

    @Test
    fun theCangjieFamilyDefaultsToThisLayout() {
        val registry = LayoutRegistry.create()
        assertEquals("cangjie26", registry.defaultMainLayoutId(key("cangjie5")))
        assertEquals("cangjie26", registry.defaultMainLayoutId(key("quick5")))
        assertEquals("cangjie26", registry.composition(key("cangjie5")).mainLayoutID)
        assertFalse("zhuyin41" in registry.mainLayoutCandidates(key("cangjie5")).map { it.id })
        assertTrue("cangjie26" in registry.mainLayoutCandidates(key("quick5")).map { it.id })
    }

    @Test
    fun otherSchemesKeepTodaysLayouts() {
        val registry = LayoutRegistry.create()
        assertEquals("qwerty26", registry.defaultMainLayoutId(key("rime_ice")))
        assertEquals("qwerty26", registry.defaultMainLayoutId(key("jyut6ping3")))
        assertFalse("cangjie26" in registry.mainLayoutCandidates(key("rime_ice")).map { it.id })
        assertFalse("cangjie26" in registry.mainLayoutCandidates(key("bopomofo_tw")).map { it.id })
    }

    @Test
    fun aSavedPairingKeepsTheUsersChoice() {
        // The 26-key shape is still a legal choice for a cangjie scheme, so a saved one stays.
        val saved =
            LayoutRegistry.create(
                compositions =
                    mapOf(
                        "cangjie5:qwerty26" to KeyboardComposition(mainLayoutID = "qwerty26"),
                    ),
            )
        assertEquals("qwerty26", saved.composition(key("cangjie5")).mainLayoutID)
        assertEquals("qwerty26", saved.compositions.getValue("cangjie5:qwerty26").mainLayoutID)

        // A pairing saved before the rule that names the zhuyin pad reads as a legal one here, and
        // the stored entry itself is left alone.
        val stale =
            LayoutRegistry.create(
                compositions =
                    mapOf(
                        "cangjie5:qwerty26" to KeyboardComposition(mainLayoutID = "zhuyin41"),
                    ),
            )
        assertEquals("zhuyin41", stale.compositions.getValue("cangjie5:qwerty26").mainLayoutID)
        assertEquals("cangjie26", stale.composition(key("cangjie5")).mainLayoutID)

        // A user layout stays available and stays selected.
        val mine = BuiltinLayouts.qwerty26.copyWithId("mine", "我的 26 键")
        val userLayout =
            LayoutRegistry.create(
                layouts = BuiltinLayouts.all + mine,
                compositions =
                    mapOf(
                        "cangjie5:qwerty26" to KeyboardComposition(mainLayoutID = "mine"),
                    ),
            )
        assertEquals("mine", userLayout.composition(key("cangjie5")).mainLayoutID)
        assertTrue("mine" in userLayout.mainLayoutCandidates(key("cangjie5")).map { it.id })
    }
}
