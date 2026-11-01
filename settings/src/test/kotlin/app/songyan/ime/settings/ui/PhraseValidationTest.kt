package app.songyan.ime.settings.ui

import app.songyan.ime.content.CommonPhrase
import app.songyan.ime.content.LocalQuickContentStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhraseValidationTest {
    private fun phrase(
        id: String,
        text: String,
    ) = CommonPhrase(id = id, text = text)

    @Test
    fun blankTextIsRefused() {
        assertEquals(
            PhraseInputError.TEXT_REQUIRED,
            PhraseValidation.check("   ", null, emptyList(), canAdd = true),
        )
    }

    @Test
    fun textOverTheStoreLimitIsRefused() {
        val oversized = "x".repeat(LocalQuickContentStore.MAXIMUM_TEXT_BYTES + 1)
        assertEquals(
            PhraseInputError.TEXT_TOO_LONG,
            PhraseValidation.check(oversized, null, emptyList(), canAdd = true),
        )
    }

    @Test
    fun textAtTheLimitIsAccepted() {
        val exact = "x".repeat(LocalQuickContentStore.MAXIMUM_TEXT_BYTES)
        assertNull(PhraseValidation.check(exact, null, emptyList(), canAdd = true))
    }

    @Test
    fun duplicateTextIsRefusedButEditingItselfIsNot() {
        val existing = phrase("id-1", "收到")
        assertEquals(
            PhraseInputError.DUPLICATE,
            PhraseValidation.check("收到", null, listOf(existing), canAdd = true),
        )
        assertNull(PhraseValidation.check("收到", "id-1", listOf(existing), canAdd = true))
    }

    @Test
    fun addingPastTheBudgetIsRefused() {
        assertEquals(
            PhraseInputError.LIMIT_REACHED,
            PhraseValidation.check("new", null, emptyList(), canAdd = false),
        )
        // Editing an existing phrase never hits the budget.
        assertNull(PhraseValidation.check("new", "id-1", emptyList(), canAdd = false))
    }

    @Test
    fun surroundingWhitespaceIsIgnored() {
        assertNull(PhraseValidation.check("  收到  ", null, emptyList(), canAdd = true))
        assertEquals(
            PhraseInputError.DUPLICATE,
            PhraseValidation.check("  收到 ", null, listOf(phrase("id-1", "收到")), canAdd = true),
        )
    }

    @Test
    fun aShortcutOverTheStoreLimitIsRefusedAndOneAtTheLimitIsNot() {
        val tooLong = "d".repeat(LocalQuickContentStore.MAXIMUM_SHORTCUT_BYTES + 1)
        val exact = "d".repeat(LocalQuickContentStore.MAXIMUM_SHORTCUT_BYTES)
        assertEquals(
            PhraseInputError.SHORTCUT_TOO_LONG,
            PhraseValidation.check("收到", null, emptyList(), canAdd = true, shortcut = tooLong),
        )
        assertNull(PhraseValidation.check("收到", null, emptyList(), canAdd = true, shortcut = exact))
    }
}
