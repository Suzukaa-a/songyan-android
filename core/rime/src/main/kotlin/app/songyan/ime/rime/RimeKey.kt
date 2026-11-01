package app.songyan.ime.rime

/**
 * Key code in librime's own keycode space.
 *
 * librime's `process_key` takes an X11-keysym-style code: printable ASCII passes
 * through as the character value, named editing keys use the classic X11 values.
 */
object RimeKeyCode {
    const val SPACE: Int = 0x20
    const val APOSTROPHE: Int = 0x27
    const val COMMA: Int = 0x2C
    const val HYPHEN: Int = 0x2D
    const val PERIOD: Int = 0x2E
    const val SLASH: Int = 0x2F
    const val DIGIT_0: Int = 0x30
    const val SEMICOLON: Int = 0x3B
    const val EQUAL: Int = 0x3D
    const val A: Int = 0x41
    const val Z: Int = 0x5A
    const val LOWERCASE_A: Int = 0x61
    const val LOWERCASE_Z: Int = 0x7A
    const val TILDE: Int = 0x7E

    // X11 keysyms for editing keys (matches librime's keysymdef.h).
    const val BACKSPACE: Int = 0xFF08
    const val TAB: Int = 0xFF09
    const val RETURN: Int = 0xFF0D
    const val ESCAPE: Int = 0xFF1B
    const val DELETE: Int = 0xFFFF
    const val HOME: Int = 0xFF50
    const val LEFT: Int = 0xFF51
    const val UP: Int = 0xFF52
    const val RIGHT: Int = 0xFF53
    const val DOWN: Int = 0xFF54
    const val PAGE_UP: Int = 0xFF55
    const val PAGE_DOWN: Int = 0xFF56
    const val END: Int = 0xFF57

    /** XK_VoidSymbol: a key no processor handles, so librime reports it as unhandled. */
    const val VOID_SYMBOL: Int = 0xFFFFFF
}

/** librime modifier bits (`RimeModifier` in librime's key_table.h). */
object RimeModifier {
    const val NONE: Int = 0
    const val SHIFT: Int = 1 shl 0
    const val LOCK: Int = 1 shl 1
    const val CONTROL: Int = 1 shl 2

    /** kMod1Mask. */
    const val ALT: Int = 1 shl 3
    const val META: Int = 1 shl 28
}

/** One key press delivered to the engine. */
data class RimeKey(
    val keycode: Int,
    val modifiers: Int = RimeModifier.NONE,
) {
    companion object {
        val BACKSPACE = RimeKey(RimeKeyCode.BACKSPACE)
        val RETURN = RimeKey(RimeKeyCode.RETURN)
        val TAB = RimeKey(RimeKeyCode.TAB)
        val SPACE = RimeKey(RimeKeyCode.SPACE)
        val ESCAPE = RimeKey(RimeKeyCode.ESCAPE)

        /** Keycode for a single printable ASCII character, or null. */
        fun characterCode(ch: Char): Int? = if (ch.code in 0x20..0x7E) ch.code else null

        /** Letter keys are sent lowercase; other printable ASCII passes through. */
        fun baseKeycode(ch: Char): Int? {
            val code = characterCode(ch) ?: return null
            return if (code in RimeKeyCode.A..RimeKeyCode.Z) code + 0x20 else code
        }

        /**
         * A text key: an uppercase letter is the capital with Shift held (what a hardware keyboard
         * sends), every other character is its base key.
         */
        private fun textKey(ch: Char): RimeKey? {
            val code = characterCode(ch) ?: return null
            if (code in RimeKeyCode.A..RimeKeyCode.Z) return RimeKey(code, RimeModifier.SHIFT)
            return RimeKey(code)
        }

        /**
         * Maps the front end's logical key value to an engine key.
         *
         * The keyboard passes single-character letter/digit codes plus the reserved
         * control values used by the shared keyboard core: backspace is U+0008,
         * space is " ", return is U+000A, cancel is "Esc" and "Home" highlights the first candidate. Anything else is not
         * an engine key and returns null so the caller keeps the text itself.
         */
        fun fromKeyValue(keyValue: String): RimeKey? =
            when (keyValue) {
                "\u0008" -> BACKSPACE
                " " -> SPACE
                "\n" -> RETURN
                "Esc" -> ESCAPE
                "Home" -> RimeKey(RimeKeyCode.HOME)
                // Only a single printable ASCII character is a text key; longer values are
                // front-end labels (for example "shift") and must not be sent as one key.
                else -> if (keyValue.length == 1) textKey(keyValue[0]) else null
            }
    }
}
