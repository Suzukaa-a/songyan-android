package app.songyan.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class EditorConnectionTest {
    @Test
    fun plainTextDeletesOneCodePoint() {
        assertEquals(1, EditorConnection.trailingClusterCodePoints("abc"))
        assertEquals(1, EditorConnection.trailingClusterCodePoints(null))
        assertEquals(1, EditorConnection.trailingClusterCodePoints(""))
    }

    @Test
    fun multiCodePointEmojiIsOneCharacter() {
        // A single emoji is one code point, which the old call already deleted correctly.
        assertEquals(1, EditorConnection.trailingClusterCodePoints("ab\uD83D\uDC4D"))
        // A family emoji joined by zero-width joiners is one cluster of five code points.
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"
        assertEquals(5, EditorConnection.trailingClusterCodePoints("x" + family))
        // A combining mark stays attached to its base letter.
        assertEquals(2, EditorConnection.trailingClusterCodePoints("e\u0301"))
    }

    @Test
    fun aClusterThatFillsTheWholeReadWindowFailsClosed() {
        val oneCluster = "a" + "\u0301".repeat(EditorConnection.GRAPHEME_WINDOW - 1)
        assertEquals(EditorConnection.GRAPHEME_WINDOW, oneCluster.length)
        // The cluster reaches the start of a possibly truncated read: never guess its extent.
        assertEquals(1, EditorConnection.trailingClusterCodePoints(oneCluster))
        // The same cluster with known context before it is measured in full.
        assertEquals(2, EditorConnection.trailingClusterCodePoints("e\u0301"))
    }
}

/** The round trips a Backspace makes: a known caret needs no selected-text read, an unknown one still does. */
class EditorConnectionDeleteTest {
    private class Recorder(
        var selected: CharSequence? = null,
        var before: CharSequence = "abc",
    ) {
        val calls = mutableListOf<String>()
        val deleted = mutableListOf<Int>()
        val connection: android.view.inputmethod.InputConnection =
            java.lang.reflect.Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(android.view.inputmethod.InputConnection::class.java),
            ) { _, method, args ->
                calls += method.name
                if (method.name == "deleteSurroundingText") deleted += args[0] as Int
                when (method.name) {
                    "getSelectedText" -> selected
                    "getTextBeforeCursor" -> before
                    "commitText", "deleteSurroundingTextInCodePoints", "deleteSurroundingText" -> true
                    else -> if (method.returnType == Boolean::class.javaPrimitiveType) true else null
                }
            } as android.view.inputmethod.InputConnection
    }

    @Test
    fun aKnownCaretDeletesWithoutReadingTheSelection() {
        val recorder = Recorder()
        val editor = EditorConnection { recorder.connection }
        editor.selection.reset(3, 3)
        editor.delete()
        assertEquals(listOf("getTextBeforeCursor", "deleteSurroundingText"), recorder.calls)
    }

    @Test
    fun anEditorThatDoesNotReportItsSelectionIsStillAsked() {
        val recorder = Recorder(selected = "bc")
        val editor = EditorConnection { recorder.connection }
        editor.selection.forget()
        editor.delete()
        assertEquals(listOf("getSelectedText", "commitText"), recorder.calls)
    }

    @Test
    fun aKnownRangeSelectionIsReplacedByNothing() {
        val recorder = Recorder(selected = "bc")
        val editor = EditorConnection { recorder.connection }
        editor.selection.reset(1, 3)
        editor.delete()
        assertEquals(listOf("getSelectedText", "commitText"), recorder.calls)
    }

    @Test
    fun aClusterIsDeletedInUtf16UnitsThatEveryEditorImplements() {
        // A skin-toned thumbs-up: two surrogate pairs, one user-perceived character.
        val recorder = Recorder(before = "a\uD83D\uDC4D\uD83C\uDFFD")
        val editor = EditorConnection { recorder.connection }
        editor.selection.reset(5, 5)
        editor.delete()
        assertEquals(listOf(4), recorder.deleted)
        assertEquals(1, editor.selection.start)
    }
}
