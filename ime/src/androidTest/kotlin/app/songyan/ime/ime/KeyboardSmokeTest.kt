package app.songyan.ime.ime

import android.os.SystemClock
import android.view.MotionEvent
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyboardSmokeTest {
    @Test fun canvasKeyReachesEngineAndPrivateEditorUsesDirectInput() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val backend = RecordingBackend()
        val commits = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var controller: KeyboardController
        instrumentation.runOnMainSync {
            controller =
                KeyboardController(
                    instrumentation.targetContext,
                    scope,
                    KeyboardController.Callbacks(
                        commit = { text, _ ->
                            commits += text
                            true
                        },
                        preedit = {},
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
        instrumentation.runOnMainSync { tapFirstLetter(controller) }
        instrumentation.waitForIdleSync()
        assertEquals(listOf("q"), backend.keys)
        instrumentation.runOnMainSync {
            // The first candidate shows once a composition replaced the idle toolbar: lay it out.
            controller.view.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST),
            )
            controller.view.layout(0, 0, 1080, controller.view.measuredHeight)
            controller.candidates
                .accessibilityNodeProvider
                .performAction(0, AccessibilityNodeInfo.ACTION_CLICK, null)
        }
        instrumentation.waitForIdleSync()
        assertEquals(listOf("去"), commits)
        instrumentation.runOnMainSync {
            controller.startInput(EditorPolicy(false, true, true, EditorInfo.IME_ACTION_DONE))
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { tapFirstLetter(controller) }
        instrumentation.waitForIdleSync()
        assertEquals(listOf("q"), backend.keys)
        assertEquals(listOf("去", "q"), commits)
        instrumentation.runOnMainSync {
            controller.retire()
            scope.cancel()
        }
    }

    /**
     * The keyboard is the content of a wrap-content IME window, so under an `AT_MOST` bound it
     * must be as tall as its column, not the bound. A greedy child made the window, its insets and its
     * touchable region the whole screen.
     */
    @Test fun keyboardMeasuresToItsColumnNotToTheAvailableHeight() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        instrumentation.runOnMainSync {
            val controller =
                KeyboardController(
                    instrumentation.targetContext,
                    scope,
                    KeyboardController.Callbacks(
                        commit = { _, _ -> true },
                        preedit = {},
                        delete = {},
                        moveCursor = {},
                        action = {},
                        error = { throw AssertionError(it) },
                    ),
                )
            controller.bind(RecordingBackend())
            controller.configure(EditorPolicy(false, false, false, EditorInfo.IME_ACTION_NONE))
            controller.startInput(EditorPolicy(false, false, false, EditorInfo.IME_ACTION_NONE))
            val bound = 4000
            controller.view.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(bound, View.MeasureSpec.AT_MOST),
            )
            controller.view.layout(0, 0, 1080, controller.view.measuredHeight)
            val column = controller.content.measuredHeight
            assertTrue("the column has no height", column > 0)
            assertTrue("the keyboard took the whole bound: $column", column < bound)
            assertEquals(column, controller.view.measuredHeight)
            // The popup layer still covers the column, so popups keep their drawing surface.
            assertEquals(column, controller.popupLayer.height)
            controller.retire()
        }
        scope.cancel()
    }

    private fun tapFirstLetter(controller: KeyboardController) {
        val width = 1080
        controller.view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST),
        )
        controller.view.layout(0, 0, width, controller.view.measuredHeight)
        val keyArea = controller.content.getChildAt(1)
        val x = width / 20f
        val y = keyArea.height / 8f
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + 30, MotionEvent.ACTION_UP, x, y, 0)
        keyArea.dispatchTouchEvent(down)
        keyArea.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
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
