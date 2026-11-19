package app.songyan.ime.layout

import app.songyan.ime.keyboard.engine.LayoutFamilyContext
import app.songyan.ime.keyboard.engine.PublishedKeyboard
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserKeyboardsTest {
    private val families = LayoutFamilyContext.UNKNOWN

    private fun k(id: String) = requireNotNull(PublishedKeyboard.parse(id)) { id }

    private val legacy =
        listOf(
            k("rime_ice:qwerty26#qwerty26"),
            k("rime_ice:phone9#phone9"),
            k("flypy:qwerty26#qwerty26"),
        )

    @Test
    fun untilEditedTheKeyboardsAreTheOnesTheMenuOffered() {
        assertEquals(legacy, UserKeyboards.effective(UserKeyboards.Stored(), listOf("rime_ice", "flypy"), legacy))
    }

    @Test
    fun addingAKeyboardStoresItsInputMethodAndLeavesTheOthersDerived() {
        val mine = k("rime_ice:qwerty26#my-26")
        val stored = UserKeyboards.adding(mine, UserKeyboards.Stored(), legacy)
        assertEquals(setOf("rime_ice"), stored.schemes)
        assertEquals(
            listOf("rime_ice:qwerty26#qwerty26", "rime_ice:phone9#phone9", "rime_ice:qwerty26#my-26"),
            stored.keyboards.map { it.id },
        )
        // The other input method still follows the hidden sets: here its phone keys came back.
        val later = legacy + k("flypy:phone9#phone9")
        assertEquals(
            stored.keyboards + listOf(k("flypy:qwerty26#qwerty26"), k("flypy:phone9#phone9")),
            UserKeyboards.effective(stored, listOf("rime_ice", "flypy"), later),
        )
        assertEquals(stored, UserKeyboards.adding(mine, stored, later))
    }

    @Test
    fun removingTheLastKeyboardLeavesTheInputMethodWithNone() {
        var stored = UserKeyboards.removing(k("flypy:qwerty26#qwerty26"), UserKeyboards.Stored(), legacy)
        assertEquals(setOf("flypy"), stored.schemes)
        assertEquals(
            listOf("rime_ice:qwerty26#qwerty26", "rime_ice:phone9#phone9"),
            UserKeyboards.effective(stored, listOf("rime_ice", "flypy"), legacy).map { it.id },
        )
        stored = UserKeyboards.adding(k("flypy:phone9#phone9"), stored, legacy)
        assertEquals(listOf("flypy:phone9#phone9"), stored.keyboards.map { it.id })
    }

    @Test
    fun inputMethodsTheEngineDoesNotListAreKeptButNotOffered() {
        val stored = UserKeyboards.adding(k("wubi86:qwerty26#qwerty26"), UserKeyboards.Stored(), emptyList())
        assertEquals(legacy, UserKeyboards.effective(stored, listOf("rime_ice", "flypy"), legacy))
        assertEquals(
            listOf("wubi86:qwerty26#qwerty26"),
            UserKeyboards.effective(stored, listOf("wubi86"), emptyList()).map { it.id },
        )
    }

    @Test
    fun aRecordWithoutALayoutIsNamedByItsShapesPairing() {
        val registry =
            LayoutRegistry
                .create()
                .copy(BuiltinLayouts.qwerty26, "my-26", "mine")
                .first
                .withComposition(KeyboardComposition(mainLayoutID = "my-26"), "rime_ice:qwerty26")
        assertEquals(k("rime_ice:qwerty26#my-26"), UserKeyboards.named(k("rime_ice:qwerty26"), registry, families))
        assertEquals(k("flypy:qwerty26#qwerty26"), UserKeyboards.named(k("flypy:qwerty26"), registry, families))
        assertEquals(k("bopomofo:qwerty26#zhuyin41"), UserKeyboards.named(k("bopomofo:qwerty26"), registry, families))
        assertEquals(
            k("kagiroi:qwerty26#kana12"),
            UserKeyboards.named(k("kagiroi:qwerty26#kana12"), registry, families),
        )
        // A named layout that is gone reads as the shape's pairing.
        assertEquals(k("rime_ice:qwerty26#my-26"), UserKeyboards.named(k("rime_ice:qwerty26#gone"), registry, families))
    }

    @Test
    fun onlyKeyboardsTheEngineCanRunAndTheCatalogCanDrawAreRunnable() {
        val registry = LayoutRegistry.create()
        val schemes = listOf(KeyboardScheme("rime_ice", setOf(KeyboardLayoutKind.QWERTY26)))
        val all =
            listOf(
                k("rime_ice:qwerty26#qwerty26"),
                k("rime_ice:merged14#merged14"),
                k("rime_ice:qwerty26#gone"),
                k("other:qwerty26#qwerty26"),
                k("rime_ice:qwerty26"),
            )
        assertEquals(
            listOf("rime_ice:qwerty26#qwerty26", "rime_ice:qwerty26"),
            UserKeyboards.runnable(all, schemes, registry, families).map { it.id },
        )
        assertTrue(UserKeyboards.drawsItsLayout(k("rime_ice:qwerty26#qwerty26"), registry, families))
        assertFalse(UserKeyboards.drawsItsLayout(k("rime_ice:qwerty26#phone9"), registry, families))
    }

    @Test
    fun anInputMethodChipKeepsTheLayoutThenTheShapeThenTakesTheFirst() {
        val target = listOf(k("flypy:phone9#phone9"), k("flypy:qwerty26#qwerty26"), k("flypy:qwerty26#my-26"))
        assertEquals(k("flypy:qwerty26#my-26"), UserKeyboards.switchTarget(target, k("rime_ice:qwerty26#my-26")))
        assertEquals(k("flypy:qwerty26#qwerty26"), UserKeyboards.switchTarget(target, k("rime_ice:qwerty26#other")))
        assertEquals(k("flypy:qwerty26#qwerty26"), UserKeyboards.switchTarget(target, k("rime_ice:qwerty26")))
        assertEquals(k("flypy:phone9#phone9"), UserKeyboards.switchTarget(target, k("rime_ice:merged14#merged14")))
        assertNull(UserKeyboards.switchTarget(emptyList(), k("rime_ice:qwerty26")))
    }

    @Test
    fun nextWrapsAroundAndStartsAtTheFirstWhenTheCurrentOneIsNotListed() {
        val list = listOf(k("a:qwerty26#qwerty26"), k("a:phone9#phone9"))
        assertEquals(list[1], UserKeyboards.next(list, list[0]))
        assertEquals(list[0], UserKeyboards.next(list, list[1]))
        assertEquals(list[0], UserKeyboards.next(list, k("a:merged14#merged14")))
        assertNull(UserKeyboards.next(listOf(list[0]), list[0]))
        assertNull(UserKeyboards.next(emptyList(), list[0]))
    }

    @Test
    fun theStoredListRoundTripsAndDropsWhatNoLongerParses() {
        val list = listOf(k("pack:a:qwerty26#my:lay#out"), k("rime_ice:phone9#phone9"))
        assertEquals(list, UserKeyboards.decode(UserKeyboards.encode(list)))
        assertEquals(emptyList<PublishedKeyboard>(), UserKeyboards.decode(null))
        assertEquals(emptyList<PublishedKeyboard>(), UserKeyboards.decode("not json"))
        assertEquals(
            listOf(k("a:qwerty26#x")),
            UserKeyboards.decode("""["a:qwerty26#x","broken","a:qwerty26#x","a:keys40"]"""),
        )
        assertEquals(setOf("my:lay#out", "phone9"), UserKeyboards.layoutIds(list))
    }

    @Test
    fun settingAnInputMethodReplacesItsKeyboardsAndKeepsTheOthers() {
        val stored =
            UserKeyboards.Stored(listOf(k("a:qwerty26#qwerty26"), k("b:merged14#merged14")), setOf("a", "b"))
        val result =
            UserKeyboards.setting(
                "a",
                listOf(k("a:phone9#phone9"), k("x:qwerty26#q"), k("a:phone9#phone9")),
                stored,
            )
        assertEquals(listOf(k("b:merged14#merged14"), k("a:phone9#phone9")), result.keyboards)
        assertEquals(setOf("a", "b"), result.schemes)
        val none = UserKeyboards.setting("new", emptyList(), result)
        assertEquals(result.keyboards, none.keyboards)
        assertEquals(setOf("a", "b", "new"), none.schemes)
    }

    @Test
    fun aKeyboardOnALayoutOfItsOwnIsRunnableOnceItsGroupingIsBuilt() {
        fun regrouped(
            id: String,
            first: String,
            second: String,
        ): LayoutDefinition {
            val base = BuiltinLayouts.merged14.copyWithId(id, id)
            val row = base.rows[0]
            val keys =
                row.keys.toMutableList().also {
                    it[0] = it[0].copy(label = first, action = LayoutAction.code(first.take(1).lowercase()))
                    it[1] = it[1].copy(label = second, action = LayoutAction.code(second.take(1).lowercase()))
                }
            return base.copy(rows = listOf(LayoutRow(keys)) + base.rows.drop(1))
        }
        val mine = regrouped("mine", "Q", "WER")
        val broken = regrouped("broken", "QWE", "ER")
        val registry = LayoutRegistry.create().install(mine.encoded()).install(broken.encoded())
        val fold = requireNotNull(LayoutFold.of(mine, KeyboardLayoutKind.MERGED14))
        val all = listOf(k("rime_ice:merged14#mine"), k("rime_ice:merged14#broken"), k("rime_ice:merged14#merged14"))
        val shapes = setOf(KeyboardLayoutKind.MERGED14)
        assertEquals(
            listOf("rime_ice:merged14#merged14"),
            UserKeyboards.runnable(all, listOf(KeyboardScheme("rime_ice", shapes)), registry, families).map { it.id },
        )
        assertEquals(
            listOf("rime_ice:merged14#mine", "rime_ice:merged14#merged14"),
            UserKeyboards
                .runnable(
                    all,
                    listOf(KeyboardScheme("rime_ice", shapes, builtFolds = setOf(fold.id))),
                    registry,
                    families,
                ).map { it.id },
        )
        assertEquals("merged14:" + fold.id, LayoutFold.shapeWire(registry, k("rime_ice:merged14#mine")))
        assertEquals("merged14", LayoutFold.shapeWire(registry, k("rime_ice:merged14#merged14")))
        assertNull(LayoutFold.shapeWire(registry, k("rime_ice:merged14#broken")))
    }
}
