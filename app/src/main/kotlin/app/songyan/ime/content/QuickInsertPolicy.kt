package app.songyan.ime.content

import app.songyan.ime.ime.EditorPolicy

/**
 * Who may use the repeat-last-input buffer of the running editor.
 *
 * The buffer is process-lifetime memory, so three rules follow from the editor policy and are
 * enforced here instead of being re-derived at every call site:
 * - a password / `IME_FLAG_NO_PERSONALIZED_LEARNING` editor never seeds the buffer;
 * - such an editor is never offered the stored text, and cannot insert it;
 * - a new input session (new field, host or IME) drops the stored text, so one field can never
 *   replay what another field or another app typed.
 */
internal data class QuickInsertPolicy(
    val privateEditor: Boolean = false,
) {
    /** Whether a commit made in this editor may seed the repeat buffer. */
    fun mayRecordCommit(): Boolean = !privateEditor

    /** Whether the panel may offer the stored text, and the keyboard may insert it. */
    fun mayReplay(): Boolean = !privateEditor

    /** Whether the start of this input session must clear the buffer. */
    fun mustResetOnNewSession(restarting: Boolean): Boolean = !restarting || privateEditor

    companion object {
        fun of(editor: EditorPolicy): QuickInsertPolicy = QuickInsertPolicy(privateEditor = editor.privateInput)
    }
}
