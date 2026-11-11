package app.songyan.ime.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Selection reports an editor sends around a composition, replayed without a device. */
@RunWith(AndroidJUnit4::class)
class EditorCompositionReportTest {
    private val calls = mutableListOf<String>()
    private val connection: android.view.inputmethod.InputConnection =
        java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(android.view.inputmethod.InputConnection::class.java),
        ) { _, method, args ->
            calls += method.name + (args?.joinToString(",", "(", ")") ?: "()")
            when (method.name) {
                "getTextBeforeCursor" -> ""
                // What Chrome shows once its completion is back: the user's "e" sits in the old region.
                "getExtractedText" ->
                    android.view.inputmethod.ExtractedText().apply {
                        text = "ex"
                        startOffset = 0
                    }
                else -> if (method.returnType == Boolean::class.javaPrimitiveType) true else null
            }
        } as android.view.inputmethod.InputConnection

    @Test
    fun aCompositionTheEditorEndedItselfIsNotDeletedWhenTheCursorMoves() {
        // Chrome's address bar: "ex" composing, then Delete leaves the spelling "e". Chrome puts its
        // inline completion back, drops the composing region, and moves the cursor.
        val editor = EditorConnection { connection }
        editor.selection.reset(0, 0)
        editor.update("e", emptyList())
        assertEquals(false, editor.onUpdateSelection(0, 0, 1, 1, 0, 1))
        editor.update("ex", emptyList())
        assertEquals(false, editor.onUpdateSelection(1, 1, 2, 2, 0, 2))
        editor.update("e", emptyList())
        assertEquals(false, editor.onUpdateSelection(2, 2, 1, 1, 0, 1))
        assertEquals(false, editor.onUpdateSelection(1, 1, 1, 1, -1, -1))
        calls.clear()
        assertEquals(true, editor.onUpdateSelection(1, 1, 2, 2, -1, -1))
        assertEquals(listOf("finishComposingText()"), calls)
    }

    @Test
    fun aCursorMovedAwayFromAConfirmedCompositionStillRemovesIt() {
        val editor = EditorConnection { connection }
        editor.selection.reset(5, 5)
        editor.update("ni", emptyList())
        assertEquals(false, editor.onUpdateSelection(5, 5, 7, 7, 5, 7))
        calls.clear()
        // The user taps at the start of the field; the editor still marks the spelling.
        assertEquals(true, editor.onUpdateSelection(7, 7, 0, 0, 5, 7))
        assertEquals(true, calls.any { it.startsWith("setComposingText(,") })
    }
}
