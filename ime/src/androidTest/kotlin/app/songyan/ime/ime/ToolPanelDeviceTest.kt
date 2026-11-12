package app.songyan.ime.ime

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
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
import app.songyan.ime.tools.TextScope
import app.songyan.ime.tools.ToolCapability
import app.songyan.ime.tools.ToolDescriptor
import app.songyan.ime.tools.ToolFailure
import app.songyan.ime.tools.ToolLimits
import app.songyan.ime.tools.ToolPhase
import app.songyan.ime.tools.ToolPreview
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

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

    // --- the visual/UX guarantees of the panel ---

    @Test
    fun manyToolsScrollInsideThePanel() {
        val many = (0 until 24).map { ToolDescriptor("tool$it", "Tool number $it", setOf(ToolCapability.TEXT)) }
        lateinit var view: ToolPanelView
        instrumentation.runOnMainSync {
            view = ToolPanelView(context)
            view.render(
                ToolPanelController.State(
                    available = true,
                    tools = many,
                    tool = null,
                    phase = ToolPhase.IDLE,
                    preview = null,
                    failure = null,
                    canUndo = false,
                ),
            )
        }
        layout(view, width = 640, height = 420)

        val scroll = findView(view, ScrollView::class.java)
        assertTrue("the list must be scrollable, not clipped", scroll.canScrollVertically(1))
        val shown = buttonTexts(view)
        for (tool in many) assertTrue("every tool must be in the list: " + tool.title, tool.title in shown)
    }

    @Test
    fun toolsDisappearingWhileThePanelIsOpenShowTheEmptyState() {
        val target = field("hello")
        var tools = listOf(tool)
        val mutableCatalog = ToolCatalog { tools }
        val bridge =
            EditorToolGatewayBridge(editor, mutableCatalog, limits, info = { target.info }, clock = { 1_000L })
        val controller =
            ToolPanelController(
                bridge,
                ToolExecutor { _, input -> listOf(input.uppercase()) },
                scope,
                clock = { 1_000L },
            )
        activate(target)
        controller.open()
        lateinit var view: ToolPanelView
        instrumentation.runOnMainSync {
            view = ToolPanelView(context)
            controller.onChanged = view::render
            view.render(controller.state())
        }
        assertEquals(context.getString(R.string.ime_tool_panel_select_hint), statusText(view))

        // Every tool went away while the panel stayed open (scripts disabled, extension removed).
        tools = emptyList()
        instrumentation.runOnMainSync { view.render(controller.state()) }

        assertEquals(
            "the open panel must say the tools are gone",
            context.getString(R.string.ime_tool_panel_unavailable),
            statusText(view),
        )
        assertTrue("no row for a tool that no longer exists", buttonTexts(view).isEmpty())
    }

    @Test
    fun copyingSaysSoForAMomentAndThenReturnsToTheHint() {
        val view = showPreview(listOf("x"), selected = 0)
        var copied = true
        instrumentation.runOnMainSync {
            view.onCopy = { copied }
            requireNotNull(buttonWithText(view, context.getString(R.string.ime_tool_panel_copy))).performClick()
        }
        assertEquals(
            "a copy says it happened",
            context.getString(R.string.ime_tool_panel_written),
            statusText(view),
        )

        Thread.sleep(2_200)
        instrumentation.runOnMainSync { }
        assertEquals(
            "the tip goes away and the phase's own status comes back",
            context.getString(R.string.ime_tool_panel_select_hint),
            statusText(view),
        )

        instrumentation.runOnMainSync {
            copied = false
            requireNotNull(buttonWithText(view, context.getString(R.string.ime_tool_panel_copy))).performClick()
        }
        assertEquals(
            "a copy that copied nothing says nothing",
            context.getString(R.string.ime_tool_panel_select_hint),
            statusText(view),
        )
    }

    @Test
    fun theLastToolRowStaysAboveTheActionRow() {
        val many = (0 until 24).map { ToolDescriptor("tool$it", "Tool number $it", setOf(ToolCapability.TEXT)) }
        lateinit var view: ToolPanelView
        instrumentation.runOnMainSync {
            view = ToolPanelView(context)
            view.render(
                ToolPanelController.State(
                    available = true,
                    tools = many,
                    tool = null,
                    phase = ToolPhase.IDLE,
                    preview = null,
                    failure = null,
                    canUndo = false,
                ),
            )
        }
        layout(view, width = 640, height = 420)

        val scroll = findView(view, ScrollView::class.java)
        val actionRow = findView(view, HorizontalScrollView::class.java)
        assertTrue(
            "the scrollable list must end above the action row: " + scroll.bottom + " vs " + actionRow.top,
            scroll.bottom <= actionRow.top,
        )
        val list = scroll.getChildAt(0) as ViewGroup
        val last = list.getChildAt(list.childCount - 1)
        assertTrue("the list keeps a safe inset under its last row", list.paddingBottom > 0)
        assertTrue(
            "the last row stays above the inset, never behind the action row",
            last.bottom <= list.height - list.paddingBottom,
        )

        instrumentation.runOnMainSync { scroll.scrollTo(0, Int.MAX_VALUE) }
        assertFalse("the end of the list must be reachable", scroll.canScrollVertically(1))
    }

    @Test
    fun aLongResultIsFoldedUntilTheUserUnfoldsIt() {
        val long = "很长的结果".repeat(60)
        val view = showPreview(listOf(long, "短结果"), selected = 0)
        layout(view, width = 640, height = 720)

        val folded = resultBodies(view).first { it.text.length > 100 }
        assertEquals("a long result starts folded", 3, folded.maxLines)
        val expand = findTextViews(view, context.getString(R.string.ime_tool_panel_expand))
        assertTrue("the fold link must be offered", expand.isNotEmpty())

        instrumentation.runOnMainSync { expand.first().performClick() }

        val unfolded = resultBodies(view).first { it.text.length > 100 }
        assertEquals("unfolded results show everything", Int.MAX_VALUE, unfolded.maxLines)
        assertTrue(
            "the link must now offer folding",
            findTextViews(view, context.getString(R.string.ime_tool_panel_collapse)).isNotEmpty(),
        )
    }

    @Test
    fun theActionRowScrollsSidewaysInsteadOfClippingButtons() {
        val view = showPreview(listOf("x"), selected = 0)
        layout(view, width = 300, height = 720)

        val actionScroll = findView(view, HorizontalScrollView::class.java)
        val row = actionScroll.getChildAt(0) as ViewGroup
        assertTrue("the action row must not be clipped", row.width > actionScroll.width)
        assertTrue(
            "the row must scroll",
            actionScroll.canScrollHorizontally(1) || actionScroll.canScrollHorizontally(-1),
        )
        val last = row.getChildAt(row.childCount - 1)
        assertTrue("the last action keeps a real touch target", last.width > 0 && last.right <= row.width)
    }

    @Test
    fun theSelectedResultIsMarkedForTalkBack() {
        val view = showPreview(listOf("第一", "第二"), selected = 1)
        layout(view, width = 640, height = 720)

        val bodies = resultBodies(view)
        val selected = bodies.single { it.isSelected }
        assertTrue("the selected row is the second result", selected.text.contains("第二"))
        assertTrue(
            "TalkBack must hear which result is selected",
            selected.contentDescription.toString().contains("第二"),
        )
        assertTrue("the selected row is the platform's selected state", selected.isSelected)
        assertTrue("the check mark makes the selection visible", selected.text.startsWith("\u2713"))
        assertNull(
            "only the selected row describes itself as selected",
            bodies.first { !it.isSelected }.contentDescription,
        )
        assertNotEquals(
            "the selected result is painted differently",
            bodies.first { !it.isSelected }.currentTextColor,
            selected.currentTextColor,
        )
    }

    @Test
    fun previewActionsAreStyledByTheirWeight() {
        val view = showPreview(listOf("x"), selected = 0)
        val palette = KeyboardPalette.from(context)

        val replace = requireNotNull(buttonWithText(view, context.getString(R.string.ime_tool_panel_replace)))
        val insert = requireNotNull(buttonWithText(view, context.getString(R.string.ime_tool_panel_insert)))
        val copy = requireNotNull(buttonWithText(view, context.getString(R.string.ime_tool_panel_copy)))
        assertEquals("replace is the primary action", palette.accent, buttonColor(replace))
        assertEquals("insert is tonal", palette.control, buttonColor(insert))
        assertEquals("copy is a text action", 0, buttonColor(copy))
        val close = findViewWithDescription(view, context.getString(R.string.ime_tool_panel_close))
        assertTrue("close is an icon action", close is ImageView)
    }

    @Test
    fun aRunningToolShowsProgressAndOnlyCancel() {
        lateinit var view: ToolPanelView
        instrumentation.runOnMainSync {
            view = ToolPanelView(context)
            view.render(
                ToolPanelController.State(
                    available = true,
                    tools = listOf(tool),
                    tool = tool,
                    phase = ToolPhase.RUNNING,
                    preview = null,
                    failure = null,
                    canUndo = false,
                ),
            )
        }
        assertEquals("a run shows it is working", View.VISIBLE, findView(view, ProgressBar::class.java).visibility)
        val texts = buttonTexts(view)
        assertTrue(context.getString(R.string.ime_tool_panel_cancel) in texts)
        assertFalse(
            "no write action while the run is in flight",
            context.getString(R.string.ime_tool_panel_replace) in texts,
        )
    }

    @Test
    fun darkPaletteKeepsThePanelTextReadable() {
        val palette = KeyboardPalette.resolve(context, dark = true)
        val view = showPreview(listOf("暗色下的结果"), selected = 0, palette = palette)
        layout(view, width = 720, height = 900)

        assertEquals(palette.background, (view.background as ColorDrawable).color)
        val title = view.getChildAt(0) as TextView
        val status = view.getChildAt(1) as TextView
        assertEquals(palette.text, title.currentTextColor)
        assertTrue(
            "the title must stay readable on the dark surface: " + contrast(palette.text, palette.background),
            contrast(palette.text, palette.background) >= 4.5,
        )
        // Secondary text may be the muted role or, when that pair falls short, the body colour.
        val statusContrast = contrast(status.currentTextColor, palette.background)
        assertTrue(
            "the status must stay readable on the dark surface: " + statusContrast,
            statusContrast >= 4.5,
        )
        val replace = requireNotNull(buttonWithText(view, context.getString(R.string.ime_tool_panel_replace)))
        val replaceBackground = buttonColor(replace)
        assertEquals(palette.accent, replaceBackground)
        assertTrue(
            "the primary action label must contrast with its fill: " +
                contrast(replace.currentTextColor, replaceBackground),
            contrast(replace.currentTextColor, replaceBackground) >= 4.5,
        )
        val insert = requireNotNull(buttonWithText(view, context.getString(R.string.ime_tool_panel_insert)))
        assertTrue(
            "the tonal action label must contrast with its fill: " +
                contrast(insert.currentTextColor, palette.control),
            contrast(insert.currentTextColor, palette.control) >= 4.5,
        )
        val copy = requireNotNull(buttonWithText(view, context.getString(R.string.ime_tool_panel_copy)))
        assertTrue(
            "the text action must contrast with the panel surface: " +
                contrast(copy.currentTextColor, palette.background),
            contrast(copy.currentTextColor, palette.background) >= 4.5,
        )
        val selectedResult = resultBodies(view).single { it.isSelected }
        assertTrue(
            "the selected result label must contrast with its fill: " +
                contrast(selectedResult.currentTextColor, palette.accent),
            contrast(selectedResult.currentTextColor, palette.accent) >= 4.5,
        )
        // A picture of the dark panel, so the palette claim can be looked at, not only computed.
        val screenshot = saveScreenshot(view, 720, 900, "tool-panel-dark.png")
        assertTrue("the dark panel must render: " + screenshot.length(), screenshot.length() > 0)

        // The tool rows are Buttons too: the platform theme's light tint must not survive here.
        instrumentation.runOnMainSync {
            view.render(
                ToolPanelController.State(
                    available = true,
                    tools = listOf(tool),
                    tool = null,
                    phase = ToolPhase.IDLE,
                    preview = null,
                    failure = null,
                    canUndo = false,
                ),
            )
        }
        val toolRow = requireNotNull(buttonWithText(view, tool.title))
        assertEquals("tool rows take the palette key surface", palette.key, buttonColor(toolRow))
        assertEquals(palette.text, toolRow.currentTextColor)
        assertTrue(
            "a tool row label must contrast with its key surface: " +
                contrast(toolRow.currentTextColor, palette.key),
            contrast(toolRow.currentTextColor, palette.key) >= 4.5,
        )
    }

    private fun layout(
        view: ToolPanelView,
        width: Int,
        height: Int,
    ) {
        instrumentation.runOnMainSync {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, width, height)
        }
    }

    private fun previewState(
        results: List<String>,
        selected: Int,
    ): ToolPanelController.State =
        ToolPanelController.State(
            available = true,
            tools = listOf(tool),
            tool = tool,
            phase = ToolPhase.PREVIEWING,
            preview = ToolPreview("run", tool.title, TextScope.SELECTION, false, results, selected),
            failure = null,
            canUndo = false,
        )

    private fun showPreview(
        results: List<String>,
        selected: Int,
        palette: KeyboardPalette? = null,
    ): ToolPanelView {
        lateinit var view: ToolPanelView
        instrumentation.runOnMainSync {
            view = if (palette == null) ToolPanelView(context) else ToolPanelView(context, palette)
            view.render(previewState(results, selected))
        }
        return view
    }

    private fun resultBodies(view: ToolPanelView): List<TextView> {
        val scroll = findView(view, ScrollView::class.java)
        val list = scroll.getChildAt(0) as LinearLayout
        return (0 until list.childCount).map { index ->
            val row = list.getChildAt(index) as ViewGroup
            row.getChildAt(0) as TextView
        }
    }

    private fun textViewsIn(root: ViewGroup): List<TextView> =
        buildList {
            for (index in 0 until root.childCount) {
                when (val child = root.getChildAt(index)) {
                    is TextView -> add(child)
                    is ViewGroup -> addAll(textViewsIn(child))
                }
            }
        }

    private fun findTextViews(
        root: ViewGroup,
        text: String,
    ): List<TextView> = textViewsIn(root).filter { it.text.toString() == text }

    private fun findViewWithDescription(
        root: ViewGroup,
        description: String,
    ): View? {
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            if (child.contentDescription?.toString() == description) return child
            if (child is ViewGroup) findViewWithDescription(child, description)?.let { return it }
        }
        return null
    }

    private fun <T : View> findView(
        root: ViewGroup,
        type: Class<T>,
    ): T {
        val pending = ArrayDeque<ViewGroup>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            val group = pending.removeLast()
            for (index in 0 until group.childCount) {
                val child = group.getChildAt(index)
                if (type.isInstance(child)) return requireNotNull(type.cast(child))
                if (child is ViewGroup) pending.addLast(child)
            }
        }
        error("no " + type.simpleName + " in the panel")
    }

    private fun buttonColor(button: Button): Int = (button.background as GradientDrawable).color?.defaultColor ?: 0

    /** WCAG contrast ratio of two opaque ARGB colours. */
    private fun contrast(
        foreground: Int,
        background: Int,
    ): Double {
        fun luminance(color: Int): Double {
            fun channel(shift: Int): Double {
                val value = ((color shr shift) and 0xFF) / 255.0
                return if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        }
        val first = luminance(foreground)
        val second = luminance(background)
        return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
    }

    private fun saveScreenshot(
        view: ToolPanelView,
        width: Int,
        height: Int,
        name: String,
    ): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        instrumentation.runOnMainSync { view.draw(Canvas(bitmap)) }
        val file = File(context.cacheDir, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
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
