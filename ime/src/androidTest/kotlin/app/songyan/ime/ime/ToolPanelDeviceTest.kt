package app.songyan.ime.ime

import android.text.InputType
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.candidate.ToolbarLayout
import app.songyan.ime.ime.tools.ToolCatalog
import app.songyan.ime.ime.tools.ToolEditorBridge
import app.songyan.ime.ime.tools.ToolExecutor
import app.songyan.ime.ime.tools.ToolPanelController
import app.songyan.ime.ime.tools.ToolPanelView
import app.songyan.ime.ime.tools.ToolUndoResult
import app.songyan.ime.ime.tools.ToolWriteResult
import app.songyan.ime.tools.ToolCapability
import app.songyan.ime.tools.ToolDescriptor
import app.songyan.ime.tools.ToolFailure
import app.songyan.ime.tools.ToolLimits
import app.songyan.ime.tools.ToolPhase
import app.songyan.ime.tools.WriteBlockReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The tool panel over a real platform editor and the shipped librime engine: an instrumented
 * [EditText] with the platform's own [InputConnection], the same [EditorConnection] the input method
 * service uses, the real [EditorToolGatewayBridge] adapter and test tools. No engine stub.
 */
@RunWith(AndroidJUnit4::class)
class ToolPanelDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val limits = ToolLimits(maxInputUnits = 64, timeoutMillis = 5_000, undoWindowMillis = null, maxResults = 3)
    private val tool = ToolDescriptor("upper", "Upper", setOf(ToolCapability.TEXT))
    private val catalog = ToolCatalog { listOf(tool) }

    private lateinit var activeConnection: InputConnection
    private val editor = EditorConnection { activeConnection }

    private inner class Field(
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
    ): Field {
        lateinit var result: Field
        instrumentation.runOnMainSync {
            val edit = EditText(context)
            edit.setInputType(inputType)
            edit.imeOptions = imeOptions
            edit.setText(text)
            edit.setSelection(edit.text.length)
            val info = EditorInfo()
            info.inputType = inputType
            info.packageName = context.packageName
            info.fieldId = 1
            val connection = requireNotNull(edit.onCreateInputConnection(info))
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
    }

    private fun bridge(target: Field): ToolEditorBridge =
        EditorToolGatewayBridge(editor, catalog, limits, info = { target.info }, clock = { 1_000L })

    private fun controller(
        target: Field,
        bridge: ToolEditorBridge = bridge(target),
        executor: ToolExecutor = ToolExecutor { _, input -> listOf(input.uppercase()) },
    ): ToolPanelController {
        activate(target)
        return ToolPanelController(bridge, executor, scope, clock = { 1_000L })
    }

    private fun awaitPhase(
        controller: ToolPanelController,
        phase: ToolPhase,
    ): ToolPanelController.State {
        repeat(200) {
            val state = controller.state()
            if (state.phase == phase) return state
            instrumentation.waitForIdleSync()
        }
        return controller.state()
    }

    @Test
    fun noToolsMeansNoToolbarEntry() {
        val without =
            ToolbarActions.assemble(
                ToolbarLayout.DEFAULT,
                hidden = false,
                switcher = false,
                oneHandAvailable = false,
            )
        assertFalse("no tools must leave no entry", ToolbarView.Action.TOOLS in without)
        val with =
            ToolbarActions.assemble(
                ToolbarLayout.DEFAULT,
                hidden = false,
                switcher = false,
                oneHandAvailable = false,
                toolsAvailable = true,
            )
        assertTrue(ToolbarView.Action.TOOLS in with)
        assertEquals(ToolbarView.Action.HIDE, with.last())
    }

    @Test
    fun aPasswordFieldHidesTheEntryAndRefusesToRun() {
        val target = field("secret", inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val controller = controller(target)
        assertFalse(controller.isAvailable())
        assertFalse(controller.state().available)
        controller.start(tool)
        assertEquals(ToolPhase.IDLE, controller.state().phase)
        assertEquals("secret", target.text)
    }

    @Test
    fun aNoPersonalizedLearningFieldIsUsable() {
        val target = field("hello", imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        assertTrue(controller(target).isAvailable())
    }

    @Test
    fun previewCopyReplaceAndUndoOnRealEngineText() {
        val target = field("")
        activate(target)
        typeWithEngine(target)
        assertTrue("the real engine must type", target.text.isNotEmpty())
        val original = target.text
        val controller = controller(target, executor = ToolExecutor { _, _ -> listOf("x") })
        controller.start(tool)
        val preview = awaitPhase(controller, ToolPhase.PREVIEWING)
        assertNotEquals(null, preview.preview)
        assertEquals("x", controller.copy())
        controller.replace()
        assertEquals("x", target.text)
        controller.undo()
        assertEquals(original, target.text)
    }

    @Test
    fun insertKeepsTheTextBeforeTheCaret() {
        val target = field("alpha ")
        val controller = controller(target, executor = ToolExecutor { _, _ -> listOf("beta") })
        controller.start(tool)
        awaitPhase(controller, ToolPhase.PREVIEWING)
        controller.insert()
        assertEquals("alpha beta", target.text)
    }

    @Test
    fun anEmptyResultCanBeUndoneThroughThePanel() {
        val target = field("hello")
        val controller = controller(target, executor = ToolExecutor { _, _ -> listOf("") })
        controller.start(tool)
        awaitPhase(controller, ToolPhase.PREVIEWING)
        controller.replace()
        assertEquals("", target.text)
        controller.undo()
        assertEquals("hello", target.text)
    }

    @Test
    fun aChangedFieldShowsTheModelReasonAndWritesNothing() {
        val first = field("first")
        val second = field("second")
        val controller = controller(first, bridge(first), ToolExecutor { _, _ -> listOf("X") })
        controller.start(tool)
        awaitPhase(controller, ToolPhase.PREVIEWING)
        activate(second)
        controller.replace()
        val state = controller.state()
        assertEquals(ToolWriteResult.Blocked(WriteBlockReason.DOCUMENT_CHANGED), state.write)
        assertEquals("second", second.text)
        assertEquals(context.getString(R.string.ime_tool_panel_field_changed), reasonText(controller))
    }

    @Test
    fun undoAfterUserTypingShowsTheModelReason() {
        val target = field("hello")
        val controller = controller(target, bridge(target), ToolExecutor { _, _ -> listOf("HI") })
        controller.start(tool)
        awaitPhase(controller, ToolPhase.PREVIEWING)
        controller.replace()
        instrumentation.runOnMainSync {
            editor.update("", listOf("!"))
            target.info.initialSelStart = target.edit.text.length
            target.info.initialSelEnd = target.edit.text.length
            editor.startInput(target.info, false)
        }
        controller.undo()
        assertEquals("HI!", target.text)
        assertEquals(ToolUndoResult.Blocked(WriteBlockReason.EDITED_AFTER_WRITE), controller.state().undo)
        assertEquals(context.getString(R.string.ime_tool_panel_edited), reasonText(controller))
    }

    @Test
    fun anInsertOverASelectionShowsTheModelReason() {
        val target = field("hello world")
        val bridge = bridge(target)
        val controller = controller(target, bridge, ToolExecutor { _, _ -> listOf("X") })
        // Start on a selection so the model sees a live selection at insert time.
        instrumentation.runOnMainSync {
            target.edit.setSelection(6, 11)
            target.info.initialSelStart = 6
            target.info.initialSelEnd = 11
            editor.startInput(target.info, false)
        }
        controller.start(tool)
        awaitPhase(controller, ToolPhase.PREVIEWING)
        controller.insert()
        assertEquals(
            ToolWriteResult.Blocked(WriteBlockReason.UNSUPPORTED_INSERTION),
            controller.state().write,
        )
        assertEquals("hello world", target.text)
        assertEquals(context.getString(R.string.ime_tool_panel_selection), reasonText(controller))
    }

    @Test
    fun aTimedOutRunIsStoppedAndCancelsItsTask() {
        val shortLimits = ToolLimits(maxInputUnits = 64, timeoutMillis = 100, undoWindowMillis = null, maxResults = 3)
        val target = field("hello")
        activate(target)
        val bridge = EditorToolGatewayBridge(editor, catalog, shortLimits, info = { target.info })
        val cancelled = CompletableDeferred<Unit>()
        val executor =
            ToolExecutor { _, _ ->
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { cancelled.complete(Unit) }
                }
            }
        val controller = ToolPanelController(bridge, executor, scope)
        controller.start(tool)
        assertEquals(ToolPhase.RUNNING, controller.state().phase)
        val state = awaitPhaseSlow(controller, ToolPhase.FAILED, 6_000)
        assertEquals(ToolPhase.FAILED, state.phase)
        assertTrue("the timeout must be reported: " + state.failure, state.failure is ToolFailure.TimedOut)
        runBlocking { withTimeout(1_000) { cancelled.await() } }
        assertEquals("hello", target.text)
    }

    @Test
    fun timeoutAndNoResultShowTheirOwnCopy() {
        lateinit var view: ToolPanelView
        instrumentation.runOnMainSync {
            view = ToolPanelView(context)
            view.render(
                ToolPanelController.State(
                    available = true,
                    tools = listOf(tool),
                    tool = tool,
                    phase = ToolPhase.FAILED,
                    preview = null,
                    failure = ToolFailure.TimedOut,
                    canUndo = false,
                ),
            )
        }
        assertEquals(context.getString(R.string.ime_tool_panel_timeout), statusText(view))
        instrumentation.runOnMainSync {
            view.render(
                ToolPanelController.State(
                    available = true,
                    tools = listOf(tool),
                    tool = tool,
                    phase = ToolPhase.FAILED,
                    preview = null,
                    failure = ToolFailure.NoResult,
                    canUndo = false,
                ),
            )
        }
        assertEquals(context.getString(R.string.ime_tool_panel_no_result), statusText(view))
    }

    @Test
    fun aRefusedWriteKeepsThePreviewActions() {
        val first = field("first")
        val second = field("second")
        val controller = controller(first, bridge(first), ToolExecutor { _, _ -> listOf("X") })
        controller.start(tool)
        awaitPhase(controller, ToolPhase.PREVIEWING)
        activate(second)
        controller.replace()
        lateinit var view: ToolPanelView
        instrumentation.runOnMainSync {
            view = ToolPanelView(context)
            controller.onChanged = view::render
            view.render(controller.state())
        }
        val texts = buttonTexts(view)
        assertTrue("copy must stay available: " + texts, context.getString(R.string.ime_tool_panel_copy) in texts)
        assertTrue("replace must stay available: " + texts, context.getString(R.string.ime_tool_panel_replace) in texts)
        assertTrue("insert must stay available: " + texts, context.getString(R.string.ime_tool_panel_insert) in texts)
        assertEquals(context.getString(R.string.ime_tool_panel_field_changed), statusText(view))
    }

    @Test
    fun aFailedRunOffersTheToolsAgain() {
        lateinit var view: ToolPanelView
        instrumentation.runOnMainSync {
            view = ToolPanelView(context)
            view.render(
                ToolPanelController.State(
                    available = true,
                    tools = listOf(tool),
                    tool = tool,
                    phase = ToolPhase.FAILED,
                    preview = null,
                    failure = ToolFailure.NoResult,
                    canUndo = false,
                ),
            )
        }
        val texts = buttonTexts(view)
        assertTrue("the tool must be offered for a retry: " + texts, tool.title in texts)
        assertEquals(context.getString(R.string.ime_tool_panel_no_result), statusText(view))
    }

    @Test
    fun aToolErrorShowsTheToolNameAndItsOwnWords() {
        lateinit var view: ToolPanelView
        val message = "保安找不到这本书"
        instrumentation.runOnMainSync {
            view = ToolPanelView(context)
            view.render(
                ToolPanelController.State(
                    available = true,
                    tools = listOf(tool),
                    tool = tool,
                    phase = ToolPhase.FAILED,
                    preview = null,
                    failure = ToolFailure.ToolError(message),
                    canUndo = false,
                ),
            )
        }
        assertEquals(
            context.getString(R.string.ime_tool_panel_error_with_tool, tool.title, message),
            statusText(view),
        )
        val shown = requireNotNull(statusText(view))
        assertTrue("the tool name must survive: " + shown, shown.contains(tool.title))
        assertTrue("the tool's own words must survive: " + shown, shown.contains(message))
    }

    @Test
    fun aToolErrorWithoutWordsFallsBackToTheGenericCopy() {
        lateinit var view: ToolPanelView
        instrumentation.runOnMainSync {
            view = ToolPanelView(context)
            view.render(
                ToolPanelController.State(
                    available = true,
                    tools = listOf(tool),
                    tool = tool,
                    phase = ToolPhase.FAILED,
                    preview = null,
                    failure = ToolFailure.ToolError(null),
                    canUndo = false,
                ),
            )
        }
        assertEquals(context.getString(R.string.ime_tool_panel_failed), statusText(view))
    }

    @Test
    fun aFailedRunOffersAnExplicitRetryForTheSameTool() {
        lateinit var view: ToolPanelView
        var retried: ToolDescriptor? = null
        instrumentation.runOnMainSync {
            view = ToolPanelView(context)
            view.onRun = { retried = it }
            view.render(
                ToolPanelController.State(
                    available = true,
                    tools = listOf(tool),
                    tool = tool,
                    phase = ToolPhase.FAILED,
                    preview = null,
                    failure = ToolFailure.ToolError("boom"),
                    canUndo = false,
                ),
            )
        }
        val retry =
            requireNotNull(buttonWithText(view, context.getString(R.string.ime_tool_panel_retry))) {
                "a failed run must offer an explicit retry"
            }
        instrumentation.runOnMainSync { retry.performClick() }
        assertEquals("retry re-runs the tool that failed", tool, retried)
    }

    @Test
    fun cancelWhileRunningKeepsTheDocumentUntouched() {
        val target = field("hello")
        val never = CompletableDeferred<List<String>>()
        val controller = controller(target, bridge(target), ToolExecutor { _, _ -> never.await() })
        controller.start(tool)
        assertEquals(ToolPhase.RUNNING, controller.state().phase)
        controller.cancel()
        assertEquals(ToolPhase.IDLE, controller.state().phase)
        assertEquals("hello", target.text)
    }

    @Test
    fun anEditorChangeCancelsTheRun() {
        val target = field("hello")
        val never = CompletableDeferred<List<String>>()
        val controller = controller(target, bridge(target), ToolExecutor { _, _ -> never.await() })
        controller.start(tool)
        assertEquals(ToolPhase.RUNNING, controller.state().phase)
        controller.onEditorChanged()
        assertEquals(ToolPhase.IDLE, controller.state().phase)
        assertEquals("hello", target.text)
    }

    @Test
    fun thePanelViewRendersTheModelStateAndStrings() {
        lateinit var view: ToolPanelView
        val target = field("hello")
        instrumentation.runOnMainSync {
            view = ToolPanelView(context)
            val controller = controller(target, bridge(target), ToolExecutor { _, input -> listOf(input) })
            view.onRun = controller::start
            view.onSelect = controller::selectResult
            view.onReplace = controller::replace
            view.onInsert = controller::insert
            view.onUndo = controller::undo
            view.onCancel = controller::cancel
            view.onCloseRequested = {}
            controller.onChanged = view::render
            controller.open()
            view.render(controller.state())
        }
        instrumentation.waitForIdleSync()
        assertTrue(view.childCount > 0)
        assertEquals(context.getString(R.string.ime_tool_panel_title), (view.getChildAt(0) as? TextView)?.text)
    }

    private fun reasonText(controller: ToolPanelController): String? {
        val view = ToolPanelView(context)
        view.render(controller.state())
        return statusText(view)
    }

    private fun buttonWithText(
        view: ViewGroup,
        text: String,
    ): Button? {
        for (index in 0 until view.childCount) {
            when (val child = view.getChildAt(index)) {
                is Button -> if (child.text?.toString() == text) return child
                is ViewGroup -> buttonWithText(child, text)?.let { return it }
            }
        }
        return null
    }

    private fun buttonTexts(view: ViewGroup): List<String> =
        buildList {
            for (index in 0 until view.childCount) {
                when (val child = view.getChildAt(index)) {
                    is android.widget.Button -> add(child.text?.toString().orEmpty())
                    is ViewGroup -> addAll(buttonTexts(child))
                }
            }
        }

    private fun statusText(view: ToolPanelView): String? = (view.getChildAt(1) as? TextView)?.text?.toString()

    private fun awaitPhaseSlow(
        controller: ToolPanelController,
        phase: ToolPhase,
        timeoutMs: Long,
    ): ToolPanelController.State {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val state = controller.state()
            if (state.phase == phase) return state
            Thread.sleep(20)
        }
        return controller.state()
    }

    private fun typeWithEngine(target: Field) {
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
            assertNotEquals("", committed)
            instrumentation.runOnMainSync {
                editor.update("", listOf(committed))
                target.info.initialSelStart = target.edit.text.length
                target.info.initialSelEnd = target.edit.text.length
                editor.startInput(target.info, false)
            }
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
            engine = DeployedEngine("tool-panel", english = false)
        }

        @AfterClass
        @JvmStatic
        fun removeEngine() {
            if (::engine.isInitialized) engine.delete()
        }
    }
}
