package app.songyan.ime.keyboard.support

/**
 * Shift state for direct English input, driven by the UI's single/double-tap recognition:
 *  - [OFF]: lowercase;
 *  - [ONCE]: the next letter is uppercase, then back to off (digits/symbols do not consume it);
 *  - [LOCKED]: Caps Lock until released.
 *
 * Chinese composition is unaffected: keycap case is cosmetic and codes always go to the engine lowercase.
 */
enum class ShiftState {
    OFF,
    ONCE,
    LOCKED,
    ;

    /** Whether once/locked is active (the UI shows English keycaps uppercase). */
    val isActive: Boolean get() = this != OFF

    /** Whether to show the one-shot uppercase state (locked uses a separate lit style). */
    val isTemporary: Boolean get() = this == ONCE
}
