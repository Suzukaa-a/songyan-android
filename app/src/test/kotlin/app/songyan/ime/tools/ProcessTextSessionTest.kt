package app.songyan.ime.tools

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * The relay's bookkeeping: an answer is tied to the request that asked for it, both arrival orders
 * work, and a field that cannot be confirmed never receives a write.
 */
class ProcessTextSessionTest {
    private val session = ProcessTextSession { UUID.randomUUID().toString() }

    @Test
    fun anAnswerThatArrivesBeforeTheEditorComesBackIsKept() {
        val token = requireNotNull(session.start(request()))

        assertTrue(session.onResult(token, "HELLO WORLD"))
        assertNull("no editor is bound yet", session.onEditor(null))
        assertNull(session.onEditor(null))
        assertNull("the same editor came back", session.onEditor(editor()))
        assertEquals("HELLO WORLD", session.pendingResult()?.text)
    }

    @Test
    fun anUnidentifiedBindingDuringTheReturnKeepsTheAnswerWithoutAuthorizingAWrite() {
        val token = requireNotNull(session.start(request()))
        session.onResult(token, "HELLO WORLD")
        val transient = editor(packageName = "", fieldId = 0)
        session.onEditor(transient)
        assertEquals("HELLO WORLD", session.pendingResult()?.text)
        assertFalse(request().sameEditorAs(transient))
        session.onEditor(editor())
        assertEquals("HELLO WORLD", session.take()?.text)
    }

    @Test
    fun anAnswerThatArrivesAfterTheEditorCameBackIsKept() {
        val token = requireNotNull(session.start(request()))
        assertNull(session.onEditor(editor()))

        assertTrue(session.onResult(token, "HELLO WORLD"))
        assertEquals("HELLO WORLD", session.pendingResult()?.text)
    }

    @Test
    fun aLateAnswerOfAnOldRequestIsIgnored() {
        val token = requireNotNull(session.start(request()))
        session.dismiss()
        val newer = requireNotNull(session.start(request(text = "new text")))

        assertFalse("an answer for a dismissed request must not count", session.onResult(token, "OLD"))
        assertNull(session.pendingResult())
        assertTrue(session.onResult(newer, "NEW"))
        assertEquals("NEW", session.pendingResult()?.text)
    }

    @Test
    fun aSecondRequestIsRefusedWhileOneIsOpen() {
        assertNotNull(session.start(request()))

        assertNull(session.start(request()))
        session.dismiss()
        assertNotNull("a dismissed request must not block the next one", session.start(request()))
    }

    @Test
    fun anEmptyAnswerIsAnAnswer() {
        val token = requireNotNull(session.start(request()))

        assertTrue(session.onResult(token, ""))
        assertEquals("", session.pendingResult()?.text)
        assertEquals("", session.take()?.text)
    }

    @Test
    fun aNullAnswerIsACancel() {
        val token = requireNotNull(session.start(request()))

        assertTrue(session.onResult(token, null))
        assertNull(session.pendingResult())
        assertNull(session.onEditor(editor()))
    }

    @Test
    fun anAnswerForAnotherFieldIsRetiredWithoutCopying() {
        val token = requireNotNull(session.start(request()))
        assertTrue(session.onResult(token, "HELLO WORLD"))

        assertNull(
            "a changed field must never produce an automatic clipboard write",
            session.onEditor(editor(fieldId = 202)),
        )
        assertNull(session.pendingResult())
    }

    @Test
    fun anAnswerForAnotherAppIsRetiredWithoutCopying() {
        val token = requireNotNull(session.start(request()))
        assertTrue(session.onResult(token, "HELLO WORLD"))

        assertNull(
            "a changed app must never produce an automatic clipboard write",
            session.onEditor(editor(packageName = "app.three", fieldId = 0)),
        )
        assertNull(session.pendingResult())
    }

    @Test
    fun aBindingBeforeTheAnswerIsIgnored() {
        val token = requireNotNull(session.start(request()))

        assertNull(
            "the opened app's own editor is not a target",
            session.onEditor(editor(packageName = TARGET_PACKAGE, fieldId = 303)),
        )
        assertNull(session.onEditor(null))
        assertTrue(session.onResult(token, "HELLO WORLD"))
        assertEquals("HELLO WORLD", session.pendingResult()?.text)
    }

    @Test
    fun aFieldIdOfZeroOrLessCannotBeConfirmed() {
        val zero = requireNotNull(session.start(request(fieldId = 0)))
        assertTrue(session.onResult(zero, "HELLO WORLD"))
        assertNull(session.onEditor(editor(fieldId = 0)))
        assertEquals("HELLO WORLD", session.take()?.text)

        val negative = requireNotNull(session.start(request(fieldId = -1)))
        assertTrue(session.onResult(negative, "HELLO WORLD"))
        assertNull(session.onEditor(editor(fieldId = -1)))
        assertEquals("HELLO WORLD", session.take()?.text)
    }

    @Test
    fun takingTheAnswerEndsTheRequest() {
        val token = requireNotNull(session.start(request()))
        assertTrue(session.onResult(token, "HELLO WORLD"))

        assertEquals("HELLO WORLD", session.take()?.text)
        assertNull(session.pendingResult())
        assertFalse("a taken answer cannot be applied twice", session.onResult(token, "AGAIN"))
    }

    @Test
    fun aResultFromBeforeAProcessRestartDoesNotAnswerANewRequest() {
        val older = ProcessTextSession { UUID.randomUUID().toString() }
        val oldToken = requireNotNull(older.start(request()))
        // The process was rebuilt: a new session and a new request, with a token of its own.
        val newer = ProcessTextSession { UUID.randomUUID().toString() }
        val newToken = requireNotNull(newer.start(request()))
        assertNotEquals(oldToken, newToken)

        assertFalse("an answer from before the restart must not count", newer.onResult(oldToken, "OLD"))
        assertNull(newer.pendingResult())
        assertTrue(newer.onResult(newToken, "NEW"))
        assertEquals("NEW", newer.pendingResult()?.text)
    }

    @Test
    fun anOldCancelDoesNotClearANewRequest() {
        val older = ProcessTextSession { UUID.randomUUID().toString() }
        val oldToken = requireNotNull(older.start(request()))
        val newer = ProcessTextSession { UUID.randomUUID().toString() }
        val newToken = requireNotNull(newer.start(request()))

        assertFalse("an old cancel must not clear the new request", newer.onResult(oldToken, null))
        assertTrue(newer.onResult(newToken, "NEW"))
        assertEquals("NEW", newer.pendingResult()?.text)
    }

    @Test
    fun theSameEditorIsTheSamePackageAndField() {
        assertTrue(request().sameEditorAs(editor()))
        assertFalse(request().sameEditorAs(editor(fieldId = 202)))
        assertFalse(request().sameEditorAs(editor(packageName = "app.three")))
        assertFalse(request().sameEditorAs(null))
        assertFalse(request(fieldId = 0).sameEditorAs(editor(fieldId = 0)))
        assertFalse(request(fieldId = -1).sameEditorAs(editor(fieldId = -1)))
    }

    private fun request(
        text: String = "hello world",
        packageName: String = "app.one",
        fieldId: Int = 101,
    ) = ProcessTextRequest(text, packageName, fieldId)

    private fun editor(
        packageName: String = "app.one",
        fieldId: Int = 101,
    ) = EditorInfo().apply {
        this.packageName = packageName
        this.fieldId = fieldId
    }

    private companion object {
        const val TARGET_PACKAGE = "app.two"
    }
}
