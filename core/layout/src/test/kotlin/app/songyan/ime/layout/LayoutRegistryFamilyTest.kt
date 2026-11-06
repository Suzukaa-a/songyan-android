package app.songyan.ime.layout

import app.songyan.ime.keyboard.engine.InputSchemeFamily
import app.songyan.ime.keyboard.engine.LayoutFamilyContext
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry's share of the layout availability rules: which physical layouts a scheme family may
 * pair with, which one a keyboard defaults to, and how a pairing saved before the rule reads now.
 *
 * The zhuyin, cangjie and romaji boards ship as builtin layouts; the twelve-key kana pad is still a
 * reserved id, so this class stands in for the layout file that will ship with it.
 */
class LayoutRegistryFamilyTest {
    private fun physical(
        id: String,
        schemaID: String? = null,
    ): LayoutDefinition =
        BuiltinLayouts.qwerty26
            .copyWithId(id, id)
            .copy(input = LayoutInput(schemaID = schemaID, builtinLayout = LayoutInput.Builtin.QWERTY26))

    /** The twelve-key kana pad has not shipped yet, so a stand-in carries its reserved id. */
    private val reserved = listOf(physical("kana12"))

    private fun registry(layouts: List<LayoutDefinition> = BuiltinLayouts.all + reserved): LayoutRegistry =
        LayoutRegistry.create(layouts = layouts)

    private fun key(
        schemaID: String,
        kind: KeyboardLayoutKind,
    ) = KeyboardCompositionKey(schemaID, LayoutInput.Builtin.fromWire(kind.wire) ?: error(kind.wire))

    private fun candidates(
        registry: LayoutRegistry,
        schemaID: String,
        kind: KeyboardLayoutKind,
        families: LayoutFamilyContext = LayoutFamilyContext.UNKNOWN,
    ): List<String> = registry.mainLayoutCandidates(key(schemaID, kind), families).map { it.id }

    /** An installed alias id whose speller alphabet is the zhuyin one. */
    private val alias = "dachen"

    private fun zhuyinFacts() = LayoutFamilyContext.of(alias to InputSchemeFamily.ZHUYIN)

    @Test
    fun aRestrictedFamilyKeepsOnlyTheIdentityShape() {
        val registry = registry()
        assertTrue(
            "a zhuyin scheme keeps no folded shape",
            registry.mainLayoutCandidates(key("bopomofo_tw", KeyboardLayoutKind.MERGED14)).isEmpty(),
        )
        assertTrue(registry.mainLayoutCandidates(key("cangjie5", KeyboardLayoutKind.PHONE9)).isEmpty())
        assertEquals(
            listOf("qwerty26", "zhuyin41"),
            candidates(registry, "bopomofo_tw", KeyboardLayoutKind.QWERTY26),
        )
    }

    @Test
    fun aReservedPhysicalLayoutOnlyServesItsOwnFamily() {
        val registry = registry()
        assertTrue("cangjie26" in candidates(registry, "cangjie5", KeyboardLayoutKind.QWERTY26))
        assertTrue("cangjie26" in candidates(registry, "quick5", KeyboardLayoutKind.QWERTY26))
        assertFalse("zhuyin41" in candidates(registry, "cangjie5", KeyboardLayoutKind.QWERTY26))
        assertTrue("romaji26" in candidates(registry, "kagiroi", KeyboardLayoutKind.QWERTY26))
        assertTrue("kana12" in candidates(registry, "kagiroi", KeyboardLayoutKind.QWERTY26))
        assertFalse("cangjie26" in candidates(registry, "kagiroi", KeyboardLayoutKind.QWERTY26))
        // Cantonese is a Latin phonetic scheme: it keeps the shipped shapes only.
        assertEquals(listOf("qwerty26"), candidates(registry, "jyut6ping3", KeyboardLayoutKind.QWERTY26))
        // A scheme without a family keeps the family-owned boards out.
        assertFalse("zhuyin41" in candidates(registry, "rime_ice", KeyboardLayoutKind.QWERTY26))
    }

    @Test
    fun anUnknownPhysicalLayoutServesEveryFamily() {
        val registry = registry(BuiltinLayouts.all + reserved + physical("mine26"))
        for (schemaID in listOf("bopomofo_tw", "cangjie5", "kagiroi", "jyut6ping3", "rime_ice")) {
            assertTrue(
                "$schemaID keeps a user layout",
                "mine26" in candidates(registry, schemaID, KeyboardLayoutKind.QWERTY26),
            )
        }
    }

    @Test
    fun theFamilyDefaultPhysicalLayoutIsUsedWhenRegistered() {
        val registry = registry()
        assertEquals("zhuyin41", registry.defaultMainLayoutId(key("bopomofo_tw", KeyboardLayoutKind.QWERTY26)))
        assertEquals("cangjie26", registry.defaultMainLayoutId(key("cangjie5", KeyboardLayoutKind.QWERTY26)))
        assertEquals("romaji26", registry.defaultMainLayoutId(key("kagiroi", KeyboardLayoutKind.QWERTY26)))
        assertEquals("qwerty26", registry.defaultMainLayoutId(key("jyut6ping3", KeyboardLayoutKind.QWERTY26)))
        assertEquals("qwerty26", registry.defaultMainLayoutId(key("rime_ice", KeyboardLayoutKind.QWERTY26)))
        // The 9-key shape cannot draw the Japanese board: the identity shape answers with its board.
        assertEquals("romaji26", registry.defaultMainLayoutId(key("kagiroi", KeyboardLayoutKind.PHONE9)))
        // A catalog saved before the board shipped still defaults to it: presets live in code.
        val older = LayoutRegistry.create(layouts = listOf(BuiltinLayouts.qwerty26))
        assertEquals("romaji26", older.defaultMainLayoutId(key("kagiroi", KeyboardLayoutKind.QWERTY26)))
        assertEquals("romaji26", older.defaultMainLayoutId(key("kagiroi", KeyboardLayoutKind.PHONE9)))
    }

    @Test
    fun anExplicitSchemaBindingIsNotOverriddenByTheFamilyDefault() {
        // The explicit binding replaces the shipped pad for this catalog.
        val registry =
            registry(
                BuiltinLayouts.all.filterNot { it.id == "zhuyin41" } +
                    physical("zhuyin41", schemaID = "bopomofo_tw"),
            )
        assertEquals("zhuyin41", registry.defaultMainLayoutId(key("bopomofo_tw", KeyboardLayoutKind.QWERTY26)))
        // A different scheme of the same family is not what this keyboard is bound to.
        assertEquals("qwerty26", registry.defaultMainLayoutId(key("bopomofo", KeyboardLayoutKind.QWERTY26)))
    }

    @Test
    fun aSavedPairingWithAForeignPhysicalLayoutReadsAsALegalOne() {
        val saved = KeyboardComposition(mainLayoutID = "zhuyin41")
        val registry =
            LayoutRegistry.create(
                layouts = BuiltinLayouts.all + reserved,
                compositions = mapOf("cangjie5:qwerty26" to saved),
            )
        // The user's saved pairing is kept as saved, so switching back to a matching scheme restores it.
        assertEquals("zhuyin41", registry.compositions.getValue("cangjie5:qwerty26").mainLayoutID)
        // Reading it for the cangjie scheme falls back to a layout its family may draw.
        assertEquals("cangjie26", registry.composition(key("cangjie5", KeyboardLayoutKind.QWERTY26)).mainLayoutID)
    }

    @Test
    fun aSavedPairingOnAFoldedShapeReadsAsTheIdentityOne() {
        // Saved before the family rule existed, when the zhuyin scheme was still an unknown scheme.
        val registry =
            LayoutRegistry.create(
                layouts = BuiltinLayouts.all,
                compositions = mapOf("bopomofo_tw:merged14" to KeyboardComposition(mainLayoutID = "merged14")),
            )
        assertEquals("merged14", registry.compositions.getValue("bopomofo_tw:merged14").mainLayoutID)
        // The zhuyin family reads through the identity shape, whose default is now its own pad.
        assertEquals(
            "zhuyin41",
            registry.composition(key("bopomofo_tw", KeyboardLayoutKind.MERGED14)).mainLayoutID,
        )
    }

    @Test
    fun savingAPairingTheFamilyForbidsIsRejected() {
        val registry = registry()
        assertThrows(LayoutException.Invalid::class.java) {
            registry.withComposition(KeyboardComposition(mainLayoutID = "zhuyin41"), "cangjie5:qwerty26")
        }
        assertThrows(LayoutException.Invalid::class.java) {
            registry.withComposition(KeyboardComposition(mainLayoutID = "merged14"), "cangjie5:merged14")
        }
    }

    @Test
    fun aFamilylessSchemeKeepsTheShippedShapes() {
        val registry = registry()
        assertTrue("merged14" in candidates(registry, "rime_ice", KeyboardLayoutKind.MERGED14))
        assertEquals("merged14", registry.defaultMainLayoutId(key("rime_ice", KeyboardLayoutKind.MERGED14)))
        assertEquals("merged14", registry.composition(key("rime_ice", KeyboardLayoutKind.MERGED14)).mainLayoutID)
    }

    @Test
    fun theKnownAliasFamilySelectsItsOwnPhysicalLayouts() {
        val registry = registry()
        // The alias id alone proves nothing: the unrestricted family keeps the family-owned boards out.
        assertFalse("zhuyin41" in candidates(registry, alias, KeyboardLayoutKind.QWERTY26))
        assertTrue("zhuyin41" in candidates(registry, alias, KeyboardLayoutKind.QWERTY26, zhuyinFacts()))
        assertFalse("romaji26" in candidates(registry, alias, KeyboardLayoutKind.QWERTY26, zhuyinFacts()))
        assertEquals(
            "zhuyin41",
            registry.defaultMainLayoutId(key(alias, KeyboardLayoutKind.QWERTY26), zhuyinFacts()),
        )
        // The known family forbids the folded shapes even though the id is unknown.
        assertTrue(registry.mainLayoutCandidates(key(alias, KeyboardLayoutKind.MERGED14), zhuyinFacts()).isEmpty())
        assertEquals(
            "zhuyin41",
            registry.defaultMainLayoutId(key(alias, KeyboardLayoutKind.PHONE9), zhuyinFacts()),
        )
    }

    @Test
    fun theKnownAliasFamilyAlsoFiltersSavedPairingsAtRead() {
        val saved = KeyboardComposition(mainLayoutID = "cangjie26")
        val registry =
            LayoutRegistry.create(
                layouts = BuiltinLayouts.all + reserved,
                compositions = mapOf("$alias:qwerty26" to saved),
            )
        assertEquals("cangjie26", registry.compositions.getValue("$alias:qwerty26").mainLayoutID)
        assertEquals(
            "zhuyin41",
            registry.composition(key(alias, KeyboardLayoutKind.QWERTY26), zhuyinFacts()).mainLayoutID,
        )
        // A folded pairing saved for the alias id reads through the family's identity layout too.
        val folded =
            LayoutRegistry.create(
                layouts = BuiltinLayouts.all + reserved,
                compositions = mapOf("$alias:phone9" to KeyboardComposition(mainLayoutID = "phone9")),
            )
        assertEquals(
            "zhuyin41",
            folded.composition(key(alias, KeyboardLayoutKind.PHONE9), zhuyinFacts()).mainLayoutID,
        )
        assertEquals("qwerty26", registry.composition(key(alias, KeyboardLayoutKind.QWERTY26)).mainLayoutID)
    }

    @Test
    fun aPhysicalLayoutBoundToAnotherSchemaCannotBecomeADefault() {
        val registry = registry(BuiltinLayouts.all + listOf(physical("kana12", schemaID = "luna_pinyin")))
        // kana12 belongs to the Japanese family: its schema binding must not let a Latin scheme default to it.
        assertEquals("qwerty26", registry.defaultMainLayoutId(key("luna_pinyin", KeyboardLayoutKind.QWERTY26)))
        assertFalse("kana12" in candidates(registry, "luna_pinyin", KeyboardLayoutKind.QWERTY26))
    }

    @Test
    fun aForbiddenKindDefaultsToTheIdentityLayoutNotThatShape() {
        val registry = registry()
        // A Japanese scheme cannot use 9 keys; the public default must still name a layout it can draw.
        assertEquals("romaji26", registry.defaultMainLayoutId(key("kagiroi", KeyboardLayoutKind.PHONE9)))
        assertEquals(
            registry.defaultMainLayoutId(key("kagiroi", KeyboardLayoutKind.QWERTY26)),
            registry.defaultMainLayoutId(key("kagiroi", KeyboardLayoutKind.PHONE9)),
        )
        assertEquals(
            "romaji26",
            registry(BuiltinLayouts.all).defaultMainLayoutId(key("kagiroi", KeyboardLayoutKind.PHONE9)),
        )
        // An older catalog without the code-owned board gets the same answer: presets need no migration.
        assertEquals(
            "romaji26",
            registry(listOf(BuiltinLayouts.qwerty26))
                .defaultMainLayoutId(key("kagiroi", KeyboardLayoutKind.PHONE9)),
        )
    }
}
