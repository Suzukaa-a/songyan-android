package app.songyan.ime.layout

import app.songyan.ime.keyboard.engine.InputSchemeFamily
import app.songyan.ime.keyboard.engine.LayoutFamilyContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/** Pairings of keyboards that name their layout, next to the pairings of a whole shape. */
class NamedKeyboardCompositionTest {
    private val q26 = LayoutInput.Builtin.QWERTY26

    private fun key(
        schema: String,
        layoutId: String? = null,
        shape: LayoutInput.Builtin = q26,
    ) = KeyboardCompositionKey(schema, shape, layoutId)

    private fun withMine(): LayoutRegistry =
        LayoutRegistry.create().copy(BuiltinLayouts.qwerty26, "my-26", "mine").first

    @Test
    fun keysRoundTripWithSeparatorsInsideEitherPart() {
        for (schema in listOf("a", "pack:method")) {
            for (layoutId in listOf(null, "qwerty26", "my#layout", "my:layout", "x:phone9#y")) {
                for (shape in LayoutInput.Builtin.entries) {
                    val key = KeyboardCompositionKey(schema, shape, layoutId)
                    assertEquals(key.rawValue, key, KeyboardCompositionKey.parse(key.rawValue))
                }
            }
        }
        assertEquals("a:qwerty26", key("a").rawValue)
        assertEquals("a:qwerty26#my-26", key("a", "my-26").rawValue)
        assertEquals("a:qwerty26", key("a", "my-26").shapeRawValue)
        for (raw in listOf("", "a", ":qwerty26", "a:keys40", "a:qwerty26#")) {
            assertNull(raw, KeyboardCompositionKey.parse(raw))
        }
    }

    @Test
    fun aNamedKeyboardDrawsItsLayoutWithTheDefaultPages() {
        val composition = withMine().composition(key("rime_ice", "my-26"))
        assertEquals(KeyboardComposition(mainLayoutID = "my-26"), composition)
    }

    @Test
    fun untilItSavesItsOwnANamedKeyboardUsesThePagesOfItsShape() {
        val shapePages =
            KeyboardComposition(mainLayoutID = "kana12", numericPageID = KeyboardPagePresets.NUMERIC_KEYPAD_ID)
        val registry = LayoutRegistry.create().withComposition(shapePages, "kagiroi:qwerty26")
        // Both boards of the shape shared these pages before keyboards named their layout.
        for (board in listOf("romaji26", "kana12")) {
            val composition = registry.composition(key("kagiroi", board))
            assertEquals(board, composition.mainLayoutID)
            assertEquals(KeyboardPagePresets.NUMERIC_KEYPAD_ID, composition.numericPageID)
        }
    }

    @Test
    fun savingOneKeyboardsPagesLeavesTheShapeAndItsOtherKeyboardsAlone() {
        val shapePages =
            KeyboardComposition(mainLayoutID = "kana12", numericPageID = KeyboardPagePresets.NUMERIC_KEYPAD_ID)
        val own =
            KeyboardComposition(mainLayoutID = "romaji26", symbolPageID = KeyboardPagePresets.SYMBOLS_CATEGORIZED_ID)
        val registry =
            LayoutRegistry
                .create()
                .withComposition(shapePages, "kagiroi:qwerty26")
                .withComposition(own, "kagiroi:qwerty26#romaji26")
        assertEquals(own, registry.composition(key("kagiroi", "romaji26")))
        assertEquals(shapePages, registry.compositions["kagiroi:qwerty26"])
        assertEquals(shapePages, registry.composition(key("kagiroi")))
        assertEquals(shapePages, registry.composition(key("kagiroi", "kana12")))
    }

    @Test
    fun aNamedPairingIsStoredWithItsOwnLayoutAsTheMainOne() {
        val registry =
            withMine().withComposition(KeyboardComposition(mainLayoutID = "qwerty26"), "rime_ice:qwerty26#my-26")
        assertEquals("my-26", registry.compositions.getValue("rime_ice:qwerty26#my-26").mainLayoutID)
    }

    @Test
    fun aStoredNamedPairingMustAgreeWithItsKey() {
        val registry = withMine()
        val corrupt =
            LayoutRegistry.fromWire(
                registry.layouts,
                registry.pages,
                mapOf("rime_ice:qwerty26#my-26" to KeyboardComposition(mainLayoutID = "qwerty26")),
            )
        assertThrows(LayoutException.Invalid::class.java) { corrupt.validate() }
    }

    @Test
    fun aNamedLayoutOfAnotherShapeOrSchemeIsRefused() {
        val bound =
            BuiltinLayouts.qwerty26
                .copyWithId("wubi-only", "wubi")
                .copy(input = LayoutInput(schemaID = "wubi86", builtinLayout = q26))
        val registry = LayoutRegistry.create(layouts = BuiltinLayouts.all + bound)
        assertThrows(LayoutException.Invalid::class.java) {
            registry.withComposition(KeyboardComposition(mainLayoutID = "phone9"), "rime_ice:qwerty26#phone9")
        }
        assertThrows(LayoutException.Invalid::class.java) {
            registry.withComposition(KeyboardComposition(mainLayoutID = "wubi-only"), "rime_ice:qwerty26#wubi-only")
        }
        assertThrows(LayoutException.Invalid::class.java) {
            registry.withComposition(KeyboardComposition(mainLayoutID = "gone"), "rime_ice:qwerty26#gone")
        }
        // A board of another family is refused for a new pairing, as it is for a whole shape.
        assertThrows(LayoutException.Invalid::class.java) {
            registry.withComposition(KeyboardComposition(mainLayoutID = "zhuyin41"), "kagiroi:qwerty26#zhuyin41")
        }
        registry.withComposition(KeyboardComposition(mainLayoutID = "wubi-only"), "wubi86:qwerty26#wubi-only")
    }

    @Test
    fun aNamedLayoutThatCanNoLongerBeDrawnReadsAsTheShapesPairing() {
        val registry = LayoutRegistry.create()
        // Gone, of another shape, and a board of another family.
        assertEquals("qwerty26", registry.composition(key("rime_ice", "gone")).mainLayoutID)
        assertEquals("qwerty26", registry.composition(key("rime_ice", "phone9")).mainLayoutID)
        assertEquals("romaji26", registry.composition(key("kagiroi", "zhuyin41")).mainLayoutID)
        val families = LayoutFamilyContext.of("dachen" to InputSchemeFamily.ZHUYIN)
        assertEquals("zhuyin41", registry.composition(key("dachen", "cangjie26"), families).mainLayoutID)
        // A folded shape the family cannot use reads through the identity shape, named or not.
        val folded = KeyboardCompositionKey("bopomofo", LayoutInput.Builtin.MERGED14, "merged14")
        assertEquals("zhuyin41", registry.composition(folded).mainLayoutID)
    }

    @Test
    fun aLayoutAKeyboardNamesCannotBeDeleted() {
        val registry = withMine()
        assertThrows(LayoutException.Invalid::class.java) { registry.remove("my-26", setOf("my-26")) }
        val paired = registry.withComposition(KeyboardComposition(mainLayoutID = "my-26"), "rime_ice:qwerty26#my-26")
        assertThrows(LayoutException.Invalid::class.java) { paired.remove("my-26") }
        assertEquals(null, registry.remove("my-26").layout("my-26"))
    }

    @Test
    fun namedPairingsAreListedPrunedAndForgotten() {
        val registry =
            withMine()
                .withComposition(KeyboardComposition(mainLayoutID = "my-26"), "pack:a:qwerty26#my-26")
                .withComposition(KeyboardComposition(mainLayoutID = "qwerty26"), "rime_ice:qwerty26")
        assertEquals(
            listOf(KeyboardCompositionKey("pack:a", q26, "my-26")),
            registry.keyboardsUsingLayout("my-26"),
        )
        assertEquals(
            listOf("pack:a:qwerty26#my-26", "rime_ice:qwerty26"),
            registry.keyboardsUsingPage(KeyboardPagePresets.SYMBOLS_SYSTEM_ID).map { it.rawValue },
        )
        val (pruned, removed) = registry.withPrunedCompositions { it == "pack:a" }
        assertEquals(1, removed)
        assertEquals(setOf("rime_ice:qwerty26"), pruned.compositions.keys)
        assertEquals(setOf("rime_ice:qwerty26"), registry.withoutComposition("pack:a:qwerty26#my-26").compositions.keys)
        assertSame(registry, registry.withoutComposition("absent:qwerty26"))
    }

    @Test
    fun namedPairingsSurviveTheStore() {
        val registry =
            withMine().withComposition(
                KeyboardComposition(mainLayoutID = "my-26", numericPageID = KeyboardPagePresets.NUMERIC_KEYPAD_ID),
                "rime_ice:qwerty26#my-26",
            )
        val decoded = LayoutRegistryCodec.decode(LayoutRegistryCodec.encode(registry))
        decoded.validate()
        assertEquals(registry.compositions, decoded.compositions)
    }
}
