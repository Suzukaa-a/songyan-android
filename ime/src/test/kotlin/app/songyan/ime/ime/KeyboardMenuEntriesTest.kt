package app.songyan.ime.ime

import app.songyan.ime.keyboard.engine.KeyboardSchemaDescriptor
import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardMenuEntriesTest {
    private val ice = listOf("rime_ice" to "雾凇拼音", "double_pinyin_flypy" to "小鹤双拼", "double_pinyin" to "自然码双拼")

    private fun schema(id: String) = KeyboardSchemaDescriptor(id = id, name = id)

    @Test
    fun theMenuOffersEveryInputMethodTheEngineListsLessTheHiddenOnes() {
        assertEquals(ice, KeyboardMenuEntries.schemas(ice, emptySet(), setOf("rime_ice")))
        assertEquals(
            listOf("rime_ice" to "雾凇拼音", "double_pinyin_flypy" to "小鹤双拼"),
            KeyboardMenuEntries.schemas(ice, setOf("double_pinyin"), setOf("rime_ice")),
        )
    }

    @Test
    fun theInputMethodInUseStaysEvenWhenHidden() {
        val shown = KeyboardMenuEntries.schemas(ice, setOf("rime_ice", "double_pinyin"), setOf("rime_ice"))
        assertEquals(listOf("rime_ice", "double_pinyin_flypy"), shown.map { it.first })
    }

    @Test
    fun beforeTheEngineListsTheBundledOnesStandIn() {
        assertEquals(
            BuiltinTemplateSchemas.SOURCE_SCHEMA_IDS,
            KeyboardMenuEntries
                .schemas(
                    emptyList(),
                    emptySet(),
                    emptySet(),
                ).map {
                    it.first
                },
        )
    }

    @Test
    fun hiddenLayoutsLeaveTheMenuButTheCurrentOneStays() {
        val shown = KeyboardMenuEntries.layouts(setOf("merged14", "phone9"), KeyboardLayoutKind.PHONE9)
        assertEquals(listOf(KeyboardLayoutKind.QWERTY26, KeyboardLayoutKind.MERGED18, KeyboardLayoutKind.PHONE9), shown)
    }

    private val all = KeyboardLayoutKind.entries

    @Test
    fun theLayoutChipsFollowTheShapesTheInputMethodCanUse() {
        val set =
            requireNotNull(
                KeyboardResourceSets.forEngineSchemas(
                    listOf(schema(BuiltinTemplateSchemas.QUANPIN), schema("jyut6ping3"), schema("bopomofo_tw")),
                ),
            )
        val cantonese = JumpTargets.usable(KeyboardLayoutKind.QWERTY26, set, "jyut6ping3")
        assertEquals(
            listOf(KeyboardLayoutKind.QWERTY26),
            KeyboardMenuEntries.layouts(emptySet(), KeyboardLayoutKind.QWERTY26, cantonese),
        )
        val zhuyin = JumpTargets.usable(KeyboardLayoutKind.QWERTY26, set, "bopomofo_tw")
        assertEquals(
            listOf(KeyboardLayoutKind.QWERTY26),
            KeyboardMenuEntries.layouts(emptySet(), KeyboardLayoutKind.QWERTY26, zhuyin),
        )
        // A bundled scheme keeps every chip.
        val bundled = JumpTargets.usable(KeyboardLayoutKind.QWERTY26, set, BuiltinTemplateSchemas.QUANPIN)
        assertEquals(
            KeyboardLayoutKind.entries.toList(),
            KeyboardMenuEntries.layouts(emptySet(), KeyboardLayoutKind.QWERTY26, bundled),
        )
    }

    @Test
    fun nextLayoutSkipsTheDisabledOnes() {
        val offered = KeyboardMenuEntries.layouts(setOf("merged14"), KeyboardLayoutKind.QWERTY26)
        val seen = mutableListOf<KeyboardLayoutKind>()
        var at = KeyboardLayoutKind.QWERTY26
        repeat(all.size * 2) {
            at = KeyboardMenuEntries.nextLayout(offered, at)!!
            seen += at
        }
        assertEquals(false, KeyboardLayoutKind.MERGED14 in seen)
        assertEquals(offered.toSet(), seen.toSet())
    }

    @Test
    fun nextLayoutWrapsAround() {
        assertEquals(all.first(), KeyboardMenuEntries.nextLayout(all, all.last()))
    }

    @Test
    fun nextLayoutDoesNothingWithOneEntryOrNone() {
        assertEquals(null, KeyboardMenuEntries.nextLayout(listOf(KeyboardLayoutKind.PHONE9), KeyboardLayoutKind.PHONE9))
        assertEquals(null, KeyboardMenuEntries.nextLayout(emptyList(), KeyboardLayoutKind.PHONE9))
    }

    @Test
    fun nextLayoutStartsAtTheFirstWhenTheCurrentOneIsNotOffered() {
        val offered = listOf(KeyboardLayoutKind.MERGED14, KeyboardLayoutKind.PHONE9)
        assertEquals(KeyboardLayoutKind.MERGED14, KeyboardMenuEntries.nextLayout(offered, KeyboardLayoutKind.QWERTY26))
    }

    @Test
    fun nextLayoutSkipsLayoutsTheInputMethodCannotUse() {
        // rime_ice runs on 26 keys only, so only the running layout is left: nothing to switch to.
        val set =
            requireNotNull(
                KeyboardResourceSets.forEngineSchemas(
                    listOf(
                        app.songyan.ime.keyboard.engine.KeyboardSchemaDescriptor(
                            BuiltinTemplateSchemas.QUANPIN,
                            BuiltinTemplateSchemas.QUANPIN,
                        ),
                        app.songyan.ime.keyboard.engine
                            .KeyboardSchemaDescriptor("rime_ice", "rime_ice"),
                    ),
                ),
            )
        val usable = JumpTargets.usable(KeyboardLayoutKind.QWERTY26, set, "rime_ice")
        val offered = KeyboardMenuEntries.layouts(emptySet(), KeyboardLayoutKind.QWERTY26).filter { it in usable }
        assertEquals(null, KeyboardMenuEntries.nextLayout(offered, KeyboardLayoutKind.QWERTY26))
    }
}
