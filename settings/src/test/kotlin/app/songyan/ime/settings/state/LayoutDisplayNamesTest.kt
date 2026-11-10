package app.songyan.ime.settings.state

import app.songyan.ime.keyboard.engine.InputSchemeFamily
import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.KeyboardComposition
import app.songyan.ime.layout.KeyboardCompositionKey
import app.songyan.ime.layout.KeyboardPagePresets
import app.songyan.ime.layout.LayoutInput
import app.songyan.ime.layout.LayoutRegistry
import app.songyan.ime.settings.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The automatic names of the two shipped physical boards, and the fallbacks that must not move. */
class LayoutDisplayNamesTest {
    private val qwerty = KeyboardLayoutOption.QWERTY26
    private val bopomofo = KeyboardCompositionKey("bopomofo_tw", LayoutInput.Builtin.QWERTY26)
    private val cangjie = KeyboardCompositionKey("cangjie5", LayoutInput.Builtin.QWERTY26)
    private val mine = BuiltinLayouts.zhuyin41.copyWithId("mine", "我的注音")

    private fun registry(vararg compositions: Pair<KeyboardCompositionKey, String>): LayoutRegistry {
        var result = LayoutRegistry.create(layouts = BuiltinLayouts.all + mine)
        for ((key, mainLayoutId) in compositions) {
            result = result.withComposition(KeyboardComposition(mainLayoutID = mainLayoutId), key.rawValue)
        }
        return result
    }

    @Test
    fun `the two known physical boards are worded by resources`() {
        assertEquals(R.string.layout_name_zhuyin41, bundledLayoutNameRes("zhuyin41"))
        assertEquals(R.string.layout_name_cangjie26, bundledLayoutNameRes("cangjie26"))
        // The shipped shapes, an author's layout id and an empty id are never renamed.
        for (id in listOf("qwerty26", "merged14", "merged18", "phone9", "mine", "")) {
            assertNull(id, bundledLayoutNameRes(id))
        }
        assertNull(bundledLayoutNameRes(null))
    }

    @Test
    fun `a bundled physical board shows its own name instead of the shape name`() {
        assertEquals(
            LayoutDisplayName.Worded(R.string.layout_name_zhuyin41),
            layoutDisplayName(BuiltinLayouts.zhuyin41, qwerty),
        )
        assertEquals(
            LayoutDisplayName.Worded(R.string.layout_name_cangjie26),
            layoutDisplayName(BuiltinLayouts.cangjie26, qwerty),
        )
    }

    @Test
    fun `the four shipped shapes keep today's names`() {
        assertEquals(
            LayoutDisplayName.Worded(R.string.layout_name_26),
            layoutDisplayName(BuiltinLayouts.qwerty26, qwerty),
        )
        assertEquals(
            LayoutDisplayName.Worded(R.string.layout_name_14),
            layoutDisplayName(BuiltinLayouts.merged14, KeyboardLayoutOption.MERGED14),
        )
        assertEquals(
            LayoutDisplayName.Worded(R.string.layout_name_18),
            layoutDisplayName(BuiltinLayouts.merged18, KeyboardLayoutOption.MERGED18),
        )
        assertEquals(
            LayoutDisplayName.Worded(R.string.layout_name_9),
            layoutDisplayName(BuiltinLayouts.phone9, KeyboardLayoutOption.PHONE9),
        )
    }

    @Test
    fun `an author's own layout keeps its saved name`() {
        assertEquals(LayoutDisplayName.Authored("我的注音"), layoutDisplayName(mine, qwerty))
        // A copy of the 26-key shape is not renamed to the shape name either.
        val keyboard = BuiltinLayouts.qwerty26.copyWithId("my-26", "我的键盘")
        assertEquals(LayoutDisplayName.Authored("我的键盘"), layoutDisplayName(keyboard, qwerty))
    }

    @Test
    fun `an edited bundled board keeps the name the user saved`() {
        // Editing a bundled board (here its name) makes it the author's own, so the shipped resource
        // name must not replace what the user wrote.
        assertEquals(
            LayoutDisplayName.Authored("我的注音"),
            layoutDisplayName(BuiltinLayouts.zhuyin41.renamed("我的注音"), qwerty),
        )
        assertEquals(
            LayoutDisplayName.Authored("我的键盘"),
            layoutDisplayName(BuiltinLayouts.qwerty26.renamed("我的键盘"), qwerty),
        )
    }

    @Test
    fun `a bundled board with edited keys but its shipped name is still worded by resources`() {
        // The stored name is the shipped data's Chinese; a Japanese or English screen must not show it.
        val edited = BuiltinLayouts.zhuyin41.copy(rows = BuiltinLayouts.zhuyin41.rows.dropLast(1))
        assertEquals(LayoutDisplayName.Worded(R.string.layout_name_zhuyin41), layoutDisplayName(edited, qwerty))
        val editedShape = BuiltinLayouts.qwerty26.copy(rows = BuiltinLayouts.qwerty26.rows.dropLast(1))
        assertEquals(LayoutDisplayName.Worded(R.string.layout_name_26), layoutDisplayName(editedShape, qwerty))
    }

    @Test
    fun `the bundled pages are worded by resources and the user's pages keep their names`() {
        val expected =
            mapOf(
                KeyboardPagePresets.symbolsSystem to R.string.page_name_symbols_system,
                KeyboardPagePresets.symbolsCategorized to R.string.page_name_symbols_categorized,
                KeyboardPagePresets.numericAutomatic to R.string.page_name_numeric_automatic,
                KeyboardPagePresets.numericKeypad to R.string.page_name_numeric_keypad,
                KeyboardPagePresets.englishQwerty to R.string.page_name_english_qwerty,
            )
        assertEquals("every page the library lists is covered", KeyboardPagePresets.all.toSet(), expected.keys)
        for ((page, resource) in expected) {
            assertEquals(page.id, LayoutDisplayName.Worded(resource), pageDisplayName(page))
        }
        val mine = KeyboardPagePresets.symbolsCategorized.copy(id = "mine", name = "我的符号")
        assertEquals(LayoutDisplayName.Authored("我的符号"), pageDisplayName(mine))
    }

    @Test
    fun `no catalog entry keeps the shape name`() {
        assertEquals(LayoutDisplayName.Worded(R.string.layout_name_26), layoutDisplayName(null, qwerty))
        assertEquals(
            LayoutDisplayName.Worded(R.string.layout_name_9),
            layoutDisplayName(null, KeyboardLayoutOption.PHONE9),
        )
    }

    @Test
    fun `without a saved pairing the family preferred board names the keyboard`() {
        assertEquals(
            CurrentMainLayout("zhuyin41", LayoutDisplayName.Worded(R.string.layout_name_zhuyin41)),
            currentMainLayout("bopomofo_tw", "qwerty26", InputSchemeFamily.ZHUYIN, LayoutRegistry.create()),
        )
        assertEquals(
            CurrentMainLayout("cangjie26", LayoutDisplayName.Worded(R.string.layout_name_cangjie26)),
            currentMainLayout(
                "cangjie5",
                "qwerty26",
                InputSchemeFamily.CANGJIE_RADICALS,
                LayoutRegistry.create(),
            ),
        )
    }

    @Test
    fun `a saved pairing wins over the family preferred board`() {
        // A 26-key pairing saved before the board shipped stays 26 keys: explicit pairing, not a guess.
        assertEquals(
            CurrentMainLayout("qwerty26", LayoutDisplayName.Worded(R.string.layout_name_26)),
            currentMainLayout("bopomofo_tw", "qwerty26", InputSchemeFamily.ZHUYIN, registry(bopomofo to "qwerty26")),
        )
        // A saved physical pairing reads back as that board.
        assertEquals(
            "zhuyin41",
            currentMainLayout(
                "bopomofo_tw",
                "qwerty26",
                InputSchemeFamily.ZHUYIN,
                registry(bopomofo to "zhuyin41"),
            )?.id,
        )
        // A saved layout of the user's own keeps the author's name.
        assertEquals(
            CurrentMainLayout("mine", LayoutDisplayName.Authored("我的注音")),
            currentMainLayout("bopomofo_tw", "qwerty26", InputSchemeFamily.ZHUYIN, registry(bopomofo to "mine")),
        )
        // The cangjie family reads its own saved board, never the other family's.
        assertEquals(
            "cangjie26",
            currentMainLayout(
                "cangjie5",
                "qwerty26",
                InputSchemeFamily.CANGJIE_RADICALS,
                registry(cangjie to "cangjie26"),
            )?.id,
        )
    }

    @Test
    fun `nothing is named before the catalog or the input method is known`() {
        assertNull(currentMainLayout(null, "qwerty26", null, LayoutRegistry.create()))
        assertNull(currentMainLayout("bopomofo_tw", "qwerty26", InputSchemeFamily.ZHUYIN, null))
        assertNull(currentMainLayout("bopomofo_tw", "not-a-shape", null, LayoutRegistry.create()))
    }
}
