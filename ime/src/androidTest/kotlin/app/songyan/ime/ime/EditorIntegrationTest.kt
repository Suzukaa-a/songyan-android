package app.songyan.ime.ime

import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorIntegrationTest {
    @Test fun editorPoliciesRespectPrivacyAndActionFlags() {
        val info =
            EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION
            }
        val password = EditorPolicy.from(info)
        assertTrue(password.privateInput)
        assertTrue(password.ascii)
        assertEquals(EditorInfo.IME_ACTION_NONE, password.action)
        info.inputType = InputType.TYPE_CLASS_TEXT
        info.imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_SEARCH
        val privateText = EditorPolicy.from(info)
        assertTrue(privateText.privateInput)
        assertFalse(privateText.ascii)
        assertEquals(EditorInfo.IME_ACTION_SEARCH, privateText.action)
        info.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        assertTrue(EditorPolicy.from(info).numeric)
        info.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        assertTrue(EditorPolicy.from(info).ascii)
    }

    /** The platform's own editor is the contract: let TextView fill EditorInfo and follow it. */
    private fun editorInfo(configure: (android.widget.EditText) -> Unit): Pair<android.widget.EditText, EditorInfo> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var info: EditorInfo
        lateinit var field: android.widget.EditText
        instrumentation.runOnMainSync {
            field = android.widget.EditText(instrumentation.targetContext)
            configure(field)
            info = EditorInfo()
            field.onCreateInputConnection(info)
        }
        return field to info
    }

    @Test fun aMultiLineFieldKeepsTheReturnKey() {
        val (_, info) =
            editorInfo { it.setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE) }
        // TextView forces IME_FLAG_NO_ENTER_ACTION on a multi-line field, so Enter must stay a line
        // break even though the app requested no action at all.
        assertEquals(EditorInfo.IME_FLAG_NO_ENTER_ACTION, info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION)
        val policy = EditorPolicy.from(info)
        assertEquals(EditorInfo.IME_ACTION_NONE, policy.action)
        assertTrue(policy.multiline)
        assertTrue(policy.returnsNewline)
    }

    @Test fun aSingleLineFieldResolvesItsOwnAction() {
        val (_, info) = editorInfo { it.setInputType(InputType.TYPE_CLASS_TEXT) }
        // TextView itself supplies DONE (or NEXT when focus can advance) for a field that named no
        // action, so the keyboard must never insert a raw newline into it.
        val policy = EditorPolicy.from(info)
        assertTrue(
            "action " + policy.action,
            policy.action == EditorInfo.IME_ACTION_DONE || policy.action == EditorInfo.IME_ACTION_NEXT,
        )
        assertFalse(policy.multiline)
        assertFalse(policy.returnsNewline)
    }

    @Test fun anEditorOwnedActionLabelBecomesTheEnterKey() {
        val (_, info) =
            editorInfo {
                it.setInputType(InputType.TYPE_CLASS_TEXT)
                it.setImeActionLabel("发表", 1)
            }
        assertEquals("发表", info.actionLabel.toString())
        assertEquals(1, info.actionId)
        val policy = EditorPolicy.from(info)
        assertEquals(EditorPolicy.ACTION_CUSTOM_LABEL, policy.action)
        assertEquals("发表", policy.customActionLabel)
    }

    @Test fun aDateTimeFieldNarrowsItsSeparators() {
        val (_, dateInfo) =
            editorInfo { it.setInputType(InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE) }
        assertEquals(DateTimeFlags(date = true), EditorPolicy.from(dateInfo).dateTimeFlags)
        val (_, timeInfo) =
            editorInfo { it.setInputType(InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME) }
        assertEquals(DateTimeFlags(time = true), EditorPolicy.from(timeInfo).dateTimeFlags)
        // A field that names no variation takes both and keeps the mixed pad.
        val (_, anyInfo) = editorInfo { it.setInputType(InputType.TYPE_CLASS_DATETIME) }
        assertEquals(DateTimeFlags(date = true, time = true), EditorPolicy.from(anyInfo).dateTimeFlags)
    }

    @Test fun returnIsALineBreakForAMultiLineFieldAndAKeyEventElsewhere() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val editable = SpannableStringBuilder()
            val connection =
                object : BaseInputConnection(View(instrumentation.targetContext), true) {
                    override fun getEditable(): Editable = editable
                }
            val editor = EditorConnection { connection }
            // A multi-line field is guaranteed its line break, ordered with the commits before it.
            assertTrue(editor.sendReturn(newlineAsText = true))
            assertEquals("\n", editable.toString())
            // The key-event delivery writes no text itself. BaseInputConnection reports false after
            // dispatching the event, so treating that as "not handled" would insert it twice.
            val keyed = SpannableStringBuilder()
            val keyedConnection =
                object : BaseInputConnection(View(instrumentation.targetContext), true) {
                    override fun getEditable(): Editable = keyed
                }
            assertTrue(EditorConnection { keyedConnection }.sendReturn(newlineAsText = false))
            assertEquals("", keyed.toString())
        }
    }

    @Test fun composingIsReplacedByEngineCommitAndDeletionUsesCodePoints() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val editable = SpannableStringBuilder()
            Selection.setSelection(editable, 0)
            val connection =
                object : BaseInputConnection(View(instrumentation.targetContext), true) {
                    override fun getEditable(): Editable = editable
                }
            val editor = EditorConnection { connection }
            editor.update("ni", emptyList())
            assertTrue(editor.composing)
            assertEquals("ni", editable.toString())
            assertTrue(editor.update("", listOf("你")))
            assertFalse(editor.composing)
            assertEquals("你", editable.toString())
            editor.update("", listOf("😀"))
            editor.delete()
            assertEquals("你", editable.toString())
            editor.update("hao", emptyList())
            editor.abandon()
            // An abandoned spelling is removed, never finished into the text.
            assertEquals(-1, BaseInputConnection.getComposingSpanStart(editable))
            assertEquals("你", editable.toString())
        }
    }
}
