package app.songyan.ime.ime

/**
 * The double-space period: a second space tapped soon after the first, right after an English word,
 * turns "word " into "word. ". Pure rules; [KeyboardController] holds the state and does the edit.
 */
internal object DoubleSpacePeriod {
    /** How long the first space stays eligible. */
    const val WINDOW_MS = 600L

    /** Text read before the caret: the space and the character before it. */
    const val CONTEXT_LIMIT = 2

    const val NOT_ARMED = Long.MIN_VALUE

    /**
     * Only plain text fields: a URL, an email address, a number, a password or a terminal takes the
     * spaces the user typed (a period there would corrupt the value).
     */
    fun allowedIn(policy: EditorPolicy): Boolean =
        policy.fieldKind == FieldKind.TEXT && !policy.numeric && !policy.privateInput && !policy.ascii

    fun withinWindow(
        armedAt: Long,
        now: Long,
        windowMs: Long = WINDOW_MS,
    ): Boolean = armedAt != NOT_ARMED && now - armedAt in 0 until windowMs

    /**
     * Whether the text before the caret ends in exactly one space that follows a letter or digit. The
     * editor is asked rather than trusted from memory: the caret may have moved since the first space.
     */
    fun replaceable(contextBeforeCursor: String?): Boolean {
        val text = contextBeforeCursor ?: return false
        if (text.length < 2 || !text.endsWith(" ")) return false
        val before = text.codePointBefore(text.length - 1)
        return Character.isLetterOrDigit(before)
    }
}
