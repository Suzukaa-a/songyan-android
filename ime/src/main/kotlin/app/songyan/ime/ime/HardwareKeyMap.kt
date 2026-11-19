package app.songyan.ime.ime

import android.view.KeyEvent

/**
 * Maps a hardware key event to the engine's key space (X11 keysym plus librime modifier mask).
 *
 * Kept free of `KeyEvent` instances so it runs in JVM tests; the caller reads the event and passes
 * the character the key produces with only Shift, Caps Lock and Num Lock applied (so Ctrl+A still
 * names `a`).
 */
internal object HardwareKeyMap {
    /**
     * One engine key. [printable] is a key that types a visible character on its own (not a
     * shortcut): the first one asks for the candidate bar.
     */
    data class Stroke(
        val keycode: Int,
        val modifiers: Int,
        val printable: Boolean,
    )

    // librime key_table.h; this module does not depend on the engine module.
    private const val SHIFT = 1 shl 0
    private const val LOCK = 1 shl 1
    private const val CONTROL = 1 shl 2
    private const val ALT = 1 shl 3
    private const val RELEASE = 1 shl 30

    private const val FIRST_VISIBLE = 0x21
    private const val LAST_ASCII = 0x7E
    private const val SPACE = 0x20
    private const val FIRST_FUNCTION_KEYSYM = 0xFFBE

    private val named: Map<Int, Int> =
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
            KeyEvent.KEYCODE_CAPS_LOCK to 0xFFE5,
            KeyEvent.KEYCODE_SHIFT_LEFT to 0xFFE1,
            KeyEvent.KEYCODE_SHIFT_RIGHT to 0xFFE2,
            KeyEvent.KEYCODE_CTRL_LEFT to 0xFFE3,
            KeyEvent.KEYCODE_CTRL_RIGHT to 0xFFE4,
            KeyEvent.KEYCODE_ALT_LEFT to 0xFFE9,
            KeyEvent.KEYCODE_ALT_RIGHT to 0xFFEA,
            KeyEvent.KEYCODE_NUMPAD_ENTER to 0xFF8D,
        )

    private val numpad: Map<Int, Int> =
        buildMap {
            for (digit in 0..9) put(KeyEvent.KEYCODE_NUMPAD_0 + digit, 0xFFB0 + digit)
            put(KeyEvent.KEYCODE_NUMPAD_DOT, 0xFFAE)
            put(KeyEvent.KEYCODE_NUMPAD_ADD, 0xFFAB)
            put(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, 0xFFAD)
            put(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, 0xFFAA)
            put(KeyEvent.KEYCODE_NUMPAD_DIVIDE, 0xFFAF)
        }

    /** The key's own modifier bit, which X reports the other way round from Android. */
    private val ownBit: Map<Int, Int> =
        mapOf(
            KeyEvent.KEYCODE_SHIFT_LEFT to SHIFT,
            KeyEvent.KEYCODE_SHIFT_RIGHT to SHIFT,
            KeyEvent.KEYCODE_CTRL_LEFT to CONTROL,
            KeyEvent.KEYCODE_CTRL_RIGHT to CONTROL,
            KeyEvent.KEYCODE_ALT_LEFT to ALT,
            KeyEvent.KEYCODE_ALT_RIGHT to ALT,
        )

    /** Null when the key is not an engine key (Meta, media and system keys, non-ASCII, dead keys). */
    fun stroke(
        keyCode: Int,
        metaState: Int,
        unicodeChar: Int,
        up: Boolean,
    ): Stroke? {
        if (metaState and KeyEvent.META_META_ON != 0) return null
        var mask = 0
        if (metaState and KeyEvent.META_SHIFT_ON != 0) mask = mask or SHIFT
        if (metaState and KeyEvent.META_CAPS_LOCK_ON != 0) mask = mask or LOCK
        if (metaState and KeyEvent.META_CTRL_ON != 0) mask = mask or CONTROL
        if (metaState and KeyEvent.META_ALT_ON != 0) mask = mask or ALT
        ownBit[keyCode]?.let { own -> mask = if (up) mask or own else mask and own.inv() }
        if (up) mask = mask or RELEASE

        val numpadKeysym = numpad[keyCode]
        if (numpadKeysym != null) {
            // Without Num Lock these keys navigate; the app owns that.
            if (metaState and KeyEvent.META_NUM_LOCK_ON == 0) return null
            return Stroke(numpadKeysym, mask, printable = false)
        }
        if (unicodeChar in SPACE..LAST_ASCII) {
            val shortcut = metaState and (KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON) != 0
            return Stroke(unicodeChar, mask, printable = unicodeChar >= FIRST_VISIBLE && !shortcut)
        }
        val keysym =
            named[keyCode]
                ?: if (keyCode in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12) {
                    FIRST_FUNCTION_KEYSYM + (keyCode - KeyEvent.KEYCODE_F1)
                } else {
                    return null
                }
        return Stroke(keysym, mask, printable = false)
    }

    /** The character a key produces for [stroke]: Shift, Caps Lock and Num Lock only. */
    const val CHARACTER_META: Int =
        KeyEvent.META_SHIFT_MASK or KeyEvent.META_CAPS_LOCK_ON or KeyEvent.META_NUM_LOCK_ON
}
