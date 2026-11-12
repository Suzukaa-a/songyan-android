package app.songyan.ime.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolInputTest {
    @Test
    fun selectionWinsOverTextBeforeCursor() {
        val editor =
            EditorSnapshot(
                documentId = "doc",
                privacy = EditorPrivacy.NORMAL,
                selection = "selected",
                selectionRange = TextRange(2, 10),
                beforeCursor = "before",
                cursorIndex = 8,
            )
        val source = (ToolInput.acquire(editor, maxUnits = 100) as InputResult.Available).source
        assertEquals(TextScope.SELECTION, source.scope)
        assertEquals("selected", source.text)
        assertEquals(TextRange(2, 10), source.range)
        assertFalse(source.isTruncated)
    }

    @Test
    fun withoutSelectionTheTextBeforeTheCursorIsUsed() {
        val editor = EditorSnapshot("doc", EditorPrivacy.NORMAL, beforeCursor = "hello", cursorIndex = 5)
        val source = (ToolInput.acquire(editor, maxUnits = 100) as InputResult.Available).source
        assertEquals(TextScope.BEFORE_CURSOR, source.scope)
        assertEquals("hello", source.text)
        assertEquals(TextRange(0, 5), source.range)
        assertFalse(source.isTruncated)
        assertNull(source.fullText)
    }

    @Test
    fun selectionIsLimitedLikeBeforeCursorText() {
        val editor =
            EditorSnapshot(
                documentId = "doc",
                privacy = EditorPrivacy.NORMAL,
                selection = "abcdef",
                selectionRange = TextRange(0, 6),
                cursorIndex = 6,
            )
        val source = (ToolInput.acquire(editor, maxUnits = 3) as InputResult.Available).source
        assertEquals("def", source.text)
        assertTrue(source.isTruncated)
        assertEquals(TextRange(3, 6), source.range)
        assertEquals("abcdef", source.fullText)
    }

    @Test
    fun onlyTheTrailingPartOfBeforeCursorTextIsProcessed() {
        val editor = EditorSnapshot("doc", EditorPrivacy.NORMAL, beforeCursor = "0123456789", cursorIndex = 10)
        val source = (ToolInput.acquire(editor, maxUnits = 4) as InputResult.Available).source
        assertEquals("6789", source.text)
        assertTrue(source.isTruncated)
        assertEquals(TextRange(6, 10), source.range)
    }

    @Test
    fun truncationDoesNotSplitASurrogatePair() {
        // A + emoji (two code units) + B: a two-unit limit must take B, not half the emoji.
        val editor = EditorSnapshot("doc", EditorPrivacy.NORMAL, beforeCursor = "A\uD83D\uDE00B", cursorIndex = 4)
        val source = (ToolInput.acquire(editor, maxUnits = 2) as InputResult.Available).source
        assertEquals("B", source.text)
        assertTrue(source.isTruncated)
        assertEquals(TextRange(3, 4), source.range)
    }

    @Test
    fun aLimitThatWouldOnlyLeaveHalfAPairLeavesNoText() {
        val editor = EditorSnapshot("doc", EditorPrivacy.NORMAL, beforeCursor = "\uD83D\uDE00", cursorIndex = 2)
        val result = ToolInput.acquire(editor, maxUnits = 1)
        assertEquals(InputResult.Unavailable(UnavailableReason.NO_TEXT), result)
    }

    @Test
    fun passwordFieldsNeverHandOverText() {
        val editor =
            EditorSnapshot(
                documentId = "doc",
                privacy = EditorPrivacy.PASSWORD,
                selection = "secret",
                selectionRange = TextRange(0, 6),
                beforeCursor = "secret",
                cursorIndex = 6,
            )
        assertEquals(InputResult.Unavailable(UnavailableReason.SECURE_ENTRY), ToolInput.acquire(editor, maxUnits = 100))
    }

    @Test
    fun noPersonalizedLearningIsAnOrdinaryEditableField() {
        val editor =
            EditorSnapshot(
                documentId = "doc",
                privacy = EditorPrivacy.NO_PERSONALIZED_LEARNING,
                beforeCursor = "hello",
                cursorIndex = 5,
            )
        val source = (ToolInput.acquire(editor, maxUnits = 100) as InputResult.Available).source
        assertEquals("hello", source.text)
    }

    @Test
    fun editorsWithoutTextReportNoText() {
        assertEquals(
            InputResult.Unavailable(UnavailableReason.NO_TEXT),
            ToolInput.acquire(EditorSnapshot("doc", EditorPrivacy.NO_TEXT), maxUnits = 100),
        )
        assertEquals(
            InputResult.Unavailable(UnavailableReason.NO_TEXT),
            ToolInput.acquire(EditorSnapshot("doc", EditorPrivacy.NORMAL, beforeCursor = "", cursorIndex = 0), 100),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun aSelectionMustMatchItsDocumentRange() {
        val editor =
            EditorSnapshot(
                documentId = "doc",
                privacy = EditorPrivacy.NORMAL,
                selection = "abc",
                selectionRange = TextRange(0, 10),
                cursorIndex = 10,
            )
        ToolInput.acquire(editor, maxUnits = 100)
    }

    @Test(expected = IllegalArgumentException::class)
    fun theLimitMustBePositive() {
        ToolInput.acquire(
            EditorSnapshot("doc", EditorPrivacy.NORMAL, beforeCursor = "x", cursorIndex = 1),
            maxUnits = 0,
        )
    }
}
