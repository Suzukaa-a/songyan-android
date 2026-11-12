package app.songyan.ime.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.ime.tools.ToolUndoResult
import app.songyan.ime.ime.tools.ToolWriteResult
import app.songyan.ime.tools.CapabilityTable
import app.songyan.ime.tools.EditorPrivacy
import app.songyan.ime.tools.TextScope
import app.songyan.ime.tools.ToolCapability
import app.songyan.ime.tools.ToolDescriptor
import app.songyan.ime.tools.ToolFailure
import app.songyan.ime.tools.ToolLimits
import app.songyan.ime.tools.ToolPhase
import app.songyan.ime.tools.ToolRun
import app.songyan.ime.tools.ToolSession
import app.songyan.ime.tools.UnavailableReason
import app.songyan.ime.tools.WriteBlockReason
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The tool gateway over real platform editors: a real [EditText] with the platform's own
 * [InputConnection], the same [EditorConnection] the input method service uses, and (for one case)
 * the shipped librime engine, never a stub. Selection reports are fed the way the framework sends
 * them.
 */
@RunWith(AndroidJUnit4::class)
class EditorToolGatewayDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val tool = ToolDescriptor("test", "Test", setOf(ToolCapability.TEXT))
    private val capabilities = CapabilityTable(setOf(ToolCapability.TEXT))
    private var now = 1_000L

    private lateinit var activeConnection: InputConnection
    private val editor = EditorConnection { activeConnection }
    private var session = ToolSession("gateway-test")
    private var limits = ToolLimits(maxInputUnits = 64, timeoutMillis = 5_000, undoWindowMillis = null, maxResults = 3)

    private class Field(
        val edit: EditText,
        val info: EditorInfo,
        val connection: InputConnection,
    ) {
        val text: String get() = edit.text.toString()
    }

    private fun field(
        text: String,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        imeOptions: Int = 0,
        selection: Pair<Int, Int>? = null,
    ): Field {
        lateinit var result: Field
        instrumentation.runOnMainSync {
            val edit = EditText(context)
            edit.setInputType(inputType)
            edit.imeOptions = imeOptions
            edit.setText(text)
            if (selection !=
                null
            ) {
                edit.setSelection(selection.first, selection.second)
            } else {
                edit.setSelection(edit.text.length)
            }
            val info = EditorInfo()
            info.inputType = inputType
            info.packageName = context.packageName
            info.fieldId = 1
            val connection = requireNotNull(edit.onCreateInputConnection(info)) { "no input connection" }
            info.imeOptions = imeOptions
            info.initialSelStart = edit.selectionStart
            info.initialSelEnd = edit.selectionEnd
            result = Field(edit, info, connection)
        }
        return result
    }

    private fun activate(target: Field) {
        activeConnection = target.connection
        editor.startInput(target.info, false)
        gateway().startInput()
    }

    private fun gateway(): EditorToolGateway = EditorToolGateway(editor, limits, session, clock = { now })

    private fun type(
        target: Field,
        text: String,
    ) {
        instrumentation.runOnMainSync {
            val before = target.edit.text.length
            editor.update("", listOf(text))
            editor.onUpdateSelection(before, before, before + text.length, before + text.length, -1, -1)
        }
    }

    private fun begin(
        target: Field,
        result: String? = null,
    ): Pair<ToolRun, EditorToolGateway> {
        activate(target)
        val gateway = gateway()
        val run = gateway.beginRun(tool, capabilities, target.info)
        if (result != null) {
            assertTrue("the run must be able to take a result: " + run.failure, run.complete(run.runId, listOf(result)))
            assertEquals(ToolPhase.PREVIEWING, run.phase)
        }
        return run to gateway
    }

    @Test
    fun aPasswordFieldIsUnavailableAndNeverRead() {
        val target = field("secret", inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        activate(target)
        val gateway = gateway()
        assertEquals(EditorPrivacy.PASSWORD, gateway.snapshot(target.info).privacy)
        val run = gateway.beginRun(tool, capabilities, target.info)
        val failure = run.failure
        assertTrue("password must be refused: " + failure, failure is ToolFailure.Unavailable)
        assertEquals(UnavailableReason.SECURE_ENTRY, (failure as ToolFailure.Unavailable).reason)
        assertEquals("secret", target.text)
    }

    @Test
    fun aNoPersonalizedLearningFieldIsUsable() {
        val target = field("hello", imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        activate(target)
        assertEquals(EditorPrivacy.NO_PERSONALIZED_LEARNING, gateway().snapshot(target.info).privacy)
        val (run, gateway) = begin(target, "HI")
        assertEquals(TextScope.BEFORE_CURSOR, run.source?.scope)
        assertEquals(ToolWriteResult.Applied, gateway.replace(run, target.info))
        assertEquals("HI", target.text)
        assertEquals(ToolPhase.WROTE, run.phase)
    }

    @Test
    fun theSelectionWinsAndReplacesWithADifferentLength() {
        val target = field("hello world", selection = 6 to 11)
        val (run, gateway) = begin(target, "WIDE WORLD")
        assertEquals(TextScope.SELECTION, run.source?.scope)
        assertEquals("world", run.source?.text)
        assertEquals(ToolWriteResult.Applied, gateway.replace(run, target.info))
        assertEquals("hello WIDE WORLD", target.text)
    }

    @Test
    fun theTextBeforeTheCursorIsUsedWithoutASelection() {
        val target = field("alpha beta")
        val (run, gateway) = begin(target, "BETA")
        assertEquals(TextScope.BEFORE_CURSOR, run.source?.scope)
        assertEquals(ToolWriteResult.Applied, gateway.replace(run, target.info))
        assertEquals("BETA", target.text)
    }

    @Test
    fun aTruncatedSourceReplacesOnlyItsTail() {
        limits = ToolLimits(maxInputUnits = 5, timeoutMillis = 5_000, undoWindowMillis = null, maxResults = 3)
        val target = field("0123456789abcde")
        val (run, gateway) = begin(target, "X")
        assertTrue("the source must be truncated", run.source?.isTruncated == true)
        assertEquals("abcde", run.source?.text)
        assertEquals(ToolWriteResult.Applied, gateway.replace(run, target.info))
        assertEquals("0123456789X", target.text)
    }

    @Test
    fun aTruncatedSelectionIsNeverReplaced() {
        limits = ToolLimits(maxInputUnits = 5, timeoutMillis = 5_000, undoWindowMillis = null, maxResults = 3)
        // Readable (fits the bounded read window) but longer than the processed-input limit.
        val target = field("012345", selection = 0 to 6)
        val (run, gateway) = begin(target, "X")
        assertEquals(TextScope.SELECTION, run.source?.scope)
        assertTrue("the selection must be truncated", run.source?.isTruncated == true)
        assertEquals(
            ToolWriteResult.Blocked(WriteBlockReason.UNSUPPORTED_PARTIAL_REPLACEMENT),
            gateway.replace(run, target.info),
        )
        assertEquals("012345", target.text)
        assertEquals(ToolPhase.PREVIEWING, run.phase)
    }

    @Test
    fun aChangedSourceIsInsertedInsteadOfOverwritten() {
        val target = field("hello")
        val (run, gateway) = begin(target, "HI")
        // The user keeps typing between the preview and the write.
        type(target, "!")
        assertEquals("hello!", target.text)
        assertEquals(ToolWriteResult.Applied, gateway.replace(run, target.info))
        assertTrue("the user's text survives: " + target.text, target.text.startsWith("hello!"))
        assertTrue(target.text.endsWith("HI"))
    }

    @Test
    fun switchingFieldsIsNeverWrittenInto() {
        val first = field("first")
        val second = field("second")
        val (run, gateway) = begin(first, "X")
        activate(second)
        assertTrue(gateway.replace(run, second.info) is ToolWriteResult.Blocked)
        assertEquals("first", first.text)
        assertEquals("second", second.text)
        assertEquals(ToolPhase.PREVIEWING, run.phase)
    }

    @Test
    fun aSelectionMovedAfterThePreviewIsNotOverwritten() {
        val target = field("hello world", selection = 6 to 11)
        val (run, gateway) = begin(target, "X")
        // The framework reports the caret moved to the end of the field by the user.
        instrumentation.runOnMainSync {
            target.edit.setSelection(target.edit.text.length)
            editor.onUpdateSelection(6, 11, target.edit.text.length, target.edit.text.length, -1, -1)
        }
        // The source no longer matches the run, so this is not a replace; the model refuses the
        // insert fallback while the selection is live, and nothing is written.
        assertEquals(
            ToolWriteResult.Blocked(WriteBlockReason.UNSUPPORTED_INSERTION),
            gateway.replace(run, target.info),
        )
        assertEquals("hello world", target.text)
    }

    @Test
    fun insertWithALiveSelectionIsRefusedAndLeavesItAlone() {
        val target = field("hello world", selection = 6 to 11)
        val (run, gateway) = begin(target, "X")
        assertEquals(
            ToolWriteResult.Blocked(WriteBlockReason.UNSUPPORTED_INSERTION),
            gateway.insert(run, target.info),
        )
        assertEquals("hello world", target.text)
        assertEquals(ToolPhase.PREVIEWING, run.phase)
    }

    @Test
    fun undoRestoresTheOriginalAtADifferentLength() {
        val target = field("hello")
        val (run, gateway) = begin(target, "HI THERE")
        assertEquals(ToolWriteResult.Applied, gateway.replace(run, target.info))
        assertEquals("HI THERE", target.text)
        assertEquals(ToolUndoResult.Applied, gateway.undo(run, target.info))
        assertEquals("hello", target.text)
        assertEquals(ToolPhase.IDLE, run.phase)
    }

    @Test
    fun undoAfterFurtherTypingIsRefused() {
        val target = field("hello")
        val (run, gateway) = begin(target, "HI")
        assertEquals(ToolWriteResult.Applied, gateway.replace(run, target.info))
        type(target, "!")
        assertEquals("HI!", target.text)
        assertTrue(gateway.undo(run, target.info) is ToolUndoResult.Blocked)
        assertEquals("HI!", target.text)
        assertEquals(ToolPhase.WROTE, run.phase)
    }

    @Test
    fun anEmptyResultCanBeUndoneBackToTheOriginalText() {
        val target = field("hello")
        val (run, gateway) = begin(target, "")
        assertEquals(ToolWriteResult.Applied, gateway.replace(run, target.info))
        assertEquals("", target.text)
        assertEquals(ToolUndoResult.Applied, gateway.undo(run, target.info))
        assertEquals("hello", target.text)
    }

    @Test
    fun aRebuiltGatewayCannotWriteARunFromThePreviousOne() {
        val first = field("same")
        val (run, _) = begin(first, "X")
        // A new tool session (new gateway instance) over the same text in another field.
        val second = field("same")
        activate(second)
        val rebuilt = gateway()
        assertEquals(ToolWriteResult.Blocked(WriteBlockReason.DOCUMENT_CHANGED), rebuilt.replace(run, second.info))
        assertEquals("same", first.text)
        assertEquals("same", second.text)
    }

    @Test
    fun aSelectionLongerThanTheReadWindowFailsClosedWithoutPullingIt() {
        limits = ToolLimits(maxInputUnits = 5, timeoutMillis = 5_000, undoWindowMillis = null, maxResults = 3)
        val target = field("0123456789abcde", selection = 0 to 15)
        activate(target)
        assertEquals(EditorPrivacy.NO_TEXT, gateway().snapshot(target.info).privacy)
        val run = gateway().beginRun(tool, capabilities, target.info)
        val failure = run.failure
        assertTrue(failure is ToolFailure.Unavailable)
        assertEquals(UnavailableReason.NO_TEXT, (failure as ToolFailure.Unavailable).reason)
        assertEquals("0123456789abcde", target.text)
    }

    @Test
    fun realRimeEngineTextIsReadAndReplaced() {
        val backend = engine.backend()
        try {
            val committed =
                runBlocking {
                    val schema =
                        backend.availableSchemas.firstOrNull { it.id == "songyan_quanpin" }?.id
                            ?: backend.availableSchemas.first().id
                    backend.selectSchema(schema)
                    for (key in "nihao") backend.processKey(key.toString())
                    backend.selectCandidate(0).committed
                }
            assertNotEquals("the real engine must commit text", "", committed)
            val target = field("")
            activate(target)
            type(target, committed)
            assertEquals(committed, target.text)
            // The framework reports the caret after typing; a new session's EditorInfo starts there.
            target.info.initialSelStart = target.edit.text.length
            target.info.initialSelEnd = target.edit.text.length
            val (run, gateway) = begin(target, "ENGINE")
            assertEquals(TextScope.BEFORE_CURSOR, run.source?.scope)
            assertEquals(ToolWriteResult.Applied, gateway.replace(run, target.info))
            assertEquals("ENGINE", target.text)
            assertEquals(ToolUndoResult.Applied, gateway.undo(run, target.info))
            assertEquals(committed, target.text)
        } finally {
            runBlocking { backend.shutdown() }
        }
    }

    private companion object {
        private lateinit var engine: DeployedEngine

        @BeforeClass
        @JvmStatic
        fun deployEngine() {
            engine = DeployedEngine("editor-tool-gateway", english = false)
        }

        @AfterClass
        @JvmStatic
        fun removeEngine() {
            if (::engine.isInitialized) engine.delete()
        }
    }
}
