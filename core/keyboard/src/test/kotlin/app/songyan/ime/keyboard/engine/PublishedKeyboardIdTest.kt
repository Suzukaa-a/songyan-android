package app.songyan.ime.keyboard.engine

import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A keyboard is an input method, a shape and the layout it draws; its id carries all three. */
class PublishedKeyboardIdTest {
    @Test
    fun aRecordWithoutALayoutKeepsTheOldId() {
        val keyboard = PublishedKeyboard("rime_ice", KeyboardLayoutKind.MERGED14)
        assertEquals("rime_ice:merged14", keyboard.id)
        assertEquals(keyboard, PublishedKeyboard.parse("rime_ice:merged14"))
    }

    @Test
    fun aNamedLayoutFollowsTheShape() {
        val keyboard = PublishedKeyboard("kagiroi", KeyboardLayoutKind.QWERTY26, "kana12")
        assertEquals("kagiroi:qwerty26#kana12", keyboard.id)
        assertEquals("kagiroi:qwerty26", keyboard.combinationID)
        assertEquals(keyboard, PublishedKeyboard.parse(keyboard.id))
    }

    @Test
    fun idsRoundTripWithSeparatorsInsideEitherPart() {
        for (schema in listOf("a", "pack:method", "a:b:c")) {
            for (board in listOf(null, "qwerty26", "my#layout", "my:layout", "a:qwerty26#b", "#")) {
                for (kind in KeyboardLayoutKind.entries) {
                    val keyboard = PublishedKeyboard(schema, kind, board)
                    assertEquals(keyboard.id, keyboard, PublishedKeyboard.parse(keyboard.id))
                }
            }
        }
    }

    @Test
    fun malformedIdsAreRefused() {
        for (id in listOf("", "rime_ice", ":qwerty26", "rime_ice:", "rime_ice:keys40", "rime_ice:qwerty26#", "#x")) {
            assertNull(id, PublishedKeyboard.parse(id))
        }
    }

    @Test
    fun keyboardsOfOneShapeShareOneCompanion() {
        val tasks =
            PublishedKeyboard.generationTasks(
                listOf(
                    PublishedKeyboard("rime_ice", KeyboardLayoutKind.MERGED14, "merged14"),
                    PublishedKeyboard("rime_ice", KeyboardLayoutKind.MERGED14, "my-14"),
                    PublishedKeyboard("rime_ice", KeyboardLayoutKind.QWERTY26, "my-26"),
                    PublishedKeyboard("rime_ice", KeyboardLayoutKind.PHONE9),
                ),
            )
        assertEquals(listOf("rime_ice:merged14", "rime_ice:phone9"), tasks.map { it.id })
    }

    @Test
    fun anAddedKeyboardOpensItsShapeWhateverLayoutItDraws() {
        val set =
            KeyboardResourceSet
                .builtin("/shared", "/user")
                .copy(
                    publishedKeyboards =
                        listOf(PublishedKeyboard(BuiltinTemplateSchemas.QUANPIN, KeyboardLayoutKind.QWERTY26, "my-26")),
                )
        assertEquals(
            listOf(BuiltinTemplateSchemas.QUANPIN + ":qwerty26"),
            set.availableCombinations.map { it.id },
        )
    }
}
