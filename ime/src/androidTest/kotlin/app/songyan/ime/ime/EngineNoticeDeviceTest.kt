package app.songyan.ime.ime

import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.keyboard.engine.Candidate
import app.songyan.ime.keyboard.engine.EngineBackend
import app.songyan.ime.keyboard.engine.EngineBackendKind
import app.songyan.ime.keyboard.engine.EngineSnapshot
import app.songyan.ime.keyboard.engine.KeyboardSchemaDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * While the engine is preparing or has failed, the key area shows the notice instead of keys and
 * commits nothing, the keyboard keeps its height, and a way to another input method stays; when the
 * engine is ready the keys come back without the field being focused again.
 */
@RunWith(AndroidJUnit4::class)
class EngineNoticeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commits = mutableListOf<String>()
    private var deletes = 0
    private var actions = 0
    private val noticeActions = mutableListOf<EngineNoticeAction>()
    private var switches = 0
    private var picks = 0
    private val backend = RecordingBackend()
    private lateinit var controller: KeyboardController

    private val inputMethods =
        object : InputMethodAccess {
            override fun options() = InputMethodOptions(canSwitch = true, voiceLabel = "Voice")

            override fun switchToOther() {
                switches++
            }

            override fun pick() {
                picks++
            }

            override fun startVoice() = true
        }

    @Before
    fun setUp() {
        instrumentation.runOnMainSync {
            controller =
                KeyboardController(
                    context,
                    scope,
                    KeyboardController.Callbacks(
                        commit = { text, _ ->
                            commits += text
                            true
                        },
                        preedit = { _, _ -> },
                        delete = { deletes++ },
                        moveCursor = {},
                        action = { actions++ },
                        error = { throw AssertionError(it) },
                        inputMethods = inputMethods,
                        engineNoticeAction = { noticeActions += it },
                    ),
                )
            val policy = EditorPolicy(false, false, false, EditorInfo.IME_ACTION_NONE)
            controller.configure(policy)
            controller.startInput(policy)
        }
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync {
            controller.retire()
            scope.cancel()
        }
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun layout() {
        controller.view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.AT_MOST),
        )
        controller.view.layout(0, 0, 1080, controller.view.measuredHeight)
    }

    private fun height(): Int {
        var value = 0
        main {
            layout()
            value = controller.view.measuredHeight
        }
        return value
    }

    private fun labels(view: View): List<String> {
        val provider = view.accessibilityNodeProvider
        return generateSequence(0) { it + 1 }
            .map { provider.createAccessibilityNodeInfo(it) }
            .takeWhile { it != null }
            .map { (it!!.contentDescription ?: it.text ?: "").toString() }
            .toList()
    }

    private fun tap(
        label: String,
        view: View = controller.keyboard,
    ) {
        main {
            layout()
            val all = labels(view)
            val index = all.indexOf(label)
            assertTrue("no '$label' in $all", index >= 0)
            assertTrue(view.accessibilityNodeProvider.performAction(index, AccessibilityNodeInfo.ACTION_CLICK, null))
        }
        idle()
    }

    private fun idle() {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
        instrumentation.waitForIdleSync()
    }

    private fun toolbarLabels(): List<String> {
        var value = emptyList<String>()
        main {
            layout()
            value = labels(controller.toolbar)
        }
        return value
    }

    private fun touchKeyArea() {
        main {
            layout()
            val view = controller.keyboard
            val x = view.width / 2f
            val y = view.height / 2f
            val down = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
            val up = android.view.MotionEvent.obtain(0, 50, android.view.MotionEvent.ACTION_UP, x, y, 0)
            view.dispatchTouchEvent(down)
            view.dispatchTouchEvent(up)
            down.recycle()
            up.recycle()
        }
        idle()
    }

    @Test
    fun noKeysAreTypedWhileTheEngineIsPreparing() {
        main { controller.setEngineNotice(EngineNotice(EngineNoticeKind.PREPARING)) }
        idle()
        assertEquals(
            context.getString(R.string.ime_engine_preparing_hint),
            controller.keyboard.notice,
        )
        assertEquals(
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
            controller.keyboard.importantForAccessibility,
        )
        touchKeyArea()
        main {
            controller.enqueueKey(
                app.songyan.ime.layout
                    .LayoutAction(app.songyan.ime.layout.LayoutActionKind.SPACE),
            )
        }
        idle()
        assertTrue("typed $commits", commits.isEmpty())
        assertEquals(0, deletes)
        assertEquals(0, actions)
    }

    @Test
    fun failedStateOffersRetryAndSettingsAndTypesNothing() {
        main {
            controller.setEngineNotice(
                EngineNotice(EngineNoticeKind.FAILED, listOf(EngineNoticeAction.RETRY, EngineNoticeAction.SETTINGS)),
            )
        }
        val retry = context.getString(R.string.ime_engine_retry)
        val settings = context.getString(R.string.ime_engine_settings)
        val toolbar = toolbarLabels()
        assertTrue("toolbar $toolbar", toolbar.containsAll(listOf(retry, settings)))
        tap(retry, controller.toolbar)
        tap(settings, controller.toolbar)
        assertEquals(listOf(EngineNoticeAction.RETRY, EngineNoticeAction.SETTINGS), noticeActions)
        assertEquals(context.getString(R.string.ime_engine_failed_hint), controller.keyboard.notice)
        touchKeyArea()
        assertTrue(commits.isEmpty())
    }

    @Test
    fun noticeRowOffersSwitchingInputMethods() {
        main { controller.setEngineNotice(EngineNotice(EngineNoticeKind.PREPARING)) }
        tap(context.getString(R.string.ime_toolbar_switch_input_method), controller.toolbar)
        assertEquals(1, switches)
        assertTrue(
            "no voice entry in the notice row",
            context.getString(R.string.ime_toolbar_voice_input) !in toolbarLabels(),
        )
    }

    @Test
    fun keyboardHeightIsTheSameWhileTheEngineIsNotReadyAndWhenItIsReady() {
        main { controller.setEngineNotice(EngineNotice(EngineNoticeKind.PREPARING)) }
        idle()
        val unavailable = height()
        main {
            controller.bind(backend)
            controller.setEngineNotice(null)
        }
        idle()
        assertEquals("the keyboard changed height when the engine became ready", unavailable, height())
        main { controller.setEngineNotice(EngineNotice(EngineNoticeKind.FAILED, listOf(EngineNoticeAction.RETRY))) }
        assertEquals("the failed notice changed the height", unavailable, height())
    }

    @Test
    fun keysComeBackWhenTheEngineIsReady() {
        main { controller.setEngineNotice(EngineNotice(EngineNoticeKind.PREPARING)) }
        idle()
        main {
            controller.bind(backend)
            controller.setEngineNotice(null)
        }
        idle()
        assertEquals(null, controller.keyboard.notice)
        tap("h")
        assertEquals("the key reached the engine", listOf("h"), backend.keys)
        assertTrue("nothing reached the editor directly", commits.isEmpty())
        assertTrue(
            "the notice row is the toolbar again: " + toolbarLabels(),
            toolbarLabels().none { it == context.getString(R.string.ime_engine_retry) },
        )
    }

    @Test
    fun switcherAndVoiceAreInTheKeyboardMenu() {
        main {
            controller.bind(backend)
            controller.openKeyboardMenu()
            layout()
        }
        idle()
        val texts = mutableListOf<String>()

        fun collect(view: View) {
            if (view is android.widget.TextView && view.visibility == View.VISIBLE) texts += view.text.toString()
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i))
        }
        main { collect(controller.menu) }
        assertTrue(texts.toString(), texts.contains(context.getString(R.string.ime_menu_voice_input)))
        assertTrue(texts.toString(), texts.contains(context.getString(R.string.ime_menu_pick_input_method)))
        assertTrue(texts.toString(), texts.contains(context.getString(R.string.ime_menu_switch_previous)))
    }

    @Test
    fun customSwitcherRequestAddsAToolbarButton() {
        main { controller.refreshInputMethodOptions() }
        val without = toolbarLabels()
        main { controller.setCustomSwitcherRequested(true) }
        val with = toolbarLabels()
        val label = context.getString(R.string.ime_toolbar_switch_input_method)
        assertTrue(without.toString(), label !in without)
        assertTrue(with.toString(), label in with)
        tap(label, controller.toolbar)
        assertEquals(1, switches)
    }

    private class RecordingBackend : EngineBackend {
        val keys = mutableListOf<String>()
        private var snapshot = EngineSnapshot()
        override val kind = EngineBackendKind.DEMO_SIMULATOR
        override val inputUnavailableReason: String? = null
        override val availableSchemas = emptyList<KeyboardSchemaDescriptor>()
        override val statusSummary = "test"
        override var spellingDemand: (() -> Boolean)? = null

        override suspend fun setup(
            sharedDirectory: String,
            userDirectory: String,
        ) = Unit

        override suspend fun selectSchema(schemaID: String) = Unit

        override suspend fun processKey(keyValue: String): EngineSnapshot {
            keys += keyValue
            snapshot =
                EngineSnapshot(
                    rawInput = keyValue,
                    preedit = keyValue,
                    isComposing = true,
                    candidates = listOf(Candidate(0, "去")),
                    highlightedIndex = 0,
                )
            return snapshot
        }

        override suspend fun cancelComposition(): EngineSnapshot {
            snapshot = EngineSnapshot()
            return snapshot
        }

        override suspend fun selectCandidate(index: Int): EngineSnapshot {
            snapshot = EngineSnapshot()
            return snapshot.copy(committed = "去")
        }

        override suspend fun nextPage() = snapshot

        override suspend fun previousPage() = snapshot

        override fun currentSnapshot() = snapshot

        override suspend fun shutdown() = Unit
    }
}
