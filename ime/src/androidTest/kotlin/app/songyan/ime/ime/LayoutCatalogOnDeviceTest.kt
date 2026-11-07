package app.songyan.ime.ime

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.keyboard.engine.EngineBackend
import app.songyan.ime.keyboard.engine.EngineBackendKind
import app.songyan.ime.keyboard.engine.EngineSnapshot
import app.songyan.ime.keyboard.engine.KeyboardSchemaDescriptor
import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.KeyboardComposition
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutRegistry
import app.songyan.ime.layout.LayoutRow
import app.songyan.ime.layout.LayoutSplitConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A layout the user made reaches the keyboard on a real view: its keys type, and its split opens a gutter. */
@RunWith(AndroidJUnit4::class)
class LayoutCatalogOnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val backend = RecordingBackend()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var controller: KeyboardController

    @After
    fun tearDown() {
        instrumentation.runOnMainSync {
            if (::controller.isInitialized) controller.retire()
            scope.cancel()
            KeyboardLayoutCatalog.install(LayoutRegistry.create())
        }
    }

    private fun start() {
        instrumentation.runOnMainSync {
            controller =
                KeyboardController(
                    instrumentation.targetContext,
                    scope,
                    KeyboardController.Callbacks(
                        commit = { _, _ -> true },
                        preedit = { _, _ -> },
                        delete = {},
                        moveCursor = {},
                        action = {},
                        error = { throw AssertionError(it) },
                    ),
                )
            controller.bind(backend)
            controller.configure(EditorPolicy(false, false, false, EditorInfo.IME_ACTION_NONE))
            controller.startInput(EditorPolicy(false, false, false, EditorInfo.IME_ACTION_NONE))
        }
        instrumentation.waitForIdleSync()
    }

    private fun install(
        layoutRows: List<LayoutRow>?,
        split: LayoutSplitConfiguration? = null,
    ) {
        var mine = BuiltinLayouts.qwerty26.copyWithId("mine", "我的").withSplit(split)
        if (layoutRows != null) mine = mine.copy(rows = layoutRows + mine.rows.last())
        val registry =
            LayoutRegistry
                .create()
                .install(mine.encoded())
                .withComposition(KeyboardComposition(mainLayoutID = "mine"), "songyan_quanpin:qwerty26")
        instrumentation.runOnMainSync { KeyboardLayoutCatalog.install(registry) }
        instrumentation.waitForIdleSync()
    }

    private fun layoutKeyboard(width: Int) {
        instrumentation.runOnMainSync {
            controller.view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST),
            )
            controller.view.layout(0, 0, width, controller.view.measuredHeight)
        }
    }

    private fun tap(
        x: Float,
        y: Float,
    ) {
        instrumentation.runOnMainSync {
            val keyArea = controller.content.getChildAt(1)
            val now = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(now, now + 30, MotionEvent.ACTION_UP, x, y, 0)
            keyArea.dispatchTouchEvent(down)
            keyArea.dispatchTouchEvent(up)
            down.recycle()
            up.recycle()
        }
        instrumentation.waitForIdleSync()
    }

    private fun letter(code: String) = LayoutKey(code, action = LayoutAction.code(code))

    @Test
    fun theFirstKeyOfAUserLayoutTypesItsOwnCode() {
        start()
        // A one-row layout of z, x and c: the keyboard has no other source for these letters.
        install(listOf(LayoutRow(listOf(letter("z"), letter("x"), letter("c")))))
        layoutKeyboard(1080)
        var keys = emptyList<LayoutKey>()
        var bounds = emptyList<KeyBounds>()
        instrumentation.runOnMainSync {
            keys = controller.keyboard.keys
            bounds = controller.keyboard.bounds
        }
        val z = keys.indexOfFirst { it.action.value == "z" }
        assertTrue("the user's key is on the keyboard", z >= 0)
        assertEquals(0, keys.count { it.action.value == "q" })
        val box = bounds[z]
        tap((box.left + box.right) / 2, (box.top + box.bottom) / 2)
        assertEquals(listOf("z"), backend.keys)
    }

    @Test
    fun aLayoutThatSplitsOpensAGutterBetweenItsHalvesAndStillTypesOnBothSides() {
        start()
        install(
            null,
            LayoutSplitConfiguration.of(
                LayoutSplitConfiguration.Activation.ALWAYS,
                rowBreaks = listOf(5, 5, 4, 3),
                gapFraction = 0.2,
            ),
        )
        layoutKeyboard(1080)
        var bounds = emptyList<KeyBounds>()
        instrumentation.runOnMainSync { bounds = controller.keyboard.bounds }
        // First row: ten letters; the fifth and sixth sit on either side of the gutter.
        val gutter = bounds[5].left - bounds[4].right
        val ordinaryGap = bounds[1].left - bounds[0].right
        assertTrue("gutter $gutter must exceed the key gap $ordinaryGap", gutter > ordinaryGap * 5)
        // The key right of the gutter still types its letter ("y").
        val box = bounds[5]
        tap((box.left + box.right) / 2, (box.top + box.bottom) / 2)
        assertEquals(listOf("y"), backend.keys)
    }

    @Test
    fun aCopiedLayoutWithSplitOnDrawsTheHandCutsSoBIsOnTheLeftHalf() {
        start()
        install(null, LayoutSplitConfiguration.of(LayoutSplitConfiguration.Activation.ALWAYS))
        layoutKeyboard(1080)
        var bounds = emptyList<KeyBounds>()
        instrumentation.runOnMainSync { bounds = controller.keyboard.bounds }
        // Last letter row: Shift, z, x, c, v, b | n, m, Delete after the 10 + 9 keys above it.
        val gutter = bounds[25].left - bounds[24].right
        val ordinaryGap = bounds[1].left - bounds[0].right
        assertTrue("gutter $gutter must follow b, not v", gutter > ordinaryGap * 5)
        val box = bounds[24]
        tap((box.left + box.right) / 2, (box.top + box.bottom) / 2)
        assertEquals(listOf("b"), backend.keys)
    }

    @Test
    fun theBundledLayoutDoesNotSplitOnAPhone() {
        start()
        layoutKeyboard(1080)
        var bounds = emptyList<KeyBounds>()
        instrumentation.runOnMainSync { bounds = controller.keyboard.bounds }
        val ordinaryGap = bounds[1].left - bounds[0].right
        assertEquals(ordinaryGap, bounds[5].left - bounds[4].right, 0.5f)
    }

    private class RecordingBackend : EngineBackend {
        val keys = mutableListOf<String>()
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
            return EngineSnapshot()
        }

        override suspend fun cancelComposition() = EngineSnapshot()

        override suspend fun selectCandidate(index: Int) = EngineSnapshot()

        override suspend fun nextPage() = EngineSnapshot()

        override suspend fun previousPage() = EngineSnapshot()

        override fun currentSnapshot() = EngineSnapshot()

        override suspend fun shutdown() = Unit
    }
}
