package app.songyan.ime.ime

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareKeyMapTest {
    private val shiftMeta = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON

    private fun stroke(
        keyCode: Int,
        meta: Int = 0,
        char: Int = 0,
        up: Boolean = false,
    ) = HardwareKeyMap.stroke(keyCode, meta, char, up)

    @Test
    fun lettersAndShiftedLetters() {
        assertEquals(HardwareKeyMap.Stroke('n'.code, 0, true), stroke(KeyEvent.KEYCODE_N, 0, 'n'.code))
        assertEquals(HardwareKeyMap.Stroke('N'.code, 1, true), stroke(KeyEvent.KEYCODE_N, shiftMeta, 'N'.code))
    }

    @Test
    fun shiftedDigitIsItsSymbolWithShiftMask() {
        assertEquals(HardwareKeyMap.Stroke('!'.code, 1, true), stroke(KeyEvent.KEYCODE_1, shiftMeta, '!'.code))
    }

    @Test
    fun ctrlLetterKeepsTheLetterAndTheControlMask() {
        val result = stroke(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON, 'c'.code)
        assertEquals('c'.code, result?.keycode)
        assertEquals(1 shl 2, result?.modifiers)
        assertFalse("a shortcut does not raise the bar", result!!.printable)
    }

    @Test
    fun capsLockSetsTheLockMask() {
        assertEquals(
            HardwareKeyMap.Stroke('A'.code, 1 shl 1, true),
            stroke(KeyEvent.KEYCODE_A, KeyEvent.META_CAPS_LOCK_ON, 'A'.code),
        )
    }

    @Test
    fun spaceIsAKeyButNotAPrintableOne() {
        assertEquals(HardwareKeyMap.Stroke(0x20, 0, false), stroke(KeyEvent.KEYCODE_SPACE, 0, 0x20))
    }

    @Test
    fun editingAndNavigationKeysUseX11Keysyms() {
        val expected =
            mapOf(
                KeyEvent.KEYCODE_ENTER to 0xFF0D,
                KeyEvent.KEYCODE_DEL to 0xFF08,
                KeyEvent.KEYCODE_FORWARD_DEL to 0xFFFF,
                KeyEvent.KEYCODE_TAB to 0xFF09,
                KeyEvent.KEYCODE_ESCAPE to 0xFF1B,
                KeyEvent.KEYCODE_DPAD_LEFT to 0xFF51,
                KeyEvent.KEYCODE_DPAD_UP to 0xFF52,
                KeyEvent.KEYCODE_DPAD_RIGHT to 0xFF53,
                KeyEvent.KEYCODE_DPAD_DOWN to 0xFF54,
                KeyEvent.KEYCODE_MOVE_HOME to 0xFF50,
                KeyEvent.KEYCODE_MOVE_END to 0xFF57,
                KeyEvent.KEYCODE_PAGE_UP to 0xFF55,
                KeyEvent.KEYCODE_PAGE_DOWN to 0xFF56,
                KeyEvent.KEYCODE_INSERT to 0xFF63,
            )
        for ((code, keysym) in expected) {
            val result = stroke(code)
            assertEquals("keycode $code", keysym, result?.keycode)
            assertFalse(result!!.printable)
        }
    }

    @Test
    fun functionKeys() {
        assertEquals(0xFFBE, stroke(KeyEvent.KEYCODE_F1)?.keycode)
        assertEquals(0xFFC9, stroke(KeyEvent.KEYCODE_F12)?.keycode)
    }

    @Test
    fun numpadFollowsNumLock() {
        val on = KeyEvent.META_NUM_LOCK_ON
        assertEquals(0xFFB5, stroke(KeyEvent.KEYCODE_NUMPAD_5, on, '5'.code)?.keycode)
        assertEquals(0xFFAB, stroke(KeyEvent.KEYCODE_NUMPAD_ADD, on, '+'.code)?.keycode)
        assertEquals(0xFFAE, stroke(KeyEvent.KEYCODE_NUMPAD_DOT, on, '.'.code)?.keycode)
        assertNull(stroke(KeyEvent.KEYCODE_NUMPAD_5, 0, 0))
        assertEquals(0xFF8D, stroke(KeyEvent.KEYCODE_NUMPAD_ENTER)?.keycode)
    }

    @Test
    fun releaseSetsTheReleaseMask() {
        assertEquals(
            HardwareKeyMap.Stroke('n'.code, 1 shl 30, true),
            stroke(KeyEvent.KEYCODE_N, 0, 'n'.code, up = true),
        )
    }

    @Test
    fun aModifiersOwnBitFollowsXConvention() {
        // Android reports Shift's own bit on its press and drops it on its release; X is the reverse.
        assertEquals(0, stroke(KeyEvent.KEYCODE_SHIFT_LEFT, shiftMeta)?.modifiers)
        assertEquals(1 or (1 shl 30), stroke(KeyEvent.KEYCODE_SHIFT_LEFT, 0, up = true)?.modifiers)
        assertEquals(0xFFE1, stroke(KeyEvent.KEYCODE_SHIFT_LEFT, shiftMeta)?.keycode)
        assertEquals(0xFFE4, stroke(KeyEvent.KEYCODE_CTRL_RIGHT, KeyEvent.META_CTRL_ON)?.keycode)
        assertEquals(0, stroke(KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.META_ALT_ON)?.modifiers)
    }

    @Test
    fun metaCombinationsNonAsciiAndDeadKeysAreNotEngineKeys() {
        assertNull(stroke(KeyEvent.KEYCODE_A, KeyEvent.META_META_ON, 'a'.code))
        assertNull(stroke(KeyEvent.KEYCODE_META_LEFT))
        assertNull(stroke(KeyEvent.KEYCODE_E, 0, 0xE9))
        assertNull(stroke(KeyEvent.KEYCODE_E, 0, 0x300 or Int.MIN_VALUE))
        assertNull(stroke(KeyEvent.KEYCODE_VOLUME_UP))
        assertNull(stroke(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertTrue(stroke(KeyEvent.KEYCODE_A, 0, 'a'.code) != null)
    }
}
