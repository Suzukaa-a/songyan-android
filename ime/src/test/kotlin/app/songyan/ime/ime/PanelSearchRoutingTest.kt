package app.songyan.ime.ime

import app.songyan.ime.keyboard.engine.InputLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelSearchRoutingTest {
    private val editor = mutableListOf<String>()
    private val sink = StringBuilder()
    private var searching = false

    private val search =
        object : PanelSearchSink {
            override fun append(text: String) {
                sink.append(text)
            }

            override fun backspace() {
                sink.setLength(maxOf(0, sink.length - 1))
            }

            override fun submit() {
                searching = false
            }
        }

    private val callbacks =
        KeyboardController
            .Callbacks(
                commit = { text, _ ->
                    editor += "commit:$text"
                    true
                },
                preedit = { text, _ -> editor += "preedit:$text" },
                delete = { editor += "delete" },
                moveCursor = { editor += "move:$it" },
                action = { editor += "action" },
                error = {},
                contextBeforeCursor = { "context" },
                deleteBefore = { editor += "deleteBefore:$it" },
            ).routedThrough { if (searching) search else null }

    @Test
    fun withoutASearchEveryCallbackReachesTheEditor() {
        assertTrue(callbacks.commit("a", false))
        callbacks.preedit("ni", InputLanguage.ZH_HANS)
        callbacks.delete()
        callbacks.moveCursor(-1)
        callbacks.action()
        callbacks.deleteBefore(2)
        assertEquals("context", callbacks.contextBeforeCursor(5))
        assertEquals(
            listOf("commit:a", "preedit:ni", "delete", "move:-1", "action", "deleteBefore:2"),
            editor,
        )
        assertEquals("", sink.toString())
    }

    @Test
    fun duringASearchTextDeletionAndReturnFeedTheQueryAndNothingReachesTheEditor() {
        searching = true
        assertTrue("a commit is reported as delivered", callbacks.commit("笑", false))
        callbacks.commit("脸", true)
        callbacks.preedit("xiao", InputLanguage.ZH_HANS)
        callbacks.delete()
        callbacks.moveCursor(1)
        callbacks.deleteBefore(3)
        assertNull("no context is read while searching", callbacks.contextBeforeCursor(5))
        assertEquals("笑", sink.toString())
        assertTrue("the editor was not touched: $editor", editor.isEmpty())
        callbacks.action()
        assertEquals("Return ends the search", false, searching)
        assertTrue(editor.isEmpty())
    }

    @Test
    fun afterTheSearchEndsTheEditorIsWrittenAgain() {
        searching = true
        callbacks.commit("x", false)
        searching = false
        callbacks.commit("y", false)
        assertEquals(listOf("commit:y"), editor)
    }
}
