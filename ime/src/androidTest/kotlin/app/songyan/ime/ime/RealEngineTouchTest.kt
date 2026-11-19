package app.songyan.ime.ime

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.HorizontalScrollView
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songyan.ime.ime.panels.QuickContentPanelView
import app.songyan.ime.layout.KeyboardComposition
import app.songyan.ime.layout.KeyboardCompositionKey
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutInput
import app.songyan.ime.layout.LayoutPageTarget
import app.songyan.ime.layout.LayoutRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Editor behaviour of the keyboard over the real engine and a real platform editor: keys go in
 * through the keyboard's own accessibility actions, text comes out through the same
 * [EditorConnection] wiring the input method service uses, and the engine is the shipped librime
 * with the bundled schemes. Selection reports, which only the system delivers to a live input
 * method, are fed in the order and with the values the framework sends them.
 *
 * Candidate taps and drifts, held keys, Delete and space touches, and caret dragging.
 */
@RunWith(AndroidJUnit4::class)
class RealEngineTouchTest : RealEngineEditorTestBase() {
    /** A press that drifts off its axis but stays on the word still picks it: only scrolling takes a touch. */
    @Test
    fun aCandidatePressThatDriftsOnTheWordStillCommitsIt() =
        withHost { host ->
            host.start()
            for (key in "ji") host.tap(key.toString())
            host.awaitComposing()
            val target = host.stripLabels().first()
            host.driftOnCandidate(target, dx = 0f, dy = host.touchSlop() * 1.5f)
            host.awaitField(target.substringBefore(' '))
        }

    /** A row that fits has nothing to scroll, so a sideways drift on a word is still a press on it. */
    @Test
    fun aSidewaysDriftOnARowThatFitsStillCommits() =
        withHost { host ->
            host.start()
            for (key in "nihao") host.tap(key.toString())
            host.awaitComposing()
            host.main { assertEquals("the row fits", 0f, host.find(CandidateBarView::class.java).maxScroll()) }
            val target = host.stripLabels().first()
            host.driftOnCandidate(target, dx = host.touchSlop() * 1.5f, dy = 0f)
            host.awaitField(target.substringBefore(' '))
        }

    @Test
    fun everyCandidateOfThePageIsReachable() =
        withHost { host ->
            // A narrow bar: the page holds more candidates than the row can show at once.
            host.start(width = NARROW_WIDTH_PX)
            host.tap("j")
            host.tap("i")
            host.awaitComposing()
            val page =
                host.backend
                    .currentSnapshot()
                    .candidates
                    .map { it.text }
            assertTrue("the page must overflow the row: $page", page.size >= MIN_OVERFLOW_PAGE)
            val labels = host.barLabels()
            for (text in page) {
                assertTrue(
                    "'$text' unreachable in $labels",
                    labels.any { it.substringBefore(' ') == text },
                )
            }
            host.tap(page.last(), bar = true, prefix = true)
            host.awaitField(page.last())
        }

    @Test
    fun theStripReadsLaterPagesAsItScrollsWithoutPagingTheEngine() =
        withHost(smallPages = true) { host ->
            // Five candidates for ji, two per engine page, in a row about one candidate wide: the
            // first page already runs past the row, so nothing more is read until the user scrolls.
            host.start(width = STRIP_WIDTH_PX)
            for (key in "ji") host.tap(key.toString())
            host.awaitComposing()
            val pageSize = host.backend.currentSnapshot().pageSize
            val first = host.stripLabels()
            assertEquals("only the first engine page is read before scrolling: $first", pageSize, first.size)
            repeat(STRIP_SWIPES) { host.swipeStrip() }
            val after = host.stripLabels()
            assertTrue("scrolling reads later pages: ${first.size} -> ${after.size}", after.size > first.size)
            assertEquals("what was shown stays in place", first, after.take(first.size))
            val engine = host.backend.currentSnapshot()
            assertEquals("reading never pages the engine", 0, engine.pageIndex)
            assertEquals("nor moves its highlight", 0, engine.highlightedIndex)
        }

    @Test
    fun aTouchOnACandidateFromALaterPageCommitsThatCandidate() =
        withHost(smallPages = true) { host ->
            host.start()
            for (key in "ji") host.tap(key.toString())
            host.awaitComposing()
            val pageSize = host.backend.currentSnapshot().pageSize
            val labels = host.stripLabels()
            val rank = labels.size - 1
            assertTrue("the target sits on the third engine page or later: $labels", rank >= 2 * pageSize)
            val target = labels[rank]
            host.touchCandidate(target)
            host.awaitField(target.substringBefore(' '))
        }

    @Test
    fun aTappedLaterPageCandidateSurvivesAnEnginePageKeyQueuedAheadOfIt() =
        withHost(smallPages = true) { host ->
            host.start()
            for (key in "ji") host.tap(key.toString())
            host.awaitComposing()
            val pageSize = host.backend.currentSnapshot().pageSize
            val labels = host.stripLabels()
            val rank = labels.size - 1
            assertTrue("the target sits on the third engine page or later: $labels", rank >= 2 * pageSize)
            val target = labels[rank]
            // The engine pages by itself before the tap is handled: the target is no longer on its
            // current page, but the strip's list is the same one.
            host.main {
                host.controller.enqueue { host.controller.session?.nextPage() }
                val bar = host.find(CandidateBarView::class.java)
                assertTrue(
                    bar.accessibilityNodeProvider.performAction(rank, AccessibilityNodeInfo.ACTION_CLICK, null),
                )
            }
            host.awaitIdle()
            host.awaitField(target.substringBefore(' '))
        }

    @Test
    fun accessibilityScrollingReachesLaterCandidates() =
        withHost(smallPages = true) { host ->
            host.start(width = STRIP_WIDTH_PX)
            for (key in "ji") host.tap(key.toString())
            host.awaitComposing()
            val first = host.stripLabels()
            assertTrue("a page is left to read: $first", first.size < JI_CANDIDATES)
            repeat(STRIP_SWIPES) {
                host.main {
                    val bar = host.find(CandidateBarView::class.java)
                    assertTrue(
                        "the strip offers a forward scroll",
                        bar.accessibilityNodeProvider.performAction(
                            View.NO_ID,
                            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                            null,
                        ),
                    )
                }
                host.awaitIdle()
            }
            val after = host.stripLabels()
            assertTrue("forward scrolls read later pages: ${first.size} -> ${after.size}", after.size > first.size)
        }

    @Test
    fun aSecondFingerTypesTheKeyTheFirstStillHolds() =
        withHost { host ->
            host.start()
            val n = host.keyCenter("n")
            val i = host.keyCenter("i")
            host.main {
                val start = SystemClock.uptimeMillis()
                host.touch(start, MotionEvent.ACTION_DOWN, n)
                host.touch(
                    start + 20,
                    MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    n,
                    i,
                )
                host.touch(
                    start + 40,
                    MotionEvent.ACTION_POINTER_UP or (0 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    n,
                    i,
                )
                host.touch(start + 60, MotionEvent.ACTION_UP, i, pointerId = 1)
            }
            host.awaitField("ni")
        }

    @Test
    fun aSpaceHeldWithoutMovingStillTypesASpace() =
        withHost { host ->
            host.start()
            val space = host.keyCenterSpace()
            val start = SystemClock.uptimeMillis()
            host.main { host.touch(start, MotionEvent.ACTION_DOWN, space) }
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + LONG_HOLD_MARGIN_MS)
            host.main { host.touch(SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, space, downTime = start) }
            host.awaitField(" ")
        }

    /**
     * A quick thumb tap on the space bar rolls a little up or down while it stays on the bar; that
     * tap must still confirm the composition, or the user has to press space a second time.
     */
    @Test
    fun aSpaceTapThatDriftsUpOrDownOnTheBarConfirmsTheComposition() =
        withHost(schema = "songyan_flypy") { host ->
            host.start()
            val density = host.context.resources.displayMetrics.density
            val bar = host.keyRectSpace()
            val drift = bar.height() * 0.35f
            assertTrue("the drift must pass the touch slop", drift > 8f * density)
            for (direction in listOf(-1f, 1f)) {
                val before = host.text()
                host.typeKeys("nihc")
                host.awaitComposing()
                val space = host.keyCenterSpace()
                val start = SystemClock.uptimeMillis()
                host.main {
                    host.touch(start, MotionEvent.ACTION_DOWN, space)
                    host.touch(
                        start + 16L,
                        MotionEvent.ACTION_MOVE,
                        space.first to space.second + direction * drift,
                        downTime = start,
                    )
                    host.touch(
                        start + 40L,
                        MotionEvent.ACTION_UP,
                        space.first to space.second + direction * drift,
                        downTime = start,
                    )
                }
                host.awaitField(before + "你好")
                assertFalse("one space ends the composition", host.editor.composing)
            }
        }

    @Test
    fun aHeldDeleteStopsOnceTheCompositionIsGone() =
        withHost { host ->
            host.start(initial = "abc")
            host.tap("n")
            host.tap("i")
            host.awaitField("abcni")
            val delete = host.keyCenterDelete()
            val start = SystemClock.uptimeMillis()
            host.main { host.touch(start, MotionEvent.ACTION_DOWN, delete) }
            // Long enough for many repeats: two letters are erased by the first two, the rest would eat "abc".
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 1_200L)
            host.main { host.touch(SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, delete, downTime = start) }
            host.awaitField("abc")
            // A new hold on text that is already committed deletes it again.
            val again = SystemClock.uptimeMillis()
            host.main { host.touch(again, MotionEvent.ACTION_DOWN, delete) }
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 1_200L)
            host.main { host.touch(SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, delete, downTime = again) }
            host.awaitField("")
        }

    @Test
    fun deleteActsOnItsDownAndTheReleaseAddsNothing() =
        withHost { host ->
            host.start(initial = "abc")
            BackspaceTrace.clear()
            val delete = host.keyCenterDelete()
            val start = SystemClock.uptimeMillis()
            host.main { host.touch(start, MotionEvent.ACTION_DOWN, delete) }
            host.awaitIdle()
            // Read once, well before the hold could start a repeat: only the DOWN can have deleted.
            val text = host.text()
            assertTrue("device too slow to tell the DOWN from the hold", SystemClock.uptimeMillis() - start < 300L)
            assertEquals("ab", text)
            host.main { host.touch(start + 80, MotionEvent.ACTION_UP, delete, downTime = start) }
            host.awaitIdle()
            assertEquals("ab", host.text())
            // The trace names the touch and the editor call, never the text.
            val events = BackspaceTrace.snapshot()
            assertEquals(
                listOf(
                    BackspaceTrace.Event.TOUCH_DOWN_DELETE,
                    BackspaceTrace.Event.EDITOR_DELETE,
                    BackspaceTrace.Event.EDITOR_DELETED_BEFORE_CURSOR,
                ),
                events.map { it.event },
            )
            assertTrue(events.last().cachedCaret)
            assertEquals(host.info.packageName, events.last().editorPackage)
        }

    @Test
    fun aSecondFingerEndsAHeldDeleteWithoutDeletingAgain() =
        withHost { host ->
            host.start(initial = "abc")
            val delete = host.keyCenterDelete()
            val n = host.keyCenter("n")
            host.main {
                val start = SystemClock.uptimeMillis()
                host.touch(start, MotionEvent.ACTION_DOWN, delete)
                host.touch(
                    start + 20,
                    MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    delete,
                    n,
                )
                host.touch(
                    start + 40,
                    MotionEvent.ACTION_POINTER_UP or (0 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    delete,
                    n,
                )
                host.touch(start + 60, MotionEvent.ACTION_UP, n, pointerId = 1)
            }
            host.awaitField("abn")
        }

    @Test
    fun aHoldWhoseDownErasedTheCompositionDeletesNothingMore() =
        withHost { host ->
            host.start(initial = "abc")
            host.tap("n")
            host.awaitField("abcn")
            val delete = host.keyCenterDelete()
            val start = SystemClock.uptimeMillis()
            host.main { host.touch(start, MotionEvent.ACTION_DOWN, delete) }
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 600L)
            host.main { host.touch(SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, delete, downTime = start) }
            host.awaitIdle()
            assertEquals("abc", host.text())
        }

    @Test
    fun aDeleteTapThatDriftsOnTheKeyStillDeletes() =
        withHost { host ->
            host.start(initial = "abc")
            driftedDelete(host)
            host.awaitField("ab")
        }

    @Test
    fun aDeleteTapThatDriftsOnTheKeyStillDeletesFromTheComposition() =
        withHost { host ->
            host.start(initial = "abc")
            host.tap("n")
            host.tap("i")
            host.awaitField("abcni")
            driftedDelete(host)
            host.awaitField("abcn")
        }

    @Test
    fun aDeleteHoldThatDriftsOnTheKeyStillRepeats() =
        withHost { host ->
            host.start(initial = "abcdefghij")
            driftedDelete(host, holdMs = ViewConfiguration.getLongPressTimeout() + 600L)
            // One delete when the hold starts, then one per repeat tick: several characters, not none.
            val left = host.text()
            assertTrue("held delete erased too little: '$left'", left.length <= 7)
        }

    @Test
    fun deleteReachesAnEditorWhoseConnectionLacksTheCodePointDelete() =
        withHost { host ->
            host.start(initial = "abc")
            // An app's own connection compiled without deleteSurroundingTextInCodePoints: the
            // framework swallows the AbstractMethodError, so the call does nothing at all.
            val real = host.connection
            host.connection =
                object : android.view.inputmethod.InputConnectionWrapper(real, false) {
                    override fun deleteSurroundingTextInCodePoints(
                        beforeLength: Int,
                        afterLength: Int,
                    ): Boolean = true
                }
            host.tapDelete()
            host.awaitField("ab")
        }

    @Test
    fun aSelectionTheEditorReportedIsWhatDeleteRemoves() =
        withHost { host ->
            host.start(initial = "abcdef")
            host.main {
                host.field.setSelection(1, 4)
                // The report the framework sends for the user's selection.
                host.editor.onUpdateSelection(6, 6, 1, 4, -1, -1)
            }
            host.tapDelete()
            host.awaitField("aef")
            // The caret the delete left behind is known again, and the next delete is plain.
            host.tapDelete()
            host.awaitField("ef")
        }

    @Test
    fun draggingTheSpaceBarMovesTheCaretOneCharacterPerStepOf24Dp() =
        withHost { host ->
            host.start(initial = "abcdefghij")
            val space = host.keyCenterSpace()
            val density = host.context.resources.displayMetrics.density
            val start = SystemClock.uptimeMillis()
            host.main {
                host.touch(start, MotionEvent.ACTION_DOWN, space)
                // Six slow 12 dp moves to the left: 72 dp, i.e. three characters at 24 dp each.
                for (step in 1..6) {
                    host.touch(
                        start + 16L * step,
                        MotionEvent.ACTION_MOVE,
                        space.first - 12f * density * step to space.second,
                        downTime = start,
                    )
                }
                host.touch(
                    start + 120L,
                    MotionEvent.ACTION_UP,
                    space.first - 72f * density to space.second,
                    downTime = start,
                )
            }
            host.awaitIdle()
            assertEquals("a slow 72 dp drag is three characters", 7, host.field.selectionStart)
            assertEquals("a drag never types a space", "abcdefghij", host.text())
        }

    @Test
    fun withTheChoiceOnASlideOverALetterKeyMovesTheCaretLikeTheSpaceBar() =
        withHost { host ->
            host.start(initial = "abcdefghij")
            host.main { host.controller.keyboard.cursorSwipeAnywhere = true }
            slideLeftOnKey(host, "l")
            assertEquals("a slow 72 dp slide is three characters", 7, host.field.selectionStart)
            assertEquals("the slide types nothing", "abcdefghij", host.text())
        }

    @Test
    fun withTheChoiceOnALanguageHoldKeepsItsMenuAndDoesNotMoveTheCaret() =
        withHost { host ->
            host.start(initial = "abcdefghij")
            host.main { host.controller.keyboard.cursorSwipeAnywhere = true }
            val key = host.keyCenterWhere { it.action.type == LayoutActionKind.LANGUAGE_SWITCH }
            val keyboard = host.controller.keyboard
            val start = SystemClock.uptimeMillis()
            host.main { host.touch(start, MotionEvent.ACTION_DOWN, key) }
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + LONG_HOLD_MARGIN_MS)
            host.main {
                assertTrue(host.controller.menuOpen)
                val end = key.first - 72f * host.density to key.second
                for (action in listOf(MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, end.first, end.second, 0)
                    keyboard.dispatchTouchEvent(event)
                    event.recycle()
                }
                host.relayout()
                val row = host.find(HorizontalScrollView::class.java)
                val down = SystemClock.uptimeMillis()
                for ((index, action) in listOf(
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_MOVE,
                    MotionEvent.ACTION_UP,
                ).withIndex()) {
                    val event =
                        MotionEvent.obtain(
                            down,
                            down + index * 32L,
                            action,
                            row.width * 0.7f - index * 36f * host.density,
                            row.height / 2f,
                            0,
                        )
                    row.dispatchTouchEvent(event)
                    event.recycle()
                }
                val rows = ArrayList<View>()
                host.controller.menu.findViewsWithText(
                    rows,
                    host.string(R.string.ime_menu_keyboard_settings),
                    View.FIND_VIEWS_WITH_TEXT,
                )
                assertTrue((rows.single().parent as View).performClick())
            }
            host.awaitIdle()
            assertTrue(host.main2 { host.controller.panels!!.isShowing })
            assertEquals("abcdefghij", host.text())
            assertEquals(10, host.field.selectionStart)
        }

    @Test
    fun withTheChoiceOnALetterHoldKeepsSidewaysAlternateSelection() =
        withHost { host ->
            host.start(initial = "abcdefghij")
            host.main {
                host.controller.keyboard.cursorSwipeAnywhere = true
                host.field.setSelection(5)
                host.report(oldStart = 10, newStart = 5, candidatesStart = -1, candidatesEnd = -1)
            }
            val key = host.keyCenter("b")
            val start = SystemClock.uptimeMillis()
            host.main { host.touch(start, MotionEvent.ACTION_DOWN, key) }
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + LONG_HOLD_MARGIN_MS)
            var alternate = ""
            host.main {
                val keyboard = host.controller.keyboard
                assertTrue(keyboard.popupOpen)
                assertTrue(keyboard.popupOptions.size >= 2)
                alternate = requireNotNull(keyboard.popupOptions[1].action.value)
                val direction = if (keyboard.popupMirrored) -1 else 1
                val dx = direction * keyboard.metrics.popupCellWidthDp * host.density
                val target = key.first + dx to key.second
                host.touch(SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, target, downTime = start)
                assertEquals(1, keyboard.popupIndex)
                assertEquals("selecting an alternate does not move the caret", 5, host.field.selectionStart)
                host.touch(SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, target, downTime = start)
            }
            host.awaitField("abcde" + alternate + "fghij")
            assertEquals(5 + alternate.length, host.field.selectionStart)
        }

    @Test
    fun withTheChoiceOnADiagonalKeyFlickKeepsItsTypingPriority() =
        withHost { host ->
            host.start(initial = "abcdefghij")
            host.main {
                host.controller.keyboard.cursorSwipeAnywhere = true
                host.field.setSelection(5)
                host.report(oldStart = 10, newStart = 5, candidatesStart = -1, candidatesEnd = -1)
            }
            val key = host.keyCenter("a")
            val end = key.first + 72f * host.density to key.second - 30f * host.density
            val start = SystemClock.uptimeMillis()
            host.main {
                host.touch(start, MotionEvent.ACTION_DOWN, key)
                host.touch(start + 16, MotionEvent.ACTION_MOVE, end, downTime = start)
                assertEquals(5, host.field.selectionStart)
                host.touch(start + 40, MotionEvent.ACTION_UP, end, downTime = start)
            }
            host.awaitField("abcde，fghij")
            assertEquals(6, host.field.selectionStart)
        }

    @Test
    fun withTheChoiceOnOpenPanelsKeepTheirDragsAndBlockCaretTakeover() {
        for (panelKind in listOf("settings", "symbols", "emoji", "symbol-page")) {
            withHost { host ->
                host.start(initial = "abcdefghij")
                host.main { host.controller.keyboard.cursorSwipeAnywhere = true }
                val key = host.keyCenter("l")
                val keyboard = host.controller.keyboard
                val start = SystemClock.uptimeMillis()
                host.main {
                    host.touch(start, MotionEvent.ACTION_DOWN, key)
                    when (panelKind) {
                        "settings" -> host.controller.openKeyboardSettingsPanel()
                        "symbols" -> host.controller.openSymbolPanel()
                        "emoji" -> host.controller.openEmojiPanel()
                        "symbol-page" -> host.controller.enqueueKey(LayoutAction.pageSwitch(LayoutPageTarget.SYMBOLS))
                    }
                }
                host.awaitIdle()
                host.main {
                    host.relayout()
                    for (action in listOf(MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL)) {
                        val event =
                            MotionEvent.obtain(
                                start,
                                SystemClock.uptimeMillis(),
                                action,
                                key.first - 72f * host.density,
                                key.second,
                                0,
                            )
                        keyboard.dispatchTouchEvent(event)
                        event.recycle()
                    }
                    // Only the quick-content panels render a view that takes touches directly;
                    // the settings and symbol pages are dispatched through the keyboard itself.
                    val panel =
                        runCatching { host.find(QuickContentPanelView::class.java) }.getOrNull()
                    val down = SystemClock.uptimeMillis()
                    for ((index, action) in listOf(
                        MotionEvent.ACTION_DOWN,
                        MotionEvent.ACTION_MOVE,
                        MotionEvent.ACTION_UP,
                    ).withIndex()) {
                        val event =
                            MotionEvent.obtain(
                                down,
                                down + index * 32L,
                                action,
                                key.first - index * 36f * host.density,
                                key.second - index * 20f * host.density,
                                0,
                            )
                        if (panel != null) {
                            assertTrue(panel.dispatchTouchEvent(event))
                        }
                        event.recycle()
                    }
                }
                host.awaitIdle()
                assertEquals(panelKind, "abcdefghij", host.text())
                assertEquals(panelKind, 10, host.field.selectionStart)
            }
        }
    }

    @Test
    fun anOverlayStopsAnInFlightCaretDragWithoutTypingOnRelease() =
        withHost { host ->
            host.start(initial = "abcdefghij")
            host.main { host.controller.keyboard.cursorSwipeAnywhere = true }
            val key = host.keyCenter("l")
            val keyboard = host.controller.keyboard
            val start = SystemClock.uptimeMillis()
            host.main {
                host.touch(start, MotionEvent.ACTION_DOWN, key)
                host.touch(
                    start + 16,
                    MotionEvent.ACTION_MOVE,
                    key.first - 24f * host.density to key.second,
                    downTime = start,
                )
            }
            host.awaitIdle()
            assertEquals(9, host.field.selectionStart)
            host.main { host.controller.openKeyboardSettingsPanel() }
            host.awaitIdle()
            host.main {
                val event =
                    MotionEvent.obtain(
                        start,
                        SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_MOVE,
                        key.first - 48f * host.density,
                        key.second,
                        0,
                    )
                keyboard.dispatchTouchEvent(event)
                event.recycle()
                host.controller.closePanels()
            }
            host.awaitToolbar()
            host.main {
                for (action in listOf(MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)) {
                    val event =
                        MotionEvent.obtain(
                            start,
                            SystemClock.uptimeMillis(),
                            action,
                            key.first - 72f * host.density,
                            key.second,
                            0,
                        )
                    keyboard.dispatchTouchEvent(event)
                    event.recycle()
                }
            }
            host.awaitIdle()
            assertEquals("abcdefghij", host.text())
            assertEquals(9, host.field.selectionStart)
        }

    @Test
    fun withTheChoiceOnAMergedKeySwipeStillTypesItsSecondLetterAtTheCaret() {
        for ((layout, pair, letter) in listOf(Triple("merged14", "bn", 'n'), Triple("merged18", "fg", 'g'))) {
            withHost(layout = layout, schema = "songyan_quanpin") { host ->
                host.start(initial = "abcdefghij")
                host.main {
                    host.controller.keyboard.cursorSwipeAnywhere = true
                    host.field.setSelection(5)
                    host.report(oldStart = 10, newStart = 5, candidatesStart = -1, candidatesEnd = -1)
                }
                host.swipe(pair, 1)
                host.awaitField("abcde" + letter + "fghij")
                assertEquals("only the typed letter advances the caret", 6, host.field.selectionStart)
            }
        }
    }

    @Test
    fun withTheChoiceOnAKana12SidewaysFlickStillTypesKanaAtTheCaret() {
        val prepared = KanaFixture.prepare()
        val previousCatalog = KeyboardLayoutCatalog.snapshot.value.registry
        try {
            val pairing = KeyboardCompositionKey(KanaFixture.SCHEMA, LayoutInput.Builtin.QWERTY26).rawValue
            KeyboardLayoutCatalog.install(
                LayoutRegistry.create(compositions = mapOf(pairing to KeyboardComposition(mainLayoutID = "kana12"))),
            )
            val backend = KanaFixture.open(prepared, "cursor-swipe-ownership")
            val host = Host(backend, layout = "qwerty26", schema = KanaFixture.SCHEMA)
            try {
                host.start(initial = "abcdefghij")
                assertEquals("kana12", host.main2 { host.controller.mainDefinition().id })
                host.main {
                    host.controller.keyboard.cursorSwipeAnywhere = true
                    host.field.setSelection(5)
                    host.report(oldStart = 10, newStart = 5, candidatesStart = -1, candidatesEnd = -1)
                }
                val label =
                    host.main2 {
                        val keyboard = host.controller.keyboard
                        keyboard.spokenLabels[keyboard.kana12RowIds.indexOf("na")]
                    }
                host.swipe(label, -1)
                host.awaitField("abcdeにfghij")
                assertEquals(
                    "に",
                    host.controller.session!!
                        .snapshot.rawInput,
                )
                assertEquals("only the typed kana advances the caret", 6, host.field.selectionStart)
            } finally {
                host.close()
                runBlocking { backend.shutdown() }
            }
        } finally {
            KeyboardLayoutCatalog.install(previousCatalog)
            KanaFixture.cleanup(prepared)
        }
    }

    @Test
    fun aSlideOverALetterKeyLeavesTheCaretAloneWhileTypingOrWithTheChoiceOff() =
        withHost { host ->
            host.start(initial = "abcdefghij")
            // Off by default: the slide is not a caret drag.
            slideLeftOnKey(host, "l")
            assertNotEquals("the choice is off by default", 7, host.field.selectionStart)
            host.discard()
            host.main { host.controller.keyboard.cursorSwipeAnywhere = true }
            host.tap("n")
            host.awaitComposing()
            val before = host.field.selectionStart
            slideLeftOnKey(host, "l")
            assertTrue("a slide while typing does not move the caret", host.field.selectionStart >= before)
        }

    @Test
    fun aFastSpaceDragNeedsLessTravelPerCharacterAndNeverSkipsOne() =
        withHost { host ->
            host.start(initial = "abcdefghijklmnop")
            val space = host.keyCenterSpace()
            val density = host.context.resources.displayMetrics.density
            val start = SystemClock.uptimeMillis()
            val moved = mutableListOf<Int>()
            host.main {
                host.touch(start, MotionEvent.ACTION_DOWN, space)
                // A fling: 24 dp every 8 ms, 96 dp in all; slow, that would be four characters.
                for (step in 1..4) {
                    host.touch(
                        start + 8L * step,
                        MotionEvent.ACTION_MOVE,
                        space.first - 24f * density * step to space.second,
                        downTime = start,
                    )
                }
                host.touch(
                    start + 60L,
                    MotionEvent.ACTION_UP,
                    space.first - 96f * density to space.second,
                    downTime = start,
                )
            }
            host.awaitIdle()
            moved += 16 - host.field.selectionStart
            assertTrue("a fast 96 dp drag moves more than four characters: $moved", moved.single() > 4)
            assertTrue("and no more than one per 12 dp: $moved", moved.single() <= 8)
            assertEquals("abcdefghijklmnop", host.text())
        }

    /** The row-shaped symbol page keeps its place like the 123 page and has no lock key of its own. */
    @Test
    fun theSymbolPageKeepsItsPlaceAfterASymbol() =
        withHost { host ->
            host.start()
            host.tapPage(LayoutPageTarget.NUMERIC)
            host.tapPage(LayoutPageTarget.SYMBOLS)
            host.tap("【")
            host.tap("】")
            host.awaitField("【】")
            assertEquals(0, host.keyCount { it.action.type == LayoutActionKind.SYMBOL_LOCK })
        }

    @Test
    fun aLeftSwipeOnDeleteSelectsThenDeletesOnRelease() =
        withHost { host ->
            host.start(initial = "abcdefgh")
            val finger = DeleteFinger(host)
            finger.down()
            // 14 dp to start, then three 24 dp steps; the press itself deleted the h.
            finger.moveTo(14f + 24f * 3)
            host.awaitIdle()
            assertEquals("abcdefg", host.text())
            assertEquals(4 to 7, host.selectionRange())
            finger.up()
            host.awaitField("abcd")
            assertEquals(4 to 4, host.selectionRange())
        }

    @Test
    fun slidingBackShrinksTheSelection() =
        withHost { host ->
            host.start(initial = "abcdefg")
            val finger = DeleteFinger(host)
            finger.down()
            finger.moveTo(14f + 24f * 3)
            host.awaitIdle()
            assertEquals(3, host.selectionRange().let { it.second - it.first })
            finger.moveTo(14f + 24f * 1)
            host.awaitIdle()
            assertEquals(2, host.selectionRange().let { it.second - it.first })
            finger.up()
            host.awaitField("abcd")
        }

    @Test
    fun releasingBackAtTheStartDeletesNothingMore() =
        withHost { host ->
            host.start(initial = "abcdefg")
            val finger = DeleteFinger(host)
            finger.down()
            finger.moveTo(14f + 24f * 3)
            host.awaitIdle()
            assertEquals(3, host.selectionRange().let { it.second - it.first })
            finger.moveTo(4f)
            host.awaitIdle()
            assertEquals("the selection is given back", 6 to 6, host.selectionRange())
            finger.up()
            host.awaitIdle()
            assertEquals("only the press deleted", "abcdef", host.text())
            assertEquals(6 to 6, host.selectionRange())
        }

    @Test
    fun aSwipeStopsTheRepeat() =
        withHost { host ->
            host.start(initial = "abcdefghij".repeat(30))
            val finger = DeleteFinger(host)
            finger.down()
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 300L)
            // No waiting for idle here: a held Delete keeps deleting until the field is empty.
            val repeated = host.text().length
            assertTrue("the hold was repeating: $repeated", repeated < 298)
            finger.moveTo(14f + 24f * 2)
            host.awaitIdle()
            val atSwipe = host.text().length
            SystemClock.sleep(500)
            host.awaitIdle()
            assertEquals("no repeat after the swipe began", atSwipe, host.text().length)
            assertEquals(2, host.selectionRange().let { it.second - it.first })
            finger.up()
            host.awaitIdle()
            assertEquals(atSwipe - 2, host.text().length)
        }

    @Test
    fun aSwipeWhileComposingKeepsDeletingFromTheComposition() =
        withHost { host ->
            host.start(initial = "xyz")
            for (key in "nihao") host.tap(key.toString())
            host.awaitComposing()
            val finger = DeleteFinger(host)
            finger.down()
            host.awaitIdle()
            val afterPress = host.text()
            finger.moveTo(14f + 24f * 3)
            host.awaitIdle()
            assertEquals("nothing is selected", host.selectionRange().let { it.first == it.second }, true)
            assertEquals("the swipe leaves the composition alone", afterPress, host.text())
            finger.up()
            host.awaitIdle()
            assertEquals("the release deletes nothing", afterPress, host.text())
            assertTrue("still composing", host.editor.composing)
        }

    @Test
    fun aSecondFingerCancelsTheSwipe() =
        withHost { host ->
            host.start(initial = "abcdefg")
            val finger = DeleteFinger(host)
            finger.down()
            finger.moveTo(14f + 24f * 3)
            host.awaitIdle()
            assertEquals(3, host.selectionRange().let { it.second - it.first })
            finger.secondFinger(host.keyCenter("n"))
            host.awaitIdle()
            assertEquals("nothing deleted", "abcdef", host.text())
            assertEquals("the caret is back", 6 to 6, host.selectionRange())
        }

    @Test
    fun aCancelRestoresTheCaret() =
        withHost { host ->
            host.start(initial = "abcdefg")
            val finger = DeleteFinger(host)
            finger.down()
            finger.moveTo(14f + 24f * 3)
            host.awaitIdle()
            assertEquals(3, host.selectionRange().let { it.second - it.first })
            finger.cancel()
            host.awaitIdle()
            assertEquals("abcdef", host.text())
            assertEquals(6 to 6, host.selectionRange())
        }

    @Test
    fun aSwipeNeverSplitsAnEmoji() =
        withHost { host ->
            val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"
            host.start(initial = "a$family$family")
            val finger = DeleteFinger(host)
            finger.down()
            host.awaitField("a$family")
            finger.moveTo(30f)
            host.awaitIdle()
            assertEquals(
                "one step selects the whole family",
                family.length,
                host.selectionRange().let {
                    it.second -
                        it.first
                },
            )
            finger.moveTo(14f + 24f * 2)
            host.awaitIdle()
            assertEquals(family.length + 1, host.selectionRange().let { it.second - it.first })
            finger.up()
            host.awaitField("")
        }

    @Test
    fun deleteSwipeIsOffUnderTouchExploration() =
        withHost { host ->
            host.start(initial = "abcdefg")
            host.main { host.find(KeyboardView::class.java).touchExplorationOverride = true }
            val finger = DeleteFinger(host)
            finger.down()
            finger.moveTo(14f + 24f * 3)
            host.awaitIdle()
            assertEquals("nothing is selected", 6 to 6, host.selectionRange())
            finger.up()
            host.awaitField("abcdef")
        }

    @Test
    fun aSwipeInAKeyEventEditorSelectsNothing() =
        withHost { host ->
            host.start(inputType = android.text.InputType.TYPE_NULL)
            val finger = DeleteFinger(host)
            finger.down()
            finger.moveTo(14f + 24f * 3)
            host.awaitIdle()
            finger.up()
            host.awaitIdle()
            assertEquals(
                "only the press's own Delete reached the editor: ${host.keyEvents}",
                listOf(KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_DEL),
                host.keyEvents.map { it.keyCode },
            )
        }
}
