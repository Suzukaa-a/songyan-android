package app.songyan.ime.ime

import android.content.ContentValues
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.provider.MediaStore
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.content.CommonPhrase
import app.songyan.ime.content.SymbolCatalog
import app.songyan.ime.ime.panels.QuickContentPanelContent
import app.songyan.ime.ime.panels.QuickContentPanelDataSource
import app.songyan.ime.keyboard.engine.EngineBackend
import app.songyan.ime.keyboard.engine.EngineSnapshot
import app.songyan.ime.keyboard.engine.ReadingRailRead
import app.songyan.ime.keyboard.engine.ReadingRailRequest
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutPageTarget
import app.songyan.ime.rime.AssetTrees
import app.songyan.ime.rime.RimeDeployment
import app.songyan.ime.rime.RimeEngine
import app.songyan.ime.rime.RimeEngineBackend
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import java.io.File

/**
 * Shared harness of the real-engine editor tests: one engine deployment per test process, the
 * editor host, and the key and gesture helpers. The tests themselves are split by topic.
 */
abstract class RealEngineEditorTestBase {
    protected val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /**
     * Commits 你 through the engine, so the pinned predictor offers 好 as its real post-commit
     * prediction: the fixture is a real scheme with a real predict.db, never a fabricated snapshot.
     */
    protected fun typePredictionContext(host: Host) {
        val before = host.text()
        host.tap("n")
        host.tap("i")
        host.awaitField(before + "ni")
        val you = host.barLabels().firstOrNull { it.startsWith("你") }
        assertTrue("the dictionary offers 你: " + host.barLabels(), you != null)
        host.tap(you!!, bar = true)
        host.awaitIdle()
        val snapshot = host.backend.currentSnapshot()
        assertEquals("only 你 was committed", before + "你", host.text())
        assertTrue("a post-commit prediction: " + snapshot, snapshot.isPrediction)
        assertFalse("prediction is not a composition", snapshot.isComposing)
        assertEquals("prediction shows no preedit", "", snapshot.preedit)
        assertTrue("the prediction offers 好: " + snapshot, snapshot.candidates.any { it.text == "好" })
    }

    protected class DeadEdits(
        target: InputConnection,
    ) : android.view.inputmethod.InputConnectionWrapper(target, true) {
        override fun setComposingText(
            text: CharSequence?,
            newCursorPosition: Int,
        ) = false

        override fun finishComposingText() = false

        override fun commitText(
            text: CharSequence?,
            newCursorPosition: Int,
        ) = false

        override fun deleteSurroundingText(
            beforeLength: Int,
            afterLength: Int,
        ) = false
    }

    /** A press on Delete whose finger rolls past the touch slop but lifts on the key itself. */
    protected fun driftedDelete(
        host: Host,
        holdMs: Long = 0L,
    ) {
        val delete = host.keyCenterDelete()
        val box = host.keyRectDelete()
        val drift = DRIFT_DP * host.density
        // Still well inside the key, yet past the 8 dp touch slop.
        assertTrue("delete key too narrow for the drift: $box", box.width() / 2f > drift + 2f)
        val moved = delete.first - drift to delete.second
        val start = SystemClock.uptimeMillis()
        host.main {
            host.touch(start, MotionEvent.ACTION_DOWN, delete)
            host.touch(start + 16, MotionEvent.ACTION_MOVE, moved, downTime = start)
        }
        if (holdMs > 0) SystemClock.sleep(holdMs)
        host.main { host.touch(SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, moved, downTime = start) }
        host.awaitIdle()
    }

    /** A slow sideways slide of 72 dp to the left from the centre of the key labelled [label]. */
    protected fun slideLeftOnKey(
        host: Host,
        label: String,
    ) {
        val key = host.keyCenter(label)
        val density = host.context.resources.displayMetrics.density
        val start = SystemClock.uptimeMillis()
        host.main {
            host.touch(start, MotionEvent.ACTION_DOWN, key)
            for (step in 1..6) {
                host.touch(
                    start + 16L * step,
                    MotionEvent.ACTION_MOVE,
                    key.first - 12f * density * step to key.second,
                    downTime = start,
                )
            }
            host.touch(start + 120L, MotionEvent.ACTION_UP, key.first - 72f * density to key.second, downTime = start)
        }
        host.awaitIdle()
    }

    protected fun flickKey(
        host: Host,
        label: String,
        dy: Float,
    ) {
        val key = host.keyCenter(label)
        val density = host.context.resources.displayMetrics.density
        val start = SystemClock.uptimeMillis()
        val end = key.first to key.second + dy * density
        host.main {
            host.touch(start, MotionEvent.ACTION_DOWN, key)
            host.touch(start + 16, MotionEvent.ACTION_MOVE, end, downTime = start)
            host.touch(start + 40, MotionEvent.ACTION_UP, end, downTime = start)
        }
    }

    /** Drags from [from]'s centre to [to]'s centre and lifts there. */
    protected fun dragKey(
        host: Host,
        from: String,
        to: String,
    ) {
        val start = host.keyCenter(from)
        val end = host.keyCenter(to)
        val down = SystemClock.uptimeMillis()
        host.main {
            host.touch(down, MotionEvent.ACTION_DOWN, start)
            host.touch(
                down + 16,
                MotionEvent.ACTION_MOVE,
                start.first to (start.second + end.second) / 2,
                downTime = down,
            )
            host.touch(down + 32, MotionEvent.ACTION_MOVE, end, downTime = down)
            host.touch(down + 48, MotionEvent.ACTION_UP, end, downTime = down)
        }
    }

    /** Picks a low-ranked candidate for `shi`, then types `shi` again; returns its rank before and after. */
    protected fun pickAndRetype(
        host: Host,
        retypeInOrdinaryField: Boolean = false,
    ): Pair<Int, Int> {
        for (key in "shi") host.tap(key.toString())
        host.awaitComposing()
        val expand = host.string(R.string.ime_expand)
        val labels = host.barLabels().filterNot { it == expand }
        val index = minOf(PICKED_RANK, labels.size - 1)
        val picked = labels[index].substringBefore(' ')
        host.tap(labels[index], bar = true)
        host.awaitField(picked)
        if (retypeInOrdinaryField) host.main { host.reconnect(imeOptions = 0) }
        host.awaitIdle()
        for (key in "shi") host.tap(key.toString())
        host.awaitComposing()
        val after = host.barLabels().indexOfFirst { it.substringBefore(' ') == picked }
        return index to (if (after < 0) Int.MAX_VALUE else after)
    }

    // ---- 14 / 18-key layouts: reading row and inline readings -----------------------------------

    protected fun typeFolded(
        host: Host,
        layout: String,
        letters: String,
    ) {
        for (letter in letters) host.tapFolded(layout, letter)
        host.awaitComposing()
    }

    // ---- harness -------------------------------------------------------------------------------

    protected fun <T> withHost(
        layout: String = "qwerty26",
        fontScale: Float = 1f,
        smallPages: Boolean = false,
        schema: String = "",
        readingPlacement: String = "row",
        smallestWidthDp: Int? = null,
        /** An asset tree under engine-fixtures/ deployed and opened instead of the builtin scheme. */
        fixtureAssetRoot: String? = null,
        /** Wraps the real backend so a prediction dismissal can be held before the engine answers. */
        pauseDismiss: Boolean = false,
        block: (Host) -> T,
    ): T {
        val user = File(root, "user-" + SystemClock.uptimeMillis())
        var fixture: File? = null
        var fixtureCompiled: File? = null
        val engineSource =
            if (fixtureAssetRoot != null) {
                val fixtureDir = File(root, "fixture-" + SystemClock.uptimeMillis())
                AssetTrees.copy(instrumentation.context.assets, fixtureAssetRoot, fixtureDir)
                val deployment = runBlocking { RimeDeployment.deploy(fixtureDir, user, appVersion = "test") }
                check(deployment.success) { "fixture deployment failed: " + deployment.lastError }
                fixture = fixtureDir
                fixtureCompiled = deployment.compiledDir
                fixtureDir
            } else if (smallPages) {
                smallPageDeployment.first
            } else {
                source
            }
        val engineCompiled = fixtureCompiled ?: if (smallPages) smallPageDeployment.second else compiled
        val backend =
            runBlocking {
                RimeEngineBackend.create(engineSource, user, engineCompiled, RimeEngine.DEFAULT_APP_NAME, "test")
            }
        val host =
            Host(
                if (pauseDismiss) PausedDismissBackend(backend) else backend,
                layout,
                fontScale,
                schema,
                readingPlacement,
                smallestWidthDp,
            )
        try {
            return block(host)
        } finally {
            try {
                host.close()
            } finally {
                runBlocking { backend.shutdown() }
                user.deleteRecursively()
                fixture?.deleteRecursively()
            }
        }
    }

    /**
     * The real backend with two test seams: a prediction dismissal or a reading rail read can be
     * held before the engine answers it. Snapshots and reads are always the engine's own; nothing
     * is fabricated.
     */
    protected class PausedDismissBackend(
        val inner: EngineBackend,
    ) : EngineBackend by inner {
        /** Set to hold the next rail read; [railHeld] completes once a read waits on it. */
        @Volatile
        var railGate: CompletableDeferred<Unit>? = null
        val railHeld = CompletableDeferred<Unit>()

        override suspend fun readReadingRail(request: ReadingRailRequest): ReadingRailRead {
            railGate?.let {
                railHeld.complete(Unit)
                it.await()
                railGate = null
            }
            return inner.readReadingRail(request)
        }

        /** Set to hold the next dismissal; cleared once it has been awaited. */
        @Volatile
        var gate: CompletableDeferred<Unit>? = null

        @Volatile
        var dismissals = 0

        override suspend fun dismissPrediction(): EngineSnapshot {
            dismissals++
            gate?.await()
            gate = null
            return inner.dismissPrediction()
        }
    }

    protected class Host(
        val backend: EngineBackend,
        layout: String,
        val fontScale: Float = 1f,
        schema: String = "",
        readingPlacement: String = "row",
        smallestWidthDp: Int? = null,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context: Context =
            instrumentation.targetContext.let { base ->
                if (fontScale == 1f && smallestWidthDp == null) {
                    base
                } else {
                    base.createConfigurationContext(
                        Configuration(base.resources.configuration).apply {
                            this.fontScale = this@Host.fontScale
                            // A tablet-class window: only these get the capped, docked key area.
                            smallestWidthDp?.let { smallestScreenWidthDp = it }
                        },
                    )
                }
            }
        val density: Float get() = context.resources.displayMetrics.density
        var hidden = false
            private set
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var field: EditText
        lateinit var connection: InputConnection
        lateinit var info: EditorInfo
        lateinit var controller: KeyboardController
        val editor = EditorConnection { connection }
        val preferences =
            FakePreferences(layout, schema).also {
                it.readingPlacement.value = readingPlacement
            }
        private var imeOptions = 0

        fun string(id: Int): String = context.getString(id)

        fun start(
            initial: String = "",
            imeOptions: Int = 0,
            width: Int = context.resources.displayMetrics.widthPixels,
            inputType: Int = InputType.TYPE_CLASS_TEXT,
            await: Boolean = true,
        ) {
            main {
                this.imeOptions = imeOptions
                field = EditText(context)
                field.inputType = inputType
                field.setText(initial)
                field.setSelection(initial.length)
                connect()
                controller = newController()
                hostWidth = width
                relayout()
                controller.bind(backend)
                beginInput(restarting = false)
            }
            if (await) awaitIdle()
        }

        private fun newController(): KeyboardController =
            KeyboardController(
                context,
                scope,
                editor.keyboardCallbacks(
                    // As the service does: a multi-line field takes the newline as text. This host's
                    // field is not in a window, so a key event would never reach it.
                    action = { editor.sendReturn(EditorPolicy.from(info).returnsNewline) },
                    onCommitted = { controller.onTextInserted() },
                    panelsChanged = { backOwned = it },
                    hideKeyboard = { hidden = true },
                ),
                preferences,
                quickContentDataSource = Phrases,
            )

        private var hostWidth = 0

        /** The host window changes width (a rotation or a window resize); the keyboard re-solves its rows. */
        fun resize(width: Int) {
            hostWidth = width
            main { relayout() }
            awaitIdle()
        }

        /** Clicks the [nth] key (0-based, in reading order) whose spoken label is [label]. */
        fun tapNth(
            label: String,
            nth: Int,
        ) {
            main {
                val view = find(KeyboardView::class.java)
                val indices = labels(view).withIndex().filter { matches(it.value, label) }.map { it.index }
                assertTrue("no key #$nth '$label'", nth in indices.indices)
                assertTrue(
                    view.accessibilityNodeProvider.performAction(
                        indices[nth],
                        AccessibilityNodeInfo.ACTION_CLICK,
                        null,
                    ),
                )
            }
            awaitIdle()
        }

        /** The host window's layout pass: the bar swaps with the toolbar and expands between frames. */
        fun relayout() {
            controller.view.measure(
                View.MeasureSpec.makeMeasureSpec(hostWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.AT_MOST),
            )
            controller.view.layout(0, 0, hostWidth, controller.view.measuredHeight)
        }

        private fun connect() {
            field.imeOptions = imeOptions
            info = EditorInfo()
            // A TextView offers no connection for TYPE_NULL; a terminal supplies a key-event one.
            val target =
                field.onCreateInputConnection(info)
                    ?: BaseInputConnection(field, false).also { info.inputType = InputType.TYPE_NULL }
            connection = Recording(target, keyEvents)
        }

        val keyEvents = mutableListOf<KeyEvent>()
        var backOwned = false

        private class Recording(
            target: InputConnection,
            private val events: MutableList<KeyEvent>,
        ) : android.view.inputmethod.InputConnectionWrapper(target, false) {
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                events += event
                return super.sendKeyEvent(event)
            }
        }

        fun keyCenter(label: String): Pair<Float, Float> {
            var center = 0f to 0f
            main {
                val view = find(KeyboardView::class.java)
                val labels = labels(view)
                val index = labels.indexOfFirst { matches(it, label) }
                assertTrue("no key '$label' in $labels", index >= 0)
                val bounds = Rect()
                view.accessibilityNodeProvider.createAccessibilityNodeInfo(index)!!.getBoundsInParent(bounds)
                center = bounds.exactCenterX() to bounds.exactCenterY()
            }
            return center
        }

        fun keyRect(label: String): Rect {
            val bounds = Rect()
            main {
                val view = find(KeyboardView::class.java)
                val index = labels(view).indexOfFirst { matches(it, label) }
                assertTrue("no key '$label'", index >= 0)
                view.accessibilityNodeProvider.createAccessibilityNodeInfo(index)!!.getBoundsInParent(bounds)
            }
            return bounds
        }

        /**
         * The drawn key whose action matches: the face of an automatic control follows the input
         * language, what the key does does not. Its index and the provider's node share one key list.
         */
        private fun requireKey(
            view: KeyboardView,
            match: (LayoutKey) -> Boolean,
        ): Int {
            val index = view.keys.indexOfFirst(match)
            assertTrue("no key matching the action in ${view.labels}", index >= 0)
            return index
        }

        fun keyCount(match: (LayoutKey) -> Boolean): Int {
            var count = 0
            main { count = find(KeyboardView::class.java).keys.count(match) }
            return count
        }

        /** Taps a key by what it does, so a face the input language renames cannot move the target. */
        fun tapKeyWhere(match: (LayoutKey) -> Boolean) {
            main {
                val view = find(KeyboardView::class.java)
                val index = requireKey(view, match)
                assertTrue(
                    view.accessibilityNodeProvider.performAction(index, AccessibilityNodeInfo.ACTION_CLICK, null),
                )
            }
            awaitIdle()
        }

        fun keyCenterWhere(match: (LayoutKey) -> Boolean): Pair<Float, Float> {
            var center = 0f to 0f
            main {
                val view = find(KeyboardView::class.java)
                val bounds = Rect()
                view.accessibilityNodeProvider
                    .createAccessibilityNodeInfo(requireKey(view, match))!!
                    .getBoundsInParent(bounds)
                center = bounds.exactCenterX() to bounds.exactCenterY()
            }
            return center
        }

        fun keyRectWhere(match: (LayoutKey) -> Boolean): Rect {
            val bounds = Rect()
            main {
                val view = find(KeyboardView::class.java)
                view.accessibilityNodeProvider
                    .createAccessibilityNodeInfo(requireKey(view, match))!!
                    .getBoundsInParent(bounds)
            }
            return bounds
        }

        /** The keyboard's own controls, located by action rather than by a renamed face. */
        fun tapSpace() = tapKeyWhere { it.action.type == LayoutActionKind.SPACE }

        fun tapDelete() = tapKeyWhere { it.action.type == LayoutActionKind.BACKSPACE }

        fun tapEnter() = tapKeyWhere { it.action.type == LayoutActionKind.ENTER }

        fun tapLanguageKey() = tapKeyWhere { it.action.type == LayoutActionKind.LANGUAGE_SWITCH }

        fun tapClearControl() = tapKeyWhere { it.action.type == LayoutActionKind.CLEAR_INPUT }

        fun tapPage(target: LayoutPageTarget) =
            tapKeyWhere { it.action.type == LayoutActionKind.PAGE_SWITCH && it.action.pageSwitchTarget == target }

        /** The symbol page's 更多 key opens the symbol panel: a mode switch, not page data. */
        fun tapSymbolPanelKey() =
            tapKeyWhere {
                it.action.type == LayoutActionKind.MODE_SWITCH && it.action.value == KeyboardPages.SYMBOL_PANEL_MODE
            }

        fun keyCenterDelete() = keyCenterWhere { it.action.type == LayoutActionKind.BACKSPACE }

        fun keyRectDelete() = keyRectWhere { it.action.type == LayoutActionKind.BACKSPACE }

        fun keyCenterSpace() = keyCenterWhere { it.action.type == LayoutActionKind.SPACE }

        fun keyRectSpace() = keyRectWhere { it.action.type == LayoutActionKind.SPACE }

        /** One touch event on the key area; pointers are listed by id. */
        fun touch(
            time: Long,
            action: Int,
            vararg points: Pair<Float, Float>,
            pointerId: Int = 0,
            downTime: Long = time,
        ) {
            val ids = if (points.size == 1) intArrayOf(pointerId) else IntArray(points.size) { it }
            val properties =
                Array(points.size) { index -> MotionEvent.PointerProperties().apply { id = ids[index] } }
            val coords =
                Array(points.size) { index ->
                    MotionEvent.PointerCoords().apply {
                        x = points[index].first
                        y = points[index].second
                        pressure = 1f
                        size = 1f
                    }
                }
            val event =
                MotionEvent.obtain(
                    downTime,
                    time,
                    action,
                    points.size,
                    properties,
                    coords,
                    0,
                    0,
                    1f,
                    1f,
                    0,
                    0,
                    0,
                    0,
                )
            find(KeyboardView::class.java).dispatchTouchEvent(event)
            event.recycle()
        }

        /**
         * Scrolls the reading rail by one slot with a real upward drag on its last visible reading:
         * the rail shows only a window, and a longer spelling can sit just below it.
         */
        fun scrollReadingRailUp() {
            // The column scrolls at once under the finger, so wait for the readings past the
            // window to have landed; until then there is nothing to scroll to.
            val ready = SystemClock.uptimeMillis() + TIMEOUT_MS
            var scrollable = false
            while (!scrollable && SystemClock.uptimeMillis() < ready) {
                main { scrollable = find(KeyboardView::class.java).railCanScroll(forward = true) }
                if (!scrollable) SystemClock.sleep(POLL_MS)
            }
            assertTrue("the reading column has nothing below its window", scrollable)
            var start = 0f to 0f
            var slot = 0f
            main {
                val view = find(KeyboardView::class.java)
                val reading = view.railIndices.filter { view.keys[it].label.isNotBlank() }.maxOrNull() ?: -1
                assertTrue("no visible reading on ${view.labels}", reading >= 0)
                val hit = Rect()
                view.accessibilityNodeProvider.createAccessibilityNodeInfo(reading)!!.getBoundsInParent(hit)
                // The column follows the finger by pixels; a little over one slot reveals the next reading.
                val drawn = view.bounds[reading]
                start = hit.exactCenterX() to hit.exactCenterY()
                slot = drawn.bottom - drawn.top
            }
            val down = SystemClock.uptimeMillis()
            main {
                touch(down, MotionEvent.ACTION_DOWN, start)
                // The first move arms the drag; the second travels a little over one slot, and the
                // release selects nothing.
                touch(down + 16, MotionEvent.ACTION_MOVE, start.first to start.second - slot * 0.5f, downTime = down)
                touch(down + 32, MotionEvent.ACTION_MOVE, start.first to start.second - slot * 1.2f, downTime = down)
                touch(down + 48, MotionEvent.ACTION_UP, start.first to start.second - slot * 1.2f, downTime = down)
            }
            awaitIdle()
        }

        /** Height of the band above the key area: the toolbar or the candidates, whichever shows. */
        fun bandHeight(): Int {
            var height = 0
            main {
                relayout()
                height = controller.content.getChildAt(0).height
            }
            return height
        }

        fun <R> main2(block: () -> R): R {
            var result: Any? = null
            main { result = block() }
            @Suppress("UNCHECKED_CAST")
            return result as R
        }

        fun <V : View> findOrNullOnMain(type: Class<V>): V? = main2 { findOrNull(type) }

        fun candidateTextPx(): Float =
            KeyboardPalette.from(context).tokens.candidateTextSizeSp * density * minOf(fontScale, MAX_FONT_SCALE)

        fun chipPaddingPx(): Float = KeyboardPalette.from(context).tokens.candidateChipPaddingDp * density

        /** The toolbar is on screen again (a closing panel animates out before it hands the band back). */
        fun awaitToolbar() {
            val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
            while (toolbarLabelsNow().isEmpty() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(POLL_MS)
            main { assertTrue("no toolbar", toolbarLabelsNow().isNotEmpty()) }
        }

        fun tapToolbar(label: String) {
            main {
                val view = find(ToolbarView::class.java)
                val index = labels(view).indexOfFirst { it == label }
                assertTrue("no toolbar entry '$label' in ${labels(view)}", index >= 0)
                assertTrue(
                    view.accessibilityNodeProvider.performAction(index, AccessibilityNodeInfo.ACTION_CLICK, null),
                )
            }
            awaitIdle()
        }

        /** Types [code] and picks each of [picks] from the bar in turn, so the engine learns their join. */
        fun learn(
            code: String,
            picks: List<String>,
        ) {
            for (key in code) tap(key.toString())
            awaitComposing()
            for (pick in picks) {
                tap(pick, bar = true)
                awaitIdle()
            }
        }

        private fun barItemCenter(label: String): Pair<Float, Float> {
            var center = 0f to 0f
            main {
                val view = find(CandidateBarView::class.java)
                val provider = view.accessibilityNodeProvider
                val labels = labels(view)
                val index = labels.indexOfFirst { it == label || it.startsWith("$label ") }
                assertTrue("no item '$label' in $labels", index >= 0)
                val bounds = Rect()
                provider.createAccessibilityNodeInfo(index)!!.getBoundsInParent(bounds)
                center = bounds.exactCenterX() to bounds.exactCenterY()
            }
            return center
        }

        /** Holds a candidate or fixed control, then lifts without moving. */
        fun longPressBarItem(
            text: String,
            holdMs: Long = ViewConfiguration.getLongPressTimeout() + LONG_HOLD_MARGIN_MS,
        ) {
            val center = barItemCenter(text)
            lastBarPoint = center
            val start = SystemClock.uptimeMillis()
            main { touchBar(start, MotionEvent.ACTION_DOWN, center, start) }
            SystemClock.sleep(holdMs)
            main { touchBar(SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, center, start) }
            awaitIdle()
        }

        fun cancelBarPress(
            label: String,
            cancelAction: Int,
        ) {
            val center = barItemCenter(label)
            val start = SystemClock.uptimeMillis()
            main { touchBar(start, MotionEvent.ACTION_DOWN, center, start) }
            awaitIdle()
            val point = if (cancelAction == MotionEvent.ACTION_MOVE) -1f to -1f else center
            main { touchBar(SystemClock.uptimeMillis(), cancelAction, point, start) }
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + LONG_HOLD_MARGIN_MS)
            main { touchBar(SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, center, start) }
            awaitIdle()
        }

        private var lastBarPoint = 0f to 0f

        /** A plain tap where the last long press happened (the word's old place on the bar). */
        fun tapBarAt(
            @Suppress("UNUSED_PARAMETER") text: String,
        ) {
            val point = lastBarPoint
            val start = SystemClock.uptimeMillis()
            main {
                touchBar(start, MotionEvent.ACTION_DOWN, point, start)
                touchBar(start + 30, MotionEvent.ACTION_UP, point, start)
            }
        }

        /** Drags a finger up the expanded grid by [upBy] px in [steps] moves, like a scroll. */
        fun dragBar(
            upBy: Float,
            steps: Int,
        ) {
            val x = hostWidth * 0.4f
            val startY = EXPANDED_DRAG_START_Y * density
            val start = SystemClock.uptimeMillis()
            main { touchBar(start, MotionEvent.ACTION_DOWN, x to startY, start) }
            for (step in 1..steps) {
                SystemClock.sleep(DRAG_STEP_MS)
                main {
                    touchBar(
                        SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_MOVE,
                        x to startY - upBy * step / steps,
                        start,
                    )
                }
            }
            main { touchBar(SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x to startY - upBy, start) }
            awaitIdle()
        }

        /** A short, fast flick followed immediately by a tap on the named candidate or control. */
        fun flingBarThenTap(label: String) {
            val target = barItemCenter(label)
            main {
                val bar = find(CandidateBarView::class.java)
                val x = bar.width * 0.5f
                val y = bar.height * 0.5f
                val start = SystemClock.uptimeMillis()
                touchBar(start, MotionEvent.ACTION_DOWN, x to y, start)
                for (step in 1..3) {
                    touchBar(start + step * 8, MotionEvent.ACTION_MOVE, x - step * 10 * density to y, start)
                }
                touchBar(start + 32, MotionEvent.ACTION_UP, x - 30 * density to y, start)
                touchBar(start + 40, MotionEvent.ACTION_DOWN, target, start + 40)
                touchBar(start + 56, MotionEvent.ACTION_UP, target, start + 40)
            }
        }

        /** Swipes the collapsed strip toward its end by four tenths of the host's width. */
        fun swipeStrip() {
            var y = 0f
            main { y = find(CandidateBarView::class.java).height / 2f }
            // From inside the strip, never from the expand control at its end.
            val from = hostWidth * 0.4f
            val to = 0f
            val start = SystemClock.uptimeMillis()
            main { touchBar(start, MotionEvent.ACTION_DOWN, from to y, start) }
            for (step in 1..STRIP_SWIPE_STEPS) {
                SystemClock.sleep(DRAG_STEP_MS * 2)
                main {
                    touchBar(
                        SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_MOVE,
                        from + (to - from) * step / STRIP_SWIPE_STEPS to y,
                        start,
                    )
                }
            }
            // Held still before lifting: a plain drag, not a fling that runs past the reads.
            SystemClock.sleep(DRAG_STEP_MS * 6)
            main { touchBar(SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, to to y, start) }
            main { touchBar(SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, to to y, start) }
            awaitIdle()
        }

        /**
         * Draws the keyboard into `Pictures/songyan-test/[name].png` (MediaStore, so it outlives the
         * test app) when the run passes the instrumentation argument `songyanScreenshots=true`;
         * otherwise nothing. Used to look at a layout case, not to assert anything.
         */
        fun saveScreenshot(name: String) {
            if (InstrumentationRegistry.getArguments().getString("songyanScreenshots") != "true") return
            main {
                val view = controller.view
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                val values =
                    ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/songyan-test")
                    }
                val resolver = context.contentResolver
                val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
                resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }

        /** The candidate labels of the strip, without its expand control. */
        fun stripLabels(): List<String> {
            val expand = string(R.string.ime_expand)
            return barLabels().filterNot { it == expand }
        }

        fun touchSlop(): Float =
            android.view.ViewConfiguration
                .get(context)
                .scaledTouchSlop
                .toFloat()

        /** Presses the candidate [label], slides the finger by ([dx], [dy]) in small steps, and lifts it there. */
        fun driftOnCandidate(
            label: String,
            dx: Float,
            dy: Float,
        ) {
            val center = barItemCenter(label)
            val start = SystemClock.uptimeMillis()
            main { touchBar(start, MotionEvent.ACTION_DOWN, center, start) }
            for (step in 1..DRIFT_STEPS) {
                SystemClock.sleep(DRAG_STEP_MS)
                val point = center.first + dx * step / DRIFT_STEPS to center.second + dy * step / DRIFT_STEPS
                main { touchBar(SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, point, start) }
            }
            main {
                touchBar(
                    SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_UP,
                    center.first + dx to center.second + dy,
                    start,
                )
            }
            awaitIdle()
        }

        /** A real finger tap on the candidate [label], brought into view first the way TalkBack focus does. */
        fun touchCandidate(label: String) {
            main {
                val view = find(CandidateBarView::class.java)
                val index = labels(view).indexOfFirst { it == label }
                assertTrue("no item '$label'", index >= 0)
                view.accessibilityNodeProvider.performAction(
                    index,
                    AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
                    null,
                )
            }
            awaitIdle()
            val center = barItemCenter(label)
            main {
                val bar = find(CandidateBarView::class.java)
                assertTrue("'$label' is on screen at $center", center.first > 0f && center.first < bar.width)
            }
            val start = SystemClock.uptimeMillis()
            main {
                touchBar(start, MotionEvent.ACTION_DOWN, center, start)
                touchBar(start + 30, MotionEvent.ACTION_UP, center, start)
            }
            awaitIdle()
        }

        private fun touchBar(
            time: Long,
            action: Int,
            point: Pair<Float, Float>,
            downTime: Long,
        ) {
            val event = MotionEvent.obtain(downTime, time, action, point.first, point.second, 0)
            find(CandidateBarView::class.java).dispatchTouchEvent(event)
            event.recycle()
        }

        /** The candidate labels, or none while the bar is idle and the toolbar is showing instead. */
        fun barLabelsNow(): List<String> = findOrNull(CandidateBarView::class.java)?.let(::labels).orEmpty()

        fun toolbarLabelsNow(): List<String> = findOrNull(ToolbarView::class.java)?.let(::labels).orEmpty()

        /** What the service does in onStartInput / onStartInputView. */
        private fun beginInput(restarting: Boolean) {
            val keep = editor.startInput(info, restarting)
            val policy = EditorPolicy.from(info)
            controller.configure(policy)
            controller.startInput(policy, keep)
        }

        /** onStartInput(restarting = true) on the same field, cursor where it is. */
        fun restart() {
            info = EditorInfo()
            connection = requireNotNull(field.onCreateInputConnection(info))
            beginInput(restarting = true)
        }

        /**
         * What the service goes through when the input method itself restarts for a configuration
         * change (rotation, dark mode, font scale): onStartInput(restarting = true) with the very
         * EditorInfo of the running input, whose initial selection is stale, then a new keyboard view
         * bound to the engine, then onStartInputView.
         */
        fun restartForConfiguration() {
            val keep = editor.startInput(info, restarting = true)
            val policy = EditorPolicy.from(info)
            controller.startInput(policy, keep)
            val old = controller
            controller = newController()
            old.retire()
            relayout()
            controller.bind(backend, keepComposition = editor.composing)
            controller.configure(policy)
            controller.startInput(policy, keep)
        }

        /**
         * Focus moves to another field of the same window, as on a web page: the old field finishes
         * its composition itself when it loses focus, and the new one starts on the same connection
         * (restarting = true). Returns the field that was left.
         */
        fun switchField(
            restarting: Boolean = true,
            initialText: String = "",
        ): EditText {
            val left = field
            connection.finishComposingText()
            field = EditText(context)
            field.inputType = InputType.TYPE_CLASS_TEXT
            if (initialText.isNotEmpty()) {
                field.setText(initialText)
                field.setSelection(initialText.length)
            }
            connect()
            beginInput(restarting = restarting)
            return left
        }

        fun finishInput() {
            editor.finishInput(info)
            controller.finishInput()
        }

        /** A fresh connection to the same field with its text and marked span untouched. */
        fun reconnectKeepingText() {
            connect()
            beginInput(restarting = false)
        }

        /** The same field (same id, same text) starts a new input, as after a recreation. */
        fun reconnect(imeOptions: Int = this.imeOptions) {
            this.imeOptions = imeOptions
            connect()
            beginInput(restarting = false)
        }

        /** onUpdateSelection as the framework delivers it to the service. */
        fun report(
            oldStart: Int,
            newStart: Int,
            candidatesStart: Int,
            candidatesEnd: Int,
        ) {
            if (editor.onUpdateSelection(oldStart, oldStart, newStart, newStart, candidatesStart, candidatesEnd)) {
                controller.discardComposition()
            }
        }

        fun text(): String {
            var value = ""
            main { value = field.text.toString() }
            return value
        }

        fun tap(
            label: String,
            bar: Boolean = false,
            prefix: Boolean = false,
        ) {
            main {
                val view: View = if (bar) find(CandidateBarView::class.java) else find(KeyboardView::class.java)
                val provider = view.accessibilityNodeProvider
                val labels = labels(view)
                val index =
                    labels.indexOfFirst { if (prefix) it.startsWith(label) else matches(it, label) }
                assertTrue(
                    "no item '$label' in $labels; engine ${backend.currentSnapshot().let {
                        listOf(
                            it.candidates.map { c ->
                                c.text
                            },
                            it.pageSize,
                            it.isLastPage,
                            it.schemaID,
                            it.rawInput,
                            it.preedit,
                        )
                    }}",
                    index >= 0,
                )
                assertTrue(provider.performAction(index, AccessibilityNodeInfo.ACTION_CLICK, null))
            }
            awaitIdle()
        }

        /** Clicks the key [keyLabel] and the bar word [word] in one main-thread turn, before either is processed. */
        fun tapKeyThenCandidate(
            keyLabel: String,
            word: String,
        ) {
            main {
                val keys = find(KeyboardView::class.java)
                val keyLabels = labels(keys)
                val keyIndex = keyLabels.indexOfFirst { matches(it, keyLabel) }
                assertTrue("no key '$keyLabel'", keyIndex >= 0)
                val bar = find(CandidateBarView::class.java)
                val barIndex = labels(bar).indexOfFirst { it == word || it.startsWith("$word ") }
                assertTrue("no item '$word' in ${labels(bar)}", barIndex >= 0)
                assertTrue(
                    keys.accessibilityNodeProvider.performAction(keyIndex, AccessibilityNodeInfo.ACTION_CLICK, null),
                )
                assertTrue(
                    bar.accessibilityNodeProvider.performAction(barIndex, AccessibilityNodeInfo.ACTION_CLICK, null),
                )
            }
        }

        /** A phone-pad key by its digit (the corner hint is part of its spoken label). */
        fun tapKeyFor(digit: Char) {
            var label = ""
            main {
                label = labels(find(KeyboardView::class.java)).first { it.split(' ').contains(digit.toString()) }
            }
            tap(label)
        }

        /** Sends each of [keys] the way its key does, apostrophes included (26-key pages have no key for it). */
        fun typeKeys(keys: String) {
            for (key in keys) {
                main { controller.enqueueKey(LayoutAction.code(key.toString())) }
                awaitIdle()
            }
        }

        /** Taps the key that sends the letter [letter] typed on [layout]'s keys (its fold target). */
        fun tapFolded(
            layout: String,
            letter: Char,
        ) {
            val fold = KeyboardLayoutKind.fromWire(layout)!!.compactFold!!
            val code = fold.map[letter] ?: letter
            if (code.isDigit()) return tapKeyFor(code)
            var label = ""
            main {
                label =
                    labels(find(KeyboardView::class.java)).first {
                        it.length <= 2 && it.contains(code) && it.all { c -> c in 'a'..'z' }
                    }
            }
            tap(label)
        }

        /** Waits until a key reads [label]: the rail's engine queries land a moment after the key. */
        fun awaitKey(label: String) {
            val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
            while (label !in keyLabels() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(POLL_MS)
            assertTrue("no key '$label' in ${keyLabels()}", label in keyLabels())
        }

        /** A sideways drag of 30 dp on the key labelled [label]; negative [direction] is left. */
        fun swipe(
            label: String,
            direction: Int,
        ) {
            val key = keyCenter(label)
            val dx = direction * 30f * density
            val start = SystemClock.uptimeMillis()
            main {
                touch(start, MotionEvent.ACTION_DOWN, key)
                touch(start + 16, MotionEvent.ACTION_MOVE, key.first + dx / 2 to key.second, downTime = start)
                touch(start + 32, MotionEvent.ACTION_MOVE, key.first + dx to key.second, downTime = start)
                touch(start + 60, MotionEvent.ACTION_UP, key.first + dx to key.second, downTime = start)
            }
        }

        fun discard() {
            main { controller.discardComposition() }
            awaitIdle()
        }

        /** The reading row's items as TalkBack reads them; empty while it is idle or not on screen. */
        fun readingRowLabels(): List<String> {
            var labels = emptyList<String>()
            main { labels = findOrNull(ReadingRowView::class.java)?.let(::labels).orEmpty() }
            return labels
        }

        /** The reading row's height in px, or 0 when it is not part of the layout. */
        fun readingRowHeight(): Int = main2 { findOrNull(ReadingRowView::class.java)?.height ?: 0 }

        /** The whole top band (reading row, if any, plus candidates or toolbar). */
        fun topBandHeight(): Int = bandHeight()

        /** The inline readings of the candidate row, in the bar's own item order. */
        fun inlineReadingLabels(): List<String> =
            barLabels().filter { it.startsWith(readingPrefix()) || it.startsWith(spellingPrefix()) }

        fun readingPrefix(): String = string(R.string.ime_reading_pick).substringBefore("%1\$s")

        fun spellingPrefix(): String = string(R.string.ime_reading_lock).substringBefore("%1\$s")

        fun pickReadingLabel(reading: String): String = context.getString(R.string.ime_reading_pick, reading)

        fun awaitReadingRow(label: String) = awaitUntil("reading row item '$label'") { label in readingRowLabels() }

        fun awaitInlineReading(label: String) = awaitUntil("inline reading '$label'") { label in barLabels() }

        fun awaitUntil(
            what: String,
            condition: () -> Boolean,
        ) {
            val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
            while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(POLL_MS)
            assertTrue("no $what; row ${readingRowLabels()} bar ${barLabels()}", condition())
        }

        /** Clicks the reading-row item [label] the way TalkBack does. */
        fun tapReadingRow(label: String) {
            main {
                val view = find(ReadingRowView::class.java)
                val index = labels(view).indexOf(label)
                assertTrue("no reading '$label' in ${labels(view)}", index >= 0)
                assertTrue(
                    view.accessibilityNodeProvider.performAction(index, AccessibilityNodeInfo.ACTION_CLICK, null),
                )
            }
            awaitIdle()
        }

        /** A finger on the very corner of the reading-row cell [label]: the whole cell is the target, not the glyphs. */
        fun touchReadingRowCorner(label: String) {
            main {
                val view = find(ReadingRowView::class.java)
                val index = labels(view).indexOf(label)
                assertTrue("no reading '$label' in ${labels(view)}", index >= 0)
                val bounds = Rect()
                view.accessibilityNodeProvider.createAccessibilityNodeInfo(index)!!.getBoundsInParent(bounds)
                val x = bounds.left + 2f
                val y = bounds.bottom - 2f
                val down = SystemClock.uptimeMillis()
                view.dispatchTouchEvent(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
                view.dispatchTouchEvent(MotionEvent.obtain(down, down + 30, MotionEvent.ACTION_UP, x, y, 0))
            }
            awaitIdle()
        }

        /** The item [label]'s bounds on the candidate bar, in the bar's coordinates. */
        fun barItemBounds(label: String): Rect {
            val rect = Rect()
            main {
                val view = find(CandidateBarView::class.java)
                val index = labels(view).indexOf(label)
                assertTrue("no item '$label' in ${labels(view)}", index >= 0)
                view.accessibilityNodeProvider.createAccessibilityNodeInfo(index)!!.getBoundsInParent(rect)
            }
            return rect
        }

        fun keyLabels(): List<String> {
            var labels = emptyList<String>()
            main { labels = labels(find(KeyboardView::class.java)) }
            return labels
        }

        fun barLabels(): List<String> {
            var labels = emptyList<String>()
            main { labels = barLabelsNow() }
            return labels
        }

        private fun matches(
            spoken: String,
            label: String,
        ): Boolean = spoken.equals(label, ignoreCase = true)

        fun labelsOf(view: View): List<String> = labels(view)

        /**
         * Clicks a panel cell spoken as [label], as a screen reader does. The click is queued only:
         * a caller that holds the work it starts must not wait for idle here.
         */
        fun tapPanelCell(
            type: Class<out View>,
            label: String,
        ) {
            main {
                val view = find(type)
                val spoken = labels(view)
                val index = spoken.indexOfFirst { matches(it, label) }
                assertTrue("no '" + label + "' in " + spoken, index >= 0)
                assertTrue(
                    "the panel cell answered",
                    view.accessibilityNodeProvider.performAction(index, AccessibilityNodeInfo.ACTION_CLICK, null),
                )
            }
        }

        private fun labels(view: View): List<String> {
            val provider = view.accessibilityNodeProvider
            val result = ArrayList<String>()
            var id = 0
            while (true) {
                val node = provider.createAccessibilityNodeInfo(id) ?: break
                result += (node.contentDescription ?: node.text ?: "").toString()
                id++
                if (id > 400) break
            }
            return result
        }

        fun <V : View> find(type: Class<V>): V = requireNotNull(findOrNull(type)) { "no visible ${type.simpleName}" }

        fun <V : View> findOrNull(type: Class<V>): V? {
            relayout()

            fun search(view: View): V? {
                if (type.isInstance(view) && view.isShown(controller.view)) return type.cast(view)
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) search(view.getChildAt(index))?.let { return it }
                }
                return null
            }
            return search(controller.view)
        }

        private fun View.isShown(root: View): Boolean {
            var current: View? = this
            while (current != null) {
                if (current.visibility != View.VISIBLE) return false
                if (current === root) return true
                current = current.parent as? View
            }
            return true
        }

        fun awaitField(expected: String) {
            awaitIdle()
            val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
            while (text() != expected && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(POLL_MS)
            assertEquals(expected, text())
        }

        fun awaitComposing() {
            val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
            while (!(editor.composing && barLabels().isNotEmpty()) && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(POLL_MS)
            }
            awaitIdle()
            assertTrue("not composing", editor.composing)
        }

        /** The engine has answered every queued key: nothing changes for a few polls. */
        fun awaitIdle() {
            var last = ""
            var stable = 0
            val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
            while (stable < STABLE_POLLS && SystemClock.uptimeMillis() < deadline) {
                instrumentation.waitForIdleSync()
                SystemClock.sleep(POLL_MS)
                var state = ""
                var ready = false
                main {
                    state = field.text.toString() + "|" + barLabelsNow()
                    ready = controller.ready && !controller.preparing
                }
                // Initial engine preparation can leave the text unchanged while the layout is still pending.
                if (ready && state == last) stable++ else stable = 0
                last = state
            }
        }

        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

        fun close() =
            main {
                if (::controller.isInitialized) controller.retire()
                scope.cancel()
            }
    }

    class FakePreferences(
        layout: String,
        schema: String = "",
    ) : KeyboardPreferencesSource {
        override val layoutId = MutableStateFlow(layout)
        override val activeSchemaId = MutableStateFlow(schema)
        override val readingPlacement = MutableStateFlow("row")
        override val shuangpinHints = MutableStateFlow<Map<Char, String>>(emptyMap())
        override val menuHiddenSchemas = MutableStateFlow<Set<String>>(emptySet())
        override val widePosition = MutableStateFlow(50)
        override val keyFlickDirection = MutableStateFlow(FlickDirection.UP.wire)

        override suspend fun setLayoutId(id: String) {
            layoutId.value = id
        }

        override suspend fun setActiveSchemaId(id: String) {
            activeSchemaId.value = id
        }
    }

    protected object Phrases : QuickContentPanelDataSource {
        override suspend fun loadContent() =
            QuickContentPanelContent(SymbolCatalog(), emptyList(), listOf(CommonPhrase("。")), emptyList(), false)

        // The panel's symbol library is empty here, so no use is ever recorded.
        override suspend fun recordRecentSymbol(text: String): List<String> = emptyList()

        override suspend fun deleteClipboardEntry(text: String) = Unit

        override suspend fun clearRecentSymbols() = Unit
    }

    companion object {
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 40L
        const val STABLE_POLLS = 3
        const val NARROW_WIDTH_PX = 360
        const val LONG_HOLD_MARGIN_MS = 300L

        /** Finger roll on a tap: past the 8 dp touch slop, inside any Delete key. */
        const val DRIFT_DP = 12f
        const val MIN_OVERFLOW_PAGE = 4
        const val PICKED_RANK = 4
        const val BUILTIN_ASSET_ROOT = "rime/builtin"

        /** This module's own prediction fixture (a real predict.db and the schema that mounts it). */
        const val PREDICTION_FIXTURE = "engine-fixtures/prediction"
        const val PREDICTION_SCHEMA = "prediction_probe"
        const val MAX_FONT_SCALE = 1.3f
        const val LAST_OF_FIVE = 4
        const val EXPANDED_DRAG_PX = 700f
        const val EXPANDED_DRAG_STEPS = 8
        const val EXPANDED_DRAG_START_Y = 200f
        const val DRAG_STEP_MS = 16L
        const val STRIP_SWIPES = 3

        /** About one candidate cell of strip beside the expand control on a phone-density screen. */
        const val STRIP_WIDTH_PX = 240
        const val DRIFT_STEPS = 6

        /** A row where two inline readings already take more than half of the strip. */
        const val CROWDED_WIDTH_PX = 540

        /** The test dictionary has five candidates for ji. */
        const val JI_CANDIDATES = 5
        const val STRIP_SWIPE_STEPS = 8

        lateinit var root: File
        lateinit var source: File
        lateinit var compiled: File

        /**
         * One deployment for the test process, shared by every class built on this harness; every
         * test gets its own learning directory. A previous run's files are cleared on first use.
         */
        @BeforeClass
        @JvmStatic
        fun deploy() {
            if (this::compiled.isInitialized) return
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            root = File(context.cacheDir, "real-engine-editor")
            root.deleteRecursively()
            source = File(root, "source")
            AssetTrees.copy(context.assets, BUILTIN_ASSET_ROOT, source)
            val deployment =
                runBlocking {
                    RimeDeployment.deploy(
                        sharedDataDir = source,
                        userDataDir = File(root, "deploy"),
                        appName = RimeEngine.DEFAULT_APP_NAME,
                        appVersion = "test",
                    )
                }
            assertTrue("deployment failed: ${deployment.lastError}", deployment.success)
            assertNotEquals(0, deployment.schemaIds.size)
            compiled = deployment.compiledDir
        }

        /**
         * The same scheme with two candidates per page, deployed once on first use: the shipped
         * dictionary is too small for a candidate list that runs past a page of five.
         */
        val smallPageDeployment: Pair<File, File> by lazy {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val small = File(root, "source-small")
            AssetTrees.copy(context.assets, BUILTIN_ASSET_ROOT, small)
            val defaults = File(small, "default.yaml")
            val patched = defaults.readText().replace("page_size: 5", "page_size: 2")
            assertNotEquals("the page size line moved", defaults.readText(), patched)
            defaults.writeText(patched)
            val deployment =
                runBlocking {
                    RimeDeployment.deploy(
                        sharedDataDir = small,
                        userDataDir = File(root, "deploy-small"),
                        appName = RimeEngine.DEFAULT_APP_NAME,
                        appVersion = "test",
                    )
                }
            assertTrue("deployment failed: ${deployment.lastError}", deployment.success)
            small to deployment.compiledDir
        }
    }
}
