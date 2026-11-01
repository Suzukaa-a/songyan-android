package app.songyan.ime.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The automatic capital derived from the keyboard's own view of the text must equal what the editor
 * itself answers, step by step, while asking the editor far less often.
 */
@RunWith(AndroidJUnit4::class)
class AutomaticCapsTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val sentences = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES

    private class Counting(
        target: InputConnection,
    ) : InputConnectionWrapper(target, true) {
        var capsQueries = 0
        var reads = 0

        override fun getCursorCapsMode(reqModes: Int): Int {
            capsQueries++
            return super.getCursorCapsMode(reqModes)
        }

        override fun getTextBeforeCursor(
            n: Int,
            flags: Int,
        ): CharSequence? {
            reads++
            return super.getTextBeforeCursor(n, flags)
        }
    }

    private inner class Rig(
        initial: String,
        private val inputType: Int = sentences,
    ) {
        lateinit var field: EditText
        lateinit var counting: Counting
        lateinit var raw: InputConnection
        val editor = EditorConnection { counting }

        init {
            instrumentation.runOnMainSync {
                field = EditText(instrumentation.targetContext)
                field.inputType = inputType
                field.setText(initial)
                field.setSelection(initial.length)
                val info = EditorInfo()
                raw = field.onCreateInputConnection(info)!!
                counting = Counting(raw)
                editor.startInput(info, false)
            }
        }

        fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

        /** What the editor says, and what the keyboard derives; they must agree. */
        fun check(note: String) {
            onMain {
                val truth = raw.getCursorCapsMode(inputType)
                assertEquals(note, truth, editor.automaticCapsMode(inputType))
            }
        }

        fun commit(text: String) = onMain { editor.update("", listOf(text)) }

        fun preedit(text: String) = onMain { editor.update(text, emptyList()) }
    }

    @Test
    fun typingFollowsTheEditorWithoutAskingItForCapitals() {
        val rig = Rig("")
        rig.check("empty field")
        rig.counting.capsQueries = 0
        rig.counting.reads = 0
        val steps = listOf("h", "i", ".", " ", "a", "b", " ", "?", " ", "\"", "x", "!", "\n", " ", "“")
        for ((n, step) in steps.withIndex()) {
            rig.commit(step)
            rig.check("after step $n '$step'")
        }
        assertEquals("the editor is never asked for the capital", 0, rig.counting.capsQueries)
        assertEquals("the text is read once, not per key", 0, rig.counting.reads)
    }

    @Test
    fun aCompositionAndItsCommitAreFollowed() {
        val rig = Rig("Done. ")
        rig.check("sentence end")
        rig.preedit("ni")
        rig.check("composing")
        rig.preedit("nih")
        rig.check("composing longer")
        rig.commit("w")
        rig.check("committed")
        rig.onMain { rig.editor.abandon() }
        rig.check("abandoned")
    }

    @Test
    fun deleteAndReturnAreFollowed() {
        val rig = Rig("Hello. W")
        rig.check("start")
        rig.onMain { rig.editor.delete() }
        rig.check("after delete of W")
        rig.onMain { rig.editor.delete() }
        rig.check("after delete of the space")
        rig.onMain { rig.editor.sendReturn(true) }
        rig.check("after newline")
    }

    @Test
    fun aCursorTheUserMovedIsReadAgain() {
        val rig = Rig("One. two three")
        rig.check("at the end")
        rig.onMain {
            rig.field.setSelection(5)
            rig.editor.onUpdateSelection(14, 14, 5, 5, -1, -1)
        }
        rig.check("after a tap before 'two'")
        rig.onMain {
            rig.field.setSelection(3)
            rig.editor.onUpdateSelection(5, 5, 3, 3, -1, -1)
        }
        rig.check("after a tap after 'One'")
    }

    @Test
    fun aTailTooShortToDecideFallsBackToTheEditor() {
        val spaces = " ".repeat(EditorConnection.CAPS_WINDOW * 2)
        val rig = Rig("Hi.$spaces")
        rig.check("a sentence end hidden behind the window")
        rig.commit(" ")
        rig.check("still hidden after typing a space")
        val notEnded = Rig("Hi$spaces")
        notEnded.check("no sentence end behind the window")
    }

    @Test
    fun aFieldThatWantsNoCapitalsIsNeverAsked() {
        val rig = Rig("Hi. ", inputType = InputType.TYPE_CLASS_TEXT)
        rig.onMain {
            assertEquals(0, rig.editor.automaticCapsMode(InputType.TYPE_CLASS_TEXT))
        }
        assertEquals(0, rig.counting.capsQueries)
        assertEquals(0, rig.counting.reads)
    }

    @Test
    fun wordAndCharacterModesAgreeToo() {
        for (type in listOf(InputType.TYPE_TEXT_FLAG_CAP_WORDS, InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)) {
            val rig = Rig("a b", inputType = InputType.TYPE_CLASS_TEXT or type)
            rig.check("end of 'a b' in mode $type")
            rig.commit(" ")
            rig.check("after a space in mode $type")
            rig.commit("c")
            rig.check("after a letter in mode $type")
        }
    }
}
