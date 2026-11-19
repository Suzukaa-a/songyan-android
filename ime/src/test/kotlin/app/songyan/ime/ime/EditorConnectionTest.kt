package app.songyan.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

/** The Delete swipe against a small in-memory editor: text, a selection, and the calls it received. */
class EditorSwipeSelectTest {
    private class FakeEditor(
        var text: String,
        var start: Int = text.length,
        var end: Int = text.length,
    ) {
        val calls = mutableListOf<String>()
        val connection: android.view.inputmethod.InputConnection =
            java.lang.reflect.Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(android.view.inputmethod.InputConnection::class.java),
            ) { _, method, args ->
                calls += method.name
                when (method.name) {
                    "getTextBeforeCursor" -> text.substring(maxOf(0, start - args[0] as Int), start)
                    "getSelectedText" -> if (start == end) null else text.substring(start, end)
                    "setSelection" -> {
                        start = args[0] as Int
                        end = args[1] as Int
                        true
                    }
                    "commitText" -> {
                        text = text.substring(0, start) + args[0] + text.substring(end)
                        start += (args[0] as CharSequence).length
                        end = start
                        true
                    }
                    else -> if (method.returnType == Boolean::class.javaPrimitiveType) true else null
                }
            } as android.view.inputmethod.InputConnection

        fun selected(): String = text.substring(start, end)
    }

    private val family = "👨‍👩‍👧"

    private fun begun(fake: FakeEditor): EditorConnection {
        val editor = EditorConnection { fake.connection }
        editor.selection.reset(fake.start, fake.end)
        assertTrue(editor.swipeSelectBegin())
        return editor
    }

    @Test
    fun swipeSelectExtendsByGraphemeClusters() {
        val fake = FakeEditor("a${family}b")
        val editor = begun(fake)
        editor.swipeSelectTo(1)
        assertEquals("b", fake.selected())
        editor.swipeSelectTo(2)
        assertEquals("${family}b", fake.selected())
        editor.swipeSelectTo(3)
        assertEquals("a${family}b", fake.selected())
        assertEquals(0, editor.selection.start)
        assertEquals(fake.text.length, editor.selection.end)
    }

    @Test
    fun swipeSelectShrinksAndCollapsesAtZero() {
        val fake = FakeEditor("abcd")
        val editor = begun(fake)
        editor.swipeSelectTo(3)
        editor.swipeSelectTo(2)
        assertEquals("cd", fake.selected())
        editor.swipeSelectTo(0)
        assertEquals(4, fake.start)
        assertEquals(4, fake.end)
    }

    @Test
    fun swipeSelectClampsAtTheStartOfTheText() {
        val fake = FakeEditor("abc")
        val editor = begun(fake)
        editor.swipeSelectTo(50)
        assertEquals("abc", fake.selected())
        val calls = fake.calls.size
        editor.swipeSelectTo(60)
        assertEquals("a clamped step needs no further call", calls, fake.calls.size)
    }

    @Test
    fun swipeSelectReportsHowManyCharactersItCanReach() {
        val fake = FakeEditor("a${family}b")
        val editor = EditorConnection { fake.connection }
        assertEquals(Int.MAX_VALUE, editor.swipeReach)
        editor.selection.reset(fake.start, fake.end)
        assertTrue(editor.swipeSelectBegin())
        assertEquals(3, editor.swipeReach)
        editor.swipeSelectEnd(delete = false)
        assertEquals(Int.MAX_VALUE, editor.swipeReach)
    }

    @Test
    fun swipeSelectEndDeletesExactlyTheSelection() {
        val fake = FakeEditor("abcdef")
        val editor = begun(fake)
        editor.swipeSelectTo(2)
        editor.swipeSelectEnd(delete = true)
        assertEquals("abcd", fake.text)
        assertEquals(4, fake.start)
        assertEquals(4, editor.selection.start)
        assertEquals(4, editor.selection.end)
        assertEquals("the swipe is over", -1, editor.swipeAnchor)
    }

    @Test
    fun swipeSelectEndWithoutDeleteRestoresTheCaret() {
        val fake = FakeEditor("abcdef")
        val editor = begun(fake)
        editor.swipeSelectTo(2)
        editor.swipeSelectEnd(delete = false)
        assertEquals("abcdef", fake.text)
        assertEquals(6, fake.start)
        assertEquals(6, fake.end)
        assertEquals(6, editor.selection.start)
    }

    @Test
    fun swipeSelectRefusesAKeyEventEditorAndAnUnknownSelection() {
        val fake = FakeEditor("abc")
        val unknown = EditorConnection { fake.connection }
        assertFalse(unknown.swipeSelectAvailable())
        unknown.selection.reset(3, 3)
        assertTrue(unknown.swipeSelectAvailable())
        unknown.selection.reset(1, 3)
        assertFalse("a range is not a bare caret", unknown.swipeSelectAvailable())

        val keyEvents = EditorConnection { fake.connection }
        val info = android.view.inputmethod.EditorInfo()
        info.inputType = android.text.InputType.TYPE_NULL
        keyEvents.startInput(info, restarting = false)
        keyEvents.selection.reset(3, 3)
        assertFalse(keyEvents.swipeSelectAvailable())
        assertFalse(keyEvents.swipeSelectBegin())
    }

    @Test
    fun swipeSelectBeginRefusesAFieldWithNothingBeforeTheCaret() {
        val fake = FakeEditor("abc", 0, 0)
        val editor = EditorConnection { fake.connection }
        editor.selection.reset(0, 0)
        assertFalse(editor.swipeSelectBegin())
        editor.swipeSelectTo(2)
        assertEquals(0, fake.start)
    }

    @Test
    fun anUnexpectedSelectionUpdateAbandonsTheSwipe() {
        val fake = FakeEditor("abcdef")
        val editor = begun(fake)
        editor.swipeSelectTo(2)
        // The user taps elsewhere in the field.
        editor.onUpdateSelection(4, 6, 1, 1, -1, -1)
        assertEquals(-1, editor.swipeAnchor)
        editor.swipeSelectEnd(delete = true)
        assertEquals("release after the user moved the caret deletes nothing", "abcdef", fake.text)
    }

    @Test
    fun aLateReportOfAnEarlierStepDoesNotAbandonTheSwipe() {
        val fake = FakeEditor("abcdef")
        val editor = begun(fake)
        editor.swipeSelectTo(1)
        editor.swipeSelectTo(3)
        // The host reports the first step only after the third was sent.
        editor.onUpdateSelection(6, 6, 5, 6, -1, -1)
        assertEquals(6, editor.swipeAnchor)
        assertEquals(3, editor.swipeUnits)
        editor.swipeSelectEnd(delete = true)
        assertEquals("abc", fake.text)
        // And the report of the second step after the release.
        editor.onUpdateSelection(5, 6, 4, 6, -1, -1)
        assertFalse(editor.selectionMovedExternally)
    }

    @Test
    fun aReportAtTheOldAnchorAfterTheFinalOneIsExternal() {
        val fake = FakeEditor("abcdef")
        val editor = begun(fake)
        editor.swipeSelectTo(2)
        editor.swipeSelectEnd(delete = true)
        // The host reports the swipe's final caret, then the user taps back where the swipe began.
        editor.onUpdateSelection(6, 6, 4, 4, -1, -1)
        assertFalse(editor.selectionMovedExternally)
        editor.onUpdateSelection(4, 4, 6, 6, -1, -1)
        assertTrue(editor.selectionMovedExternally)
        assertEquals(6, editor.selection.start)
    }

    @Test
    fun aKeyboardEditEndsTheEchoWindow() {
        val fake = FakeEditor("abcdef")
        val editor = begun(fake)
        editor.swipeSelectTo(2)
        editor.swipeSelectEnd(delete = true)
        editor.deleteBeforeCursor(1)
        editor.onUpdateSelection(4, 4, 6, 6, -1, -1)
        assertTrue(editor.selectionMovedExternally)
    }

    @Test
    fun aZeroUnitReleaseDoesNotReuseTheLastSwipesFinalCaret() {
        val fake = FakeEditor("abcdef")
        val editor = begun(fake)
        editor.swipeSelectTo(2)
        editor.swipeSelectEnd(delete = true)
        // The caret is elsewhere when the next swipe begins and is released without selecting.
        fake.start = 3
        fake.end = 3
        editor.selection.reset(3, 3)
        assertTrue(editor.swipeSelectBegin())
        editor.swipeSelectEnd(delete = true)
        editor.onUpdateSelection(3, 3, 4, 4, -1, -1)
        assertTrue("4 was the previous swipe's final caret, not this one's", editor.selectionMovedExternally)
    }

    @Test
    fun differentTextOfTheSameLengthIsNotDeleted() {
        val fake = FakeEditor("abcdef")
        val editor = begun(fake)
        editor.swipeSelectTo(2)
        fake.text = "abcdXY"
        editor.swipeSelectEnd(delete = true)
        assertEquals("abcdXY", fake.text)
        assertEquals(6, fake.start)
        assertEquals(6, fake.end)
    }

    @Test
    fun aChangedSelectionIsNotDeletedOnRelease() {
        val fake = FakeEditor("abcdef")
        val editor = begun(fake)
        editor.swipeSelectTo(2)
        // The host replaced the selection with something longer before the release.
        fake.start = 2
        fake.end = 6
        editor.swipeSelectEnd(delete = true)
        assertEquals("abcdef", fake.text)
        assertEquals("the caret goes back instead", 6, fake.start)
        assertEquals(6, fake.end)
    }
}
