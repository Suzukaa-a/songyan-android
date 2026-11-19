package app.songyan.ime.keyboard.layout

import app.songyan.ime.keyboard.engine.KeyboardResourceSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A layout's own grouping becomes a fold of its own; the bundled grouping stays the bundled fold. */
class CompactFoldOfTest {
    private fun groups(vararg keys: String): Map<Char, Char> = keys.flatMap { key -> key.map { it to key[0] } }.toMap()

    private val bundled14 = groups("qw", "er", "ty", "ui", "op", "as", "df", "gh", "jk", "l", "zx", "cv", "bn", "m")

    @Test
    fun theBundledGroupingIsTheBundledFold() {
        assertSame(CompactFold.MERGED14, CompactFold.of(KeyboardLayoutKind.MERGED14, bundled14))
        assertSame(CompactFold.PHONE9, CompactFold.of(KeyboardLayoutKind.PHONE9, CompactFold.PHONE9.map))
    }

    @Test
    fun anotherGroupingGetsItsOwnIdAndKeepsTheKeyCount() {
        val moved = groups("q", "wer", "ty", "ui", "op", "as", "df", "gh", "jk", "l", "zx", "cv", "bn", "m")
        val fold = CompactFold.of(KeyboardLayoutKind.MERGED14, moved)!!
        assertTrue(fold.id, fold.id.matches(Regex("14u[0-9a-f]{8}")))
        assertEquals("14", fold.keyCount)
        assertFalse(fold.isBuiltin)
        assertEquals('w', fold.map['e'])
        assertEquals('q', fold.map['q'])
        // The same grouping always gets the same id; another one never does.
        assertEquals(fold.id, CompactFold.of(KeyboardLayoutKind.MERGED14, moved)!!.id)
        assertNotEquals(
            fold.id,
            CompactFold
                .of(
                    KeyboardLayoutKind.MERGED14,
                    groups("qwe", "r", "ty", "ui", "op", "as", "df", "gh", "jk", "l", "zx", "cv", "bn", "m"),
                )!!
                .id,
        )
        // Its companion id is a generated one, like the bundled folds'.
        assertTrue(KeyboardResourceSet.EncodingAdapter.isGeneratedID("keyboard_0123456789abcdef0123_" + fold.id))
    }

    @Test
    fun aNineKeyGroupingOfItsOwnSendsDigits() {
        val custom =
            CompactFold.PHONE9.map
                .toMutableMap()
                .apply { put('c', '3') }
        val fold = CompactFold.of(KeyboardLayoutKind.PHONE9, custom)!!
        assertEquals("9", fold.keyCount)
        assertTrue(fold.isPhoneDigits)
        assertEquals('3', fold.map['c'])
    }

    @Test
    fun aGroupingThatCannotFoldIsRefused() {
        // A key that sends a letter outside its group.
        assertNull(CompactFold.of(KeyboardLayoutKind.MERGED14, bundled14 + ('w' to 'x')))
        // A 9-key letter on 1.
        assertNull(CompactFold.of(KeyboardLayoutKind.PHONE9, CompactFold.PHONE9.map + ('a' to '1')))
        // 26 keys do not fold.
        assertNull(CompactFold.of(KeyboardLayoutKind.QWERTY26, bundled14))
    }

    @Test
    fun codesOtherThanLettersFoldTheSameWay() {
        // A stroke layout of three keys: 横竖 on one, 撇点 on another, 折 alone.
        val strokes = mapOf('h' to 'h', 's' to 'h', 'p' to 'p', 'n' to 'p', 'z' to 'z')
        val fold = requireNotNull(CompactFold.of(KeyboardLayoutKind.MERGED14, strokes))
        assertFalse(fold.isBuiltin)
        assertEquals('h', fold.map['s'])
        assertEquals(setOf('h', 'p', 'z'), fold.keysSent)
    }
}
