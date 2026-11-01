package app.songyan.ime.settings.ui

import app.songyan.ime.content.CommonPhrase
import app.songyan.ime.content.LocalQuickContentStore

/** Why the phrase editor refused what was typed; the screen maps each to copy. */
enum class PhraseInputError {
    TEXT_REQUIRED,
    TEXT_TOO_LONG,
    DUPLICATE,
    LIMIT_REACHED,
    SHORTCUT_TOO_LONG,
}

/**
 * The phrase rules of the store, checked before the dialog closes so the user learns about a
 * duplicate or the budget while the editor is still open. Pure: the caller passes the current
 * phrases, which keeps the rules unit-testable and the dialog synchronous.
 */
object PhraseValidation {
    /**
     * Returns the first reason [text] cannot be saved, or null when it can.
     *
     * [editingId] is the phrase being edited (null when adding); it is excluded from the duplicate
     * check so re-saving an unchanged phrase is allowed.
     */
    fun check(
        text: String,
        editingId: String?,
        phrases: List<CommonPhrase>,
        canAdd: Boolean,
        shortcut: String = "",
    ): PhraseInputError? {
        val trimmedText = text.trim()
        if (trimmedText.isEmpty()) return PhraseInputError.TEXT_REQUIRED
        if (trimmedText.toByteArray(Charsets.UTF_8).size > LocalQuickContentStore.MAXIMUM_TEXT_BYTES) {
            return PhraseInputError.TEXT_TOO_LONG
        }
        if (shortcut.trim().toByteArray(Charsets.UTF_8).size > LocalQuickContentStore.MAXIMUM_SHORTCUT_BYTES) {
            return PhraseInputError.SHORTCUT_TOO_LONG
        }
        if (phrases.any { it.text == trimmedText && it.id != editingId }) return PhraseInputError.DUPLICATE
        if (editingId == null && !canAdd) return PhraseInputError.LIMIT_REACHED
        return null
    }
}
