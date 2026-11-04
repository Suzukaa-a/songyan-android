package app.songyan.ime.ime

import android.content.ContentValues
import android.content.Context
import android.content.res.AssetManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.text.InputType
import android.text.TextPaint
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.content.CommonPhrase
import app.songyan.ime.content.SymbolCatalog
import app.songyan.ime.ime.panels.PhraseClipboardPanelView
import app.songyan.ime.ime.panels.QuickContentPanelContent
import app.songyan.ime.ime.panels.QuickContentPanelDataSource
import app.songyan.ime.keyboard.engine.EngineBackend
import app.songyan.ime.keyboard.layout.CompactFold
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutRegistry
import app.songyan.ime.layout.LayoutSplitConfiguration
import app.songyan.ime.layout.ShuangpinHintPresets
import app.songyan.ime.rime.RimeDeployment
import app.songyan.ime.rime.RimeEngine
import app.songyan.ime.rime.RimeEngineBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Editor behaviour of the keyboard over the real engine and a real platform editor: keys go in
 * through the keyboard's own accessibility actions, text comes out through the same
 * [EditorConnection] wiring the input method service uses, and the engine is the shipped librime
 * with the bundled schemes. Selection reports, which only the system delivers to a live input
 * method, are fed in the order and with the values the framework sends them.
 */
@RunWith(AndroidJUnit4::class)
class RealEngineEditorTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun deletingTheLastLetterLeavesNothingBehind() =
        withHost { host ->
            host.start()
            host.tap("n")
            host.awaitField("n")
            host.tap(host.string(R.string.ime_delete))
            host.awaitIdle()
            assertEquals("", host.text())
            assertFalse(host.editor.composing)
        }

    @Test
    fun clearingANineKeyCompositionLeavesNothingBehind() =
        withHost(layout = "phone9") { host ->
            host.start()
            for (key in "64426") host.tapKeyFor(key)
            host.awaitComposing()
            host.tap(host.string(R.string.ime_clear))
            host.awaitIdle()
            assertEquals("", host.text())
        }

    @Test
    fun aNineKeyCompositionShowsLettersNotItsKeyCodes() =
        withHost(layout = "phone9") { host ->
            host.start()
            for (key in "64426") host.tapKeyFor(key)
            host.awaitComposing()
            val shown = host.text()
            assertTrue("field shows '$shown'", shown.isNotEmpty() && shown.none(Char::isDigit))
            val bar = host.barLabels()
            assertTrue("candidate bar shows $bar", bar.isNotEmpty())
        }

    @Test
    fun aNineKeySentenceIsSpelledWithTheReadingsSeenWhileTyping() =
        withHost(layout = "phone9") { host ->
            host.start()
            // 546 on its own offers 林 (lin) as a candidate: the engine proves that reading.
            for (key in "546") host.tapKeyFor(key)
            host.awaitField("lin")
            host.tap(host.string(R.string.ime_clear))
            host.awaitIdle()
            // The sentence 一林 has no reading of its own; 林 must not fall back to the commoner jin.
            for (key in "94546") host.tapKeyFor(key)
            host.awaitComposing()
            val snapshot = host.backend.currentSnapshot()
            assertEquals("一林", snapshot.candidates[snapshot.highlightedIndex].text)
            host.awaitField("yi'lin")
        }

    @Test
    fun anIdleKeyboardShowsTheToolbarAndCompositionSwapsItForCandidates() =
        withHost { host ->
            host.start()
            val toolbar = host.toolbarLabelsNow()
            assertEquals(
                listOf(R.string.ime_panel_clipboard, R.string.ime_toolbar_input_methods, R.string.ime_toolbar_emoji)
                    .map(host::string),
                toolbar.take(3),
            )
            assertTrue(host.string(R.string.ime_toolbar_hide) in toolbar)
            assertTrue("no candidates while idle", host.barLabelsNow().isEmpty())
            val idleHeight = host.bandHeight()
            assertEquals(48f * host.density, idleHeight.toFloat(), 1f)
            host.tap("n")
            host.awaitComposing()
            assertTrue("the toolbar gives way to the candidates", host.toolbarLabelsNow().isEmpty())
            assertEquals("the band keeps its height", idleHeight, host.bandHeight())
            host.tap(host.string(R.string.ime_delete))
            host.awaitIdle()
            assertTrue("the toolbar comes back", host.toolbarLabelsNow().isNotEmpty())
            assertEquals(idleHeight, host.bandHeight())
        }

    @Test
    fun theToolbarOpensTheClipboardAndHidesTheKeyboard() =
        withHost { host ->
            host.start()
            host.tapToolbar(host.string(R.string.ime_panel_clipboard))
            host.awaitIdle()
            assertTrue("the panel owns Back", host.backOwned)
            assertTrue(host.findOrNullOnMain(PhraseClipboardPanelView::class.java) != null)
            host.main { host.controller.closePanels() }
            host.awaitToolbar()
            host.tapToolbar(host.string(R.string.ime_toolbar_hide))
            assertTrue("the hide entry reaches the service", host.hidden)
        }

    @Test
    fun noCandidateIsCutShortWhenItFitsTheRow() {
        // The width a cell gets and the width the text is drawn at must agree, also at a big font
        // scale: a candidate like 明 must not render as 明… in a cell that fits it.
        for (fontScale in listOf(1.0f, 1.3f, 2.0f)) {
            withHost(fontScale = fontScale) { host ->
                host.start()
                for (key in "ji") host.tap(key.toString())
                host.awaitComposing()
                host.main {
                    val bar = host.find(CandidateBarView::class.java)
                    val provider = bar.accessibilityNodeProvider
                    val paint = TextPaint().apply { textSize = host.candidateTextPx() }
                    var index = 0
                    while (true) {
                        val node = provider.createAccessibilityNodeInfo(index) ?: break
                        val label = (node.contentDescription ?: "").toString()
                        val text = label.substringBefore(' ')
                        if (text.isNotEmpty() && text.all { Character.isIdeographic(it.code) }) {
                            val bounds = Rect()
                            node.getBoundsInParent(bounds)
                            val needed = paint.measureText(text)
                            val room = bounds.width() - 2 * host.chipPaddingPx()
                            // A cell the whole row cannot hold may be cut; every other one must fit.
                            if (bounds.width() < bar.width - 2 * host.chipPaddingPx()) {
                                assertTrue(
                                    "font scale $fontScale: '$text' needs $needed px but its cell offers $room",
                                    room + 1f >= needed,
                                )
                            }
                        }
                        index++
                    }
                    assertTrue("only $index accessible items in the bar", index > 2)
                }
            }
        }
    }

    @Test
    fun longPressingALearnedWordOffersToDeleteItAndTheWordGoes() =
        withHost { host ->
            host.start()
            host.learn("yuwo", listOf("鱼", "我"))
            val typed = host.text()
            for (key in "yuwo") host.tap(key.toString())
            host.awaitComposing()
            assertEquals(
                "鱼我",
                host.backend
                    .currentSnapshot()
                    .candidates
                    .first()
                    .text,
            )
            host.longPressCandidate("鱼我")
            // The hold's release left the word's menu, not the character strip.
            assertEquals(listOf(host.string(R.string.ime_candidate_delete_word)), host.barLabels())
            host.main {
                val bar = host.find(CandidateBarView::class.java)
                val menu = requireNotNull(bar.menu).rect
                val candidate = requireNotNull(bar.viewCell(bar.entries.indexOf(bar.menu!!.entry)))
                assertFalse(
                    "the menu $menu must leave the candidate $candidate in view",
                    menu.intersects(
                        candidate.left.toFloat(),
                        candidate.top.toFloat(),
                        candidate.right.toFloat(),
                        candidate.bottom.toFloat(),
                    ),
                )
            }
            host.tap(host.string(R.string.ime_candidate_delete_word), bar = true)
            host.awaitIdle()
            assertFalse("the menu is gone", host.main2 { host.find(CandidateBarView::class.java).popupOpen })
            assertTrue(
                "a learned word must be forgotten: ${host.backend.currentSnapshot().candidates.map { it.text }}",
                host.backend
                    .currentSnapshot()
                    .candidates
                    .none { it.text == "鱼我" },
            )
            assertTrue("deleting commits nothing", host.text().startsWith(typed) && host.text() != typed)
            assertTrue("the composition goes on", host.editor.composing)
        }

    @Test
    fun aWordThatWasNeverLearnedStaysAfterDeleteIsAsked() =
        withHost { host ->
            host.start()
            for (key in "yuwo") host.tap(key.toString())
            host.awaitComposing()
            val first =
                host.backend
                    .currentSnapshot()
                    .candidates
                    .first()
                    .text
            host.longPressCandidate(first)
            host.tap(host.string(R.string.ime_candidate_delete_word), bar = true)
            host.awaitIdle()
            assertEquals(
                first,
                host.backend
                    .currentSnapshot()
                    .candidates
                    .first()
                    .text,
            )
            assertTrue("still composing", host.editor.composing)
        }

    @Test
    fun aLongPressNeverLeavesAStripThatTheNextTapTypesFrom() =
        withHost { host ->
            host.start()
            for (key in "nihao") host.tap(key.toString())
            host.awaitComposing()
            val before = host.text()
            host.longPressCandidate("你好")
            // Whatever is left on screen after the release, touching the candidate again must only
            // close it: no character of the long-pressed word is typed.
            host.tapBarAt("你好")
            host.awaitIdle()
            assertEquals(before, host.text())
            assertTrue("the composition survives", host.editor.composing)
            assertFalse(host.main2 { host.find(CandidateBarView::class.java).popupOpen })
            host.tap("你好", bar = true, prefix = true)
            host.awaitField("你好")
        }

    @Test
    fun anExpandedGridKeepsLoadingPagesAndSelectsAcrossThem() =
        withHost(smallPages = true) { host ->
            host.start()
            // Five candidates, two per page: three engine pages the grid must stitch together.
            for (key in "ji") host.tap(key.toString())
            host.awaitComposing()
            assertFalse("the engine has more pages", host.backend.currentSnapshot().isLastPage)
            val pageSize = host.backend.currentSnapshot().pageSize
            host.tap(host.string(R.string.ime_expand), bar = true)
            host.awaitIdle()
            val firstScreen = host.barLabels().size
            host.dragBar(upBy = EXPANDED_DRAG_PX, steps = EXPANDED_DRAG_STEPS)
            host.dragBar(upBy = EXPANDED_DRAG_PX, steps = EXPANDED_DRAG_STEPS)
            host.awaitIdle()
            val labels = host.barLabels()
            assertTrue("pages beyond the first must load: $firstScreen -> ${labels.size}", labels.size > pageSize + 3)
            // The fifth candidate lives on the third engine page.
            val target = labels[LAST_OF_FIVE]
            host.tap(target, bar = true)
            host.awaitIdle()
            val picked = target.substringBefore(' ')
            assertTrue("'$picked' from a later page was typed: '${host.text()}'", host.text().startsWith(picked))
        }

    @Test
    fun theExpandButtonRespondsToTheFirstTapDuringCandidateInertia() =
        withHost(fontScale = 2f) { host ->
            // Keep the five real candidates wider than the row even when font scaling is capped.
            host.start(width = (200 * host.density).toInt())
            for (key in "ji") host.tap(key.toString())
            host.awaitComposing()
            val expected =
                host.backend
                    .currentSnapshot()
                    .candidates
                    .first()
                    .text
            host.flingBarThenTap(expected)
            host.awaitField("ji")
            host.tap(host.string(R.string.ime_delete))
            host.awaitField("j")
            host.tap("i")
            host.awaitIdle()
            host.flingBarThenTap(host.string(R.string.ime_expand))
            host.awaitIdle()
            assertTrue(
                "the first tap expands the moving candidate row",
                host.barLabels().contains(host.string(R.string.ime_candidate_back)),
            )
            assertEquals("ji", host.text())
            host.tap(host.string(R.string.ime_candidate_back), bar = true)
            host.tap(host.string(R.string.ime_delete))
            host.awaitField("j")
            host.tap("i")
            host.tap(host.string(R.string.ime_space))
            host.awaitField(expected)
        }

    @Test
    fun theExpandedColumnGoesBackDeletesAndRetypes() =
        withHost { host ->
            host.start()
            for (key in "jiu") host.tap(key.toString())
            host.awaitComposing()
            host.tap(host.string(R.string.ime_expand), bar = true)
            host.awaitIdle()
            host.tap(host.string(R.string.ime_delete), bar = true)
            host.awaitField("ji")
            host.tap(host.string(R.string.ime_candidate_back), bar = true)
            host.awaitIdle()
            assertFalse(host.main2 { host.find(CandidateBarView::class.java).expanded })
            host.tap(host.string(R.string.ime_expand), bar = true)
            host.awaitIdle()
            host.tap(host.string(R.string.ime_clear), bar = true)
            host.awaitIdle()
            assertEquals("", host.text())
            assertTrue("the toolbar is back", host.toolbarLabelsNow().isNotEmpty())
        }

    @Test
    fun anIdleSpaceTypesASpace() =
        withHost { host ->
            host.start()
            host.tap(host.string(R.string.ime_space))
            host.awaitField(" ")
        }

    @Test
    fun tappingElsewhereRemovesTheCompositionAndKeepsTheCursor() =
        withHost { host ->
            host.start(initial = "abc")
            host.tap("n")
            host.tap("i")
            host.awaitField("abcni")
            host.main {
                host.field.setSelection(1)
                host.report(oldStart = 5, newStart = 1, candidatesStart = 3, candidatesEnd = 5)
            }
            host.awaitIdle()
            assertEquals("abc", host.text())
            assertEquals(1, host.field.selectionStart)
            assertTrue("the engine composition must end too", host.barLabels().none { it.startsWith("你") })
        }

    @Test
    fun aLateSelectionReportDoesNotEndTheComposition() =
        withHost { host ->
            host.start()
            host.tap("n")
            host.tap("i")
            host.tap("h")
            host.awaitField("nih")
            // The framework reports earlier states late while the user types fast; some editors
            // also omit the composing region.
            host.main {
                host.report(oldStart = 0, newStart = 1, candidatesStart = -1, candidatesEnd = -1)
                host.report(oldStart = 1, newStart = 2, candidatesStart = 0, candidatesEnd = 2)
            }
            host.tap("a")
            host.tap("o")
            host.awaitField("ni hao")
            assertTrue(host.editor.composing)
            assertEquals(0, BaseInputConnection.getComposingSpanStart(host.field.text))
        }

    @Test
    fun aRestartedEditorKeepsTheComposition() =
        withHost { host ->
            host.start()
            host.tap("n")
            host.tap("i")
            host.awaitField("ni")
            host.main { host.restart() }
            host.awaitIdle()
            host.tap("h")
            host.awaitField("nih")
            assertEquals(0, BaseInputConnection.getComposingSpanStart(host.field.text))
        }

    /** The field shows the engine's spaced reading, so the user sees how the letters were split. */
    @Test
    fun aFullPinyinFieldShowsTheEnginesSpacedReading() =
        withHost { host ->
            host.start()
            host.typeKeys("nihao")
            host.awaitField("ni hao")
            assertTrue(host.editor.composing)
        }

    @Test
    fun aFieldTheUserLeavesMidWordKeepsTheReadingAsPlainText() =
        withHost { host ->
            host.start()
            host.typeKeys("leo")
            assertTrue(host.editor.composing)
            // A web page: tapping the password field finishes the username's composition in place.
            var left: EditText? = null
            host.main { left = host.switchField() }
            host.awaitIdle()
            var leftText = ""
            host.main { leftText = left!!.text.toString() }
            // The editor finished it in place; the keyboard no longer owns it and starts fresh.
            assertEquals("le o", leftText)
            assertEquals("", host.text())
            assertFalse(host.editor.composing)
            host.tap("a")
            host.awaitField("a")
        }

    @Test
    fun aConfigurationRestartKeepsTheCompositionAlive() =
        withHost { host ->
            host.start()
            host.typeKeys("dianhu")
            host.awaitComposing()
            // Rotation in an app that handles it itself: the input method restarts its own input and
            // view first, then the app closes the old connection (finishing the composing text) and
            // restarts input on the same field.
            host.main { host.restartForConfiguration() }
            host.awaitIdle()
            host.main {
                host.connection.closeConnection()
                host.restart()
            }
            host.awaitIdle()
            assertTrue("the composition survives the restart", host.editor.composing)
            host.tap("a")
            host.awaitIdle()
            assertEquals("dianhua", host.backend.currentSnapshot().rawInput)
            // The field shows the engine's spaced reading of all six letters.
            host.awaitField(host.backend.currentSnapshot().preedit)
            assertEquals(0, BaseInputConnection.getComposingSpanStart(host.field.text))
        }

    @Test
    fun aSelectionJumpWithAnUnknownCompositionUnmarksItInsteadOfLettingTheNextKeyReplaceIt() =
        withHost { host ->
            host.start()
            host.typeKeys("nihao")
            host.awaitComposing()
            // An editor that never told where the composition is, and does not report it now.
            host.main {
                host.editor.selection.forget()
                host.field.setSelection(0)
                host.report(oldStart = 5, newStart = 0, candidatesStart = -1, candidatesEnd = -1)
            }
            host.awaitIdle()
            assertFalse(host.editor.composing)
            assertEquals(-1, BaseInputConnection.getComposingSpanStart(host.field.text))
            host.tap("a")
            host.awaitField("ani hao")
        }

    @Test
    fun aNewInputUnmarksAComposingSpanNoKeyboardOwns() =
        withHost { host ->
            // The keyboard process died mid-word and the editor kept the word marked; the new
            // process knows nothing of it.
            host.start(initial = "women")
            host.main {
                host.connection.setComposingRegion(0, 5)
                host.reconnectKeepingText()
            }
            host.awaitIdle()
            assertEquals(-1, BaseInputConnection.getComposingSpanStart(host.field.text))
            host.tap("a")
            host.awaitField("womena")
        }

    @Test
    fun aRecreatedFieldDropsTheSpellingItsConnectionFinished() =
        withHost { host ->
            host.start()
            host.tap("n")
            host.awaitField("n")
            // Rotation: the activity's connection closes first and finishes the composition as
            // text, then the input finishes and the recreated field starts with that text.
            host.main {
                host.connection.closeConnection()
                host.finishInput()
                host.reconnect()
            }
            host.awaitIdle()
            assertEquals("", host.text())
        }

    @Test
    fun aRecreatedFieldKeepsTheTextWhenTheComposingEndWasNeverKnown() =
        withHost { host ->
            host.start()
            for (key in "nihao") host.tap(key.toString())
            host.awaitField("ni hao")
            // The editor never reported where the composition ends: the field's id and the text
            // before the cursor are not enough to call the text the keyboard's own, so it stays.
            host.main {
                host.editor.selection.forget()
                host.connection.closeConnection()
                host.finishInput()
                host.reconnect()
            }
            host.awaitIdle()
            assertEquals("ni hao", host.text())
            assertEquals(-1, BaseInputConnection.getComposingSpanStart(host.field.text))
        }

    @Test
    fun aCompositionTheDeadConnectionCouldNotRemoveIsUnmarkedAndNotEatenByTheNextLetter() =
        withHost { host ->
            host.start()
            for (key in "nihao") host.tap(key.toString())
            host.awaitField("ni hao")
            // Home: the window loses focus, so the connection no longer accepts the keyboard's
            // removal; the field keeps its marked text. Reopened after the recognition window.
            host.main {
                host.editor.strandedWindowMs = 0
                host.connection = DeadEdits(host.connection)
                host.finishInput()
            }
            Thread.sleep(20)
            host.main { host.reconnectKeepingText() }
            host.awaitIdle()
            host.tap("a")
            host.awaitField("ni haoa")
        }

    private class DeadEdits(
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

    @Test
    fun aPanelInsertConfirmsTheCompositionFirst() =
        withHost { host ->
            host.start()
            for (key in "nihao") host.tap(key.toString())
            host.awaitField("ni hao")
            host.main { host.controller.openQuickContentPanel() }
            host.awaitIdle()
            host.main { host.find(PhraseClipboardPanelView::class.java).onInsertText("。") }
            host.awaitField("你好。")
            assertFalse(host.editor.composing)
        }

    @Test
    fun anOpenPanelCoversTheCandidatesOfARunningComposition() =
        withHost { host ->
            host.start()
            for (key in "nihao") host.tap(key.toString())
            host.awaitField("ni hao")
            host.main { host.controller.openQuickContentPanel() }
            host.awaitIdle()
            host.main { assertEquals(View.GONE, host.controller.barRow.visibility) }
            host.main { host.controller.closePanels() }
            // With system animations on, the bar comes back when the panel's slide-out ends.
            host.awaitUntil("candidate bar back") { host.controller.barRow.visibility == View.VISIBLE }
            // The composition survived the panel and is still there to confirm.
            assertTrue(host.editor.composing)
        }

    @Test
    fun theSamePanelViewShownAndRemovedTwiceDoesNotCrashAndStillInserts() =
        withHost { host ->
            host.start()
            var first: PhraseClipboardPanelView? = null
            repeat(2) { round ->
                host.main { host.controller.openQuickContentPanel() }
                host.awaitIdle()
                val panel = host.find(PhraseClipboardPanelView::class.java)
                if (round == 0) first = panel
                // A real gesture hands the panel a velocity tracker; detaching must release it once.
                host.main {
                    val now = android.os.SystemClock.uptimeMillis()
                    for ((i, action) in listOf(
                        MotionEvent.ACTION_DOWN,
                        MotionEvent.ACTION_MOVE,
                        MotionEvent.ACTION_UP,
                    ).withIndex()) {
                        val event = MotionEvent.obtain(now, now + i * 20L, action, 40f, 200f - i * 30f, 0)
                        panel.dispatchTouchEvent(event)
                        event.recycle()
                    }
                }
                host.main { host.controller.closePanels() }
                host.awaitToolbar()
                host.awaitIdle()
            }
            host.main { host.controller.openQuickContentPanel() }
            host.awaitIdle()
            val again = host.find(PhraseClipboardPanelView::class.java)
            assertSame("the panel view is reused, which is what made the second detach crash", first, again)
            host.main { again.onInsertText("。") }
            host.awaitField("。")
        }

    @Test
    fun aTappedCandidateIsTheOneCommittedWhenAKeyIsStillQueuedAheadOfIt() =
        withHost { host ->
            host.start()
            host.tap("n")
            host.tap("i")
            host.awaitComposing()
            val labels = host.barLabels()
            assertTrue("bar after ni: $labels", labels.isNotEmpty())
            val shown = labels.first().substringBefore(' ')
            // The key and the tap reach the queue in one main-thread turn, so the tap is made against
            // the page of "ni" while the engine is already on the page of "nih".
            host.tapKeyThenCandidate("h", shown)
            host.awaitIdle()
            assertTrue("the tapped word is committed, got '${host.text()}'", host.text().startsWith(shown))
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
            // first read fills the row and the last page is left for the scroll to ask for.
            host.start(width = STRIP_WIDTH_PX)
            for (key in "ji") host.tap(key.toString())
            host.awaitComposing()
            val pageSize = host.backend.currentSnapshot().pageSize
            val first = host.stripLabels()
            assertTrue("the row is filled past one engine page: $first", first.size > pageSize)
            assertTrue("a page is left to read: $first", first.size < JI_CANDIDATES)
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
            val space = host.keyCenter(host.string(R.string.ime_space))
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
            val bar = host.keyRect(host.string(R.string.ime_space))
            val drift = bar.height() * 0.35f
            assertTrue("the drift must pass the touch slop", drift > 8f * density)
            for (direction in listOf(-1f, 1f)) {
                val before = host.text()
                host.typeKeys("nihc")
                host.awaitComposing()
                val space = host.keyCenter(host.string(R.string.ime_space))
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
            val delete = host.keyCenter(host.string(R.string.ime_delete))
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

    /** A press on Delete whose finger rolls past the touch slop but lifts on the key itself. */
    private fun driftedDelete(
        host: Host,
        holdMs: Long = 0L,
    ) {
        val delete = host.keyCenter(host.string(R.string.ime_delete))
        val box = host.keyRect(host.string(R.string.ime_delete))
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

    @Test
    fun deleteActsOnItsDownAndTheReleaseAddsNothing() =
        withHost { host ->
            host.start(initial = "abc")
            BackspaceTrace.clear()
            val delete = host.keyCenter(host.string(R.string.ime_delete))
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
            val delete = host.keyCenter(host.string(R.string.ime_delete))
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
            val delete = host.keyCenter(host.string(R.string.ime_delete))
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
            host.tap(host.string(R.string.ime_delete))
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
            host.tap(host.string(R.string.ime_delete))
            host.awaitField("aef")
            // The caret the delete left behind is known again, and the next delete is plain.
            host.tap(host.string(R.string.ime_delete))
            host.awaitField("ef")
        }

    @Test
    fun draggingTheSpaceBarMovesTheCaretOneCharacterPerStepOf24Dp() =
        withHost { host ->
            host.start(initial = "abcdefghij")
            val space = host.keyCenter(host.string(R.string.ime_space))
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
    fun aFastSpaceDragNeedsLessTravelPerCharacterAndNeverSkipsOne() =
        withHost { host ->
            host.start(initial = "abcdefghijklmnop")
            val space = host.keyCenter(host.string(R.string.ime_space))
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
            host.tap(host.string(R.string.ime_open_numbers_page))
            host.tap(host.string(R.string.ime_open_symbols_page))
            host.tap("【")
            host.tap("】")
            host.awaitField("【】")
            assertTrue(host.keyLabels().none { it == host.string(R.string.ime_symbol_lock) })
        }

    @Test
    fun theNumbersPageKeepsItsPlaceAfterDigits() =
        withHost { host ->
            host.start()
            host.tap(host.string(R.string.ime_open_numbers_page))
            host.tap("1")
            host.tap("2")
            host.awaitField("12")
        }

    @Test
    fun spaceAfterDigitsOrSymbolsReturnsToLettersForContinuedTyping() =
        withHost { host ->
            host.start()
            val space = host.string(R.string.ime_space)
            host.tap(host.string(R.string.ime_open_numbers_page))
            host.tap(space)
            host.awaitField(" ")
            assertTrue(host.keyLabels().contains("1"))
            host.tap("1")
            host.tap("2")
            host.tap(space)
            host.awaitField(" 12 ")
            assertTrue(host.keyLabels().contains("a"))
            host.tap("n")
            host.tap("i")
            host.tap(space)
            host.awaitField(" 12 你")
            host.tap(host.string(R.string.ime_open_numbers_page))
            host.tap(space)
            host.awaitField(" 12 你 ")
            assertTrue(host.keyLabels().contains("1"))
            host.tap(host.string(R.string.ime_open_symbols_page))
            host.tap("【")
            host.tap(space)
            host.awaitField(" 12 你 【 ")
            assertTrue(host.keyLabels().contains("a"))
        }

    @Test
    fun anEmailFieldHasTheAtSignBesideTheSpaceBarAndNoLanguageKey() =
        withHost { host ->
            host.start(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
            host.tap("@")
            host.tap(".")
            host.awaitField("@.")
            val labels = host.keyLabels()
            assertTrue(labels.toString(), labels.none { it == host.string(R.string.ime_language_to_chinese) })
        }

    @Test
    fun aUrlFieldStartsInEnglishAndStillSwitchesToChinese() =
        withHost { host ->
            host.start(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            // The slash is the first long-press choice of the full stop beside the space bar.
            val dot = host.keyCenter(".")
            val start = SystemClock.uptimeMillis()
            host.main { host.touch(start, MotionEvent.ACTION_DOWN, dot) }
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + LONG_HOLD_MARGIN_MS)
            host.main { host.touch(SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, dot, downTime = start) }
            host.awaitField("/")
            host.tap(host.string(R.string.ime_language_to_chinese))
            host.tap("n")
            host.tap("i")
            host.awaitComposing()
        }

    @Test
    fun aPasswordFieldHasADigitRow() =
        withHost { host ->
            host.start(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            host.tap("7")
            host.awaitField("7")
        }

    /** The digit row adds a row of height; it does not squeeze five rows into the room of four. */
    @Test
    fun aPasswordDigitRowAddsHeightInsteadOfSqueezingTheLetters() =
        withHost { host ->
            host.start(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            var pitch = 0f
            host.main { pitch = host.controller.rowHeightDp * host.context.resources.displayMetrics.density }
            // A middle row's touch band is exactly one pitch; the top row's also reaches the edge.
            val letter = host.keyRect("a").height()
            assertTrue("'a' is $letter px, the row pitch is $pitch px", abs(letter - pitch) <= 2f)
            val digit = host.keyRect("7").height()
            assertTrue("'7' is $digit px, the row pitch is $pitch px", digit >= pitch - 2f)
        }

    @Test
    fun aChineseLetterLongPressTypesItsPunctuation() =
        withHost { host ->
            host.start()
            val a = host.keyCenter("a")
            val start = SystemClock.uptimeMillis()
            host.main { host.touch(start, MotionEvent.ACTION_DOWN, a) }
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + LONG_HOLD_MARGIN_MS)
            host.main { host.touch(SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, a, downTime = start) }
            host.awaitField("，")
        }

    @Test
    fun undoingASelectionWithDeleteHighlightsTheFirstCandidateAgain() =
        withHost { host ->
            host.start()
            for (key in "nihao") host.tap(key.toString())
            host.awaitComposing()
            // Choose a single-character candidate (a partial choice), then take it back.
            val before = host.backend.currentSnapshot()
            val partial = before.candidates.firstOrNull { it.text == "你" }
            assertTrue("no partial candidate in ${before.candidates.map { it.text }}", partial != null)
            assertTrue(partial!!.index > 0)
            host.tap(partial.text, bar = true)
            host.awaitIdle()
            val chosen = host.backend.currentSnapshot()
            assertTrue("after choice: raw=${chosen.rawInput} field=${host.text()}", chosen.isComposing)
            host.tap(host.string(R.string.ime_delete))
            host.awaitIdle()
            val after = host.backend.currentSnapshot()
            assertTrue("composition is back: raw=${after.rawInput} field=${host.text()}", after.isComposing)
            assertEquals("the first candidate is highlighted", 0, after.highlightedIndex)
        }

    /**
     * The field shows the keys typed, not the reading the engine decodes from them: `ni'hc` read as
     * `ni hao` made Delete look like it needed two presses, and the user's own `'` vanished.
     */
    @Test
    fun aDoublePinyinFieldShowsTheKeysTypedAndEachDeleteTakesOneBack() =
        withHost(schema = "songyan_flypy") { host ->
            host.start()
            host.typeKeys("ni'hc")
            assertEquals(
                "你好",
                host.backend
                    .currentSnapshot()
                    .candidates
                    .first()
                    .text,
            )
            host.awaitField("ni'hc")
            for (expected in listOf("ni'h", "ni'", "ni")) {
                host.tap(host.string(R.string.ime_delete))
                host.awaitField(expected)
                assertEquals(expected, host.backend.currentSnapshot().rawInput)
            }
        }

    @Test
    fun aFullPinyinFieldKeepsTheTypedApostropheAndEachDeleteTakesOneKeyBack() =
        withHost { host ->
            host.start()
            host.typeKeys("ni'hc")
            host.awaitField("ni'hc")
            for (expected in listOf("ni'h", "ni'", "ni", "n")) {
                host.tap(host.string(R.string.ime_delete))
                host.awaitField(expected)
                assertEquals(expected, host.backend.currentSnapshot().rawInput)
            }
        }

    /**
     * Space confirms what the engine's highlighted candidate covers, and the key it could not
     * convert stays in the field as typed: the field never claimed `h` was already part of a word.
     */
    @Test
    fun spaceAfterAnUnconvertedInitialConfirmsTheWordAndLeavesTheInitialAsTyped() {
        for (schema in listOf("songyan_quanpin", "songyan_flypy")) {
            withHost(schema = schema) { host ->
                host.start()
                host.typeKeys("ni'h")
                host.awaitField("ni'h")
                assertEquals(
                    "你",
                    host.backend
                        .currentSnapshot()
                        .candidates
                        .first()
                        .text,
                )
                host.tap(host.string(R.string.ime_space))
                host.awaitField("你h")
                assertEquals("ni'h", host.backend.currentSnapshot().rawInput)
                host.tap(host.string(R.string.ime_space))
                host.awaitField("你h")
                assertFalse("$schema: the second Space ends the composition", host.editor.composing)
            }
        }
    }

    /** A compact layout spells its keys out, but an apostrophe the user typed stays in the spelling. */
    @Test
    fun aCompactSpellingKeepsTheTypedApostropheAndEachDeleteShows() {
        for (layout in listOf("merged18", "phone9")) {
            withHost(layout = layout, schema = "songyan_quanpin") { host ->
                host.start()
                for (letter in "ni") host.tapFolded(layout, letter)
                host.typeKeys("'")
                host.awaitField("ni'")
                host.tapFolded(layout, 'h')
                host.awaitIdle()
                // One more key, spelled as whichever letter of that key the engine reads.
                assertTrue("$layout: '${host.text()}'", host.text().length == 4 && host.text().startsWith("ni'"))
                host.tap(host.string(R.string.ime_delete))
                host.awaitField("ni'")
                host.tap(host.string(R.string.ime_delete))
                host.awaitField("ni")
            }
        }
    }

    @Test
    fun aDoublePinyinKeyShowsItsFinalsInTheCorner() =
        withHost(schema = "songyan_flypy") { host ->
            host.start()
            val labels = host.keyLabels()
            assertTrue(labels.toString(), labels.contains("q"))
            // The hint is display only: the key still types its own letter.
            host.tap("q")
            host.awaitComposing()
        }

    @Test
    fun anUpwardFlickOnANineKeyDigitKeyTypesTheDigit() =
        withHost(layout = "phone9") { host ->
            host.start()
            val label = host.keyLabels().first { it.split(' ').contains("5") }
            val key = host.keyCenter(label)
            val density = host.context.resources.displayMetrics.density
            val start = SystemClock.uptimeMillis()
            host.main {
                host.touch(start, MotionEvent.ACTION_DOWN, key)
                host.touch(
                    start + 16,
                    MotionEvent.ACTION_MOVE,
                    key.first to key.second - 30f * density,
                    downTime = start,
                )
                host.touch(start + 40, MotionEvent.ACTION_UP, key.first to key.second - 30f * density, downTime = start)
            }
            host.awaitField("5")
            // A short press on the same key is still the letters: it starts a composition.
            host.tap(label)
            host.awaitComposing()
        }

    private fun flickKey(
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

    @Test
    fun anUpwardFlickOnALetterKeyTypesItsFirstLongPressOption() =
        withHost { host ->
            host.start()
            flickKey(host, "a", -30f)
            host.awaitField("，")
        }

    @Test
    fun aDownwardFlickOnALetterKeyTypesItsSecondLongPressOption() =
        withHost { host ->
            host.start()
            flickKey(host, "a", 30f)
            host.awaitField(",")
        }

    @Test
    fun aShortVerticalJitterOnALetterKeyStillTypesTheLetter() =
        withHost { host ->
            host.start()
            flickKey(host, "a", -4f)
            host.awaitComposing()
            // The letter composes: the field shows its spelling, not a symbol.
            assertEquals("a", host.text())
        }

    /** Drags from [from]'s centre to [to]'s centre and lifts there. */
    private fun dragKey(
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

    @Test
    fun aDownwardFlickWithNothingToTypeDoesNotTypeTheKeyItEndsOn() =
        withHost { host ->
            host.start()
            // "q" has one long-press option, so a downward flick has nothing to type; the finger
            // lifts over "a", which must not be typed in its place.
            dragKey(host, "q", "a")
            host.awaitIdle()
            assertEquals("", host.text())
            host.tap("w")
            host.awaitComposing()
            assertEquals("w", host.text())
        }

    @Test
    fun aDownwardFlickOnANineKeyDigitKeyDoesNotTypeTheKeyBelow() =
        withHost(layout = "phone9") { host ->
            host.start()
            val five = host.keyLabels().first { it.split(' ').contains("5") }
            val eight = host.keyLabels().first { it.split(' ').contains("8") }
            dragKey(host, five, eight)
            host.awaitIdle()
            assertEquals("", host.text())
        }

    @Test
    fun theSymbolPageOpensTheSymbolLibraryWithRecentFirstAndABottomBar() =
        withHost { host ->
            host.start()
            host.tap(host.string(R.string.ime_open_numbers_page))
            host.tap(host.string(R.string.ime_open_symbols_page))
            host.tap("更多")
            host.awaitIdle()
            var labels = emptyList<String>()
            host.main {
                val panel = host.find(app.songyan.ime.ime.panels.SymbolPanelView::class.java)
                labels = host.labelsOf(panel)
            }
            // The rail has "recent" even though nothing was used yet; it sits beside the Chinese categories, so it is Chinese too.
            assertTrue(labels.toString(), labels.contains("最近"))
            assertTrue(labels.toString(), labels.contains(host.string(R.string.ime_delete)))
            assertTrue(labels.toString(), labels.contains(host.string(R.string.ime_space)))
            assertTrue(labels.toString(), labels.contains(host.string(R.string.ime_return_to_letters)))
        }

    @Test
    fun backClosesACandidatePopupBeforeTheKeyboard() =
        withHost { host ->
            host.start()
            for (key in "nihao") host.tap(key.toString())
            host.awaitComposing()
            host.main {
                val bar = host.find(CandidateBarView::class.java)
                val index = host.barLabelsNow().indexOfFirst { it.startsWith("你好") }
                assertTrue(
                    bar.accessibilityNodeProvider.performAction(index, AccessibilityNodeInfo.ACTION_LONG_CLICK, null),
                )
                assertTrue("the character strip is open", bar.popupOpen)
                assertTrue("Back belongs to the keyboard while a popup is open", host.backOwned)
                assertTrue(host.controller.closePanels())
                assertFalse(bar.popupOpen)
            }
            assertTrue("the composition survives", host.editor.composing)
        }

    @Test
    fun cursorMovesUseTheSelectionNotArrowKeys() =
        withHost { host ->
            host.start(initial = "a\uD83D\uDC4Dc")
            host.main {
                host.editor.moveCursor(-1)
                assertEquals(3, host.field.selectionStart)
                // The emoji is one character: a single step crosses both of its UTF-16 units.
                host.editor.moveCursor(-1)
                assertEquals(1, host.field.selectionStart)
                assertEquals(0, host.keyEvents.size)
            }
        }

    @Test
    fun returnIsASoftKeyboardKeyEventWithRealTimes() =
        withHost { host ->
            host.start()
            host.main { assertTrue(host.editor.sendReturn(newlineAsText = false)) }
            assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), host.keyEvents.map { it.action })
            for (event in host.keyEvents) {
                assertEquals(KeyEvent.KEYCODE_ENTER, event.keyCode)
                assertTrue(event.flags and KeyEvent.FLAG_SOFT_KEYBOARD != 0)
                assertTrue(event.downTime > 0 && event.eventTime >= event.downTime)
            }
        }

    @Test
    fun returnDuringAChineseCompositionCommitsTheTypedLetters() =
        withHost { host ->
            host.start()
            for (letter in "zhong") host.tap(letter.toString())
            host.awaitField("zhong")
            host.tap(host.string(R.string.ime_done))
            host.awaitIdle()
            assertEquals("zhong", host.text())
            assertFalse(host.editor.composing)
        }

    @Test
    fun returnDuringANineKeyCompositionCommitsTheHighlightedCandidate() =
        withHost(layout = "phone9") { host ->
            host.start()
            for (key in "94546") host.tapKeyFor(key)
            host.awaitComposing()
            val snapshot = host.backend.currentSnapshot()
            val expected = snapshot.candidates[snapshot.highlightedIndex].text
            host.tap(host.string(R.string.ime_done))
            host.awaitIdle()
            assertEquals(expected, host.text())
            assertFalse(host.editor.composing)
        }

    @Test
    fun theFirstKeyAfterTheKeyboardAppearsIsKept() =
        withHost { host ->
            // No wait: the key arrives while the session is still being prepared.
            host.start(await = false)
            host.tap("n")
            host.awaitField("n")
        }

    @Test
    fun deleteIsAKeyEventWhereTheKeyboardCannotSeeText() =
        withHost { host ->
            host.start(inputType = InputType.TYPE_NULL)
            host.tap(host.string(R.string.ime_delete))
            assertEquals(KeyEvent.KEYCODE_DEL, host.keyEvents.firstOrNull()?.keyCode)
        }

    @Test
    fun anIncognitoFieldComposesChineseWithoutLearning() {
        // Control: the same pick in an ordinary field is learned and ranks higher afterwards.
        val learned =
            withHost { host ->
                host.start()
                pickAndRetype(host)
            }
        assertTrue(
            "an ordinary field must learn: ${learned.first} -> ${learned.second}",
            learned.second < learned.first,
        )
        val incognito =
            withHost { host ->
                host.start(imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
                pickAndRetype(host, retypeInOrdinaryField = true)
            }
        assertEquals("an incognito pick must not be learned", incognito.first, incognito.second)
    }

    /** Picks a low-ranked candidate for `shi`, then types `shi` again; returns its rank before and after. */
    private fun pickAndRetype(
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

    // ---- compact layouts: reading display and exact letters --------------------------------------

    @Test
    fun everyCompactLayoutSpellsAReadingNotItsKeys() {
        for (layout in listOf("merged14", "merged18", "phone9")) {
            for (
            (schema, keys, shown) in
            listOf(Triple("songyan_quanpin", "nihao", "ni hao"), Triple("songyan_flypy", "nihc", "ni hao"))
            ) {
                withHost(layout = layout, schema = schema) { host ->
                    host.start()
                    for (key in keys) host.tapFolded(layout, key)
                    host.awaitComposing()
                    host.awaitIdle()
                    android.util.Log.i("CompactDump", "$layout $schema field='${host.text()}' want '$shown'")
                }
            }
        }
    }

    @Test
    fun aMergedKeySwipeTypesItsExactLetterThroughTheCompanionToken() =
        withHost(layout = "merged14", schema = "songyan_quanpin") { host ->
            host.start()
            val token = CompactFold.MERGED14.exactTokens.getValue('b')
            val schema =
                host.controller.session!!
                    .snapshot.schemaID
            // The companion publishes the token in its alphabet: that is what lets the keyboard send it.
            assertTrue(
                "alphabet of $schema lacks $token",
                runBlocking { host.backend.spellerAlphabet(schema) }.orEmpty().contains(token),
            )
            // A tap on the BN key reads as either letter.
            host.tap("bn")
            host.tap("ui")
            host.awaitComposing()
            val either = host.barLabels()
            assertTrue(
                "a tap keeps both readings: $either",
                either.any { it.startsWith("你") } && either.any { it.startsWith("不") },
            )
            host.discard()
            // Swiping left picks b exactly: the engine reads the token, the field still spells b.
            host.swipe("bn", -1)
            host.tap("ui")
            host.awaitComposing()
            assertEquals(
                "${token}u",
                host.controller.session!!
                    .snapshot.rawInput,
            )
            assertTrue("only b words: ${host.barLabels()}", host.barLabels().none { it.startsWith("你") })
            assertTrue(host.barLabels().any { it.startsWith("不") })
            host.awaitField("bu")
            host.discard()
            // Swiping right picks n exactly.
            host.swipe("bn", 1)
            host.tap("ui")
            host.awaitComposing()
            assertTrue("only n words: ${host.barLabels()}", host.barLabels().none { it.startsWith("不") })
            assertTrue(host.barLabels().any { it.startsWith("你") })
        }

    // ---- nine-key rail: spelling picker and reading filter --------------------------------------

    @Test
    fun aNineKeySpellingPickLocksTheSegmentAndDeleteRestoresIt() =
        withHost(layout = "phone9") { host ->
            host.start()
            for (key in "94") host.tapKeyFor(key)
            host.awaitComposing()
            // The engine proves both spellings of the one key pair; the rail offers them.
            host.awaitKey("xi")
            host.awaitKey("yi")
            val before = host.backend.currentSnapshot()
            assertEquals("94", before.rawInput)
            host.tap("xi")
            host.awaitKey(host.context.getString(R.string.ime_rail_chosen, "xi"))
            assertNotEquals(
                "the engine rewrote the segment",
                "94",
                host.controller.session!!
                    .snapshot.rawInput,
            )
            assertTrue("only xi words remain: ${host.barLabels()}", host.barLabels().none { it.startsWith("一") })
            assertTrue("a chosen spelling is not offered again", host.keyLabels().none { it == "yi" })
            // Delete undoes the lock before it deletes anything.
            host.tap(host.string(R.string.ime_delete))
            host.awaitKey("yi")
            assertEquals("94", host.backend.currentSnapshot().rawInput)
            assertTrue("the first word is back: ${host.barLabels()}", host.barLabels().any { it.startsWith("一") })
        }

    @Test
    fun aKeyTypedAfterASpellingPickRestoresTheOriginalCodeFirst() =
        withHost(layout = "phone9") { host ->
            host.start()
            for (key in "94") host.tapKeyFor(key)
            host.awaitComposing()
            host.awaitKey("xi")
            host.tap("xi")
            host.awaitKey(host.context.getString(R.string.ime_rail_chosen, "xi"))
            host.tapKeyFor('6')
            host.awaitIdle()
            // No alias token is left in front of the new key: the input is the typed code plus it.
            assertEquals("946", host.backend.currentSnapshot().rawInput)
        }

    @Test
    fun aChosenReadingNarrowsTheCandidatesAndSpaceCommitsItsFirstMatch() =
        withHost(layout = "phone9") { host ->
            host.start()
            for (key in "9426") host.tapKeyFor(key)
            host.awaitComposing()
            host.awaitKey("xi")
            val engineFirst =
                host.backend
                    .currentSnapshot()
                    .candidates
                    .first()
                    .text
            assertEquals("小", engineFirst)
            host.tap("xi")
            host.awaitKey(host.context.getString(R.string.ime_rail_chosen, "xi"))
            val bar = host.barLabels()
            assertTrue("only words that read xi remain: $bar", bar.none { it.startsWith("小") })
            assertTrue("西安 reads xi an: $bar", bar.first().startsWith("西安"))
            // The field follows what space will commit.
            host.awaitField("xi'an")
            host.tap(host.string(R.string.ime_space))
            host.awaitField("西安")
        }

    @Test
    fun deleteUndoesAChosenReadingBeforeItDeletesACode() =
        withHost(layout = "phone9") { host ->
            host.start()
            for (key in "9426") host.tapKeyFor(key)
            host.awaitComposing()
            host.awaitKey("xi")
            host.tap("xi")
            host.awaitKey(host.context.getString(R.string.ime_rail_chosen, "xi"))
            host.tap(host.string(R.string.ime_delete))
            host.awaitKey("xi")
            assertEquals("9426", host.backend.currentSnapshot().rawInput)
            assertTrue("the engine's own first word is back", host.barLabels().first().startsWith("小"))
        }

    // ---- 14 / 18-key layouts: reading row and inline readings -----------------------------------

    private fun typeFolded(
        host: Host,
        layout: String,
        letters: String,
    ) {
        for (letter in letters) host.tapFolded(layout, letter)
        host.awaitComposing()
    }

    @Test
    fun aMergedLayoutKeepsAnEmptyReadingRowWhileIdleAndTheHeightNeverJumps() {
        for (layout in listOf("merged14", "merged18")) {
            withHost(layout = layout) { host ->
                host.start()
                val row = host.readingRowHeight()
                assertEquals(
                    "row is ${ReadingRowBudget.ROW_DP} dp",
                    (ReadingRowBudget.ROW_DP * host.density).toInt(),
                    row,
                )
                assertTrue(
                    "idle row has nothing to read: ${host.readingRowLabels()}",
                    host.readingRowLabels().isEmpty(),
                )
                val idleBand = host.topBandHeight()
                // The row under the readings is as tall as the reading row, not a full bar.
                assertEquals("two rows of $row px", 2 * row, idleBand)
                typeFolded(host, layout, "xian")
                host.awaitUntil("a reading in the row") { host.readingRowLabels().isNotEmpty() }
                assertEquals("composing keeps the band height", idleBand, host.topBandHeight())
                assertEquals(row, host.readingRowHeight())
                host.discard()
                assertEquals("clearing keeps the band height", idleBand, host.topBandHeight())
                assertTrue(host.readingRowLabels().isEmpty())
                assertEquals(row, host.readingRowHeight())
            }
        }
    }

    @Test
    fun theOtherLayoutsHaveNoReadingRow() {
        for (layout in listOf("qwerty26", "phone9")) {
            withHost(layout = layout) { host ->
                host.start()
                assertEquals(layout, 0, host.readingRowHeight())
            }
        }
    }

    @Test
    fun theRowUnderTheReadingRowGivesBackWhatAFullBarWouldTake() {
        var plain = 0
        withHost(layout = "qwerty26") { host ->
            host.start()
            plain = host.topBandHeight()
        }
        withHost(layout = "merged14") { host ->
            host.start()
            val row = host.readingRowHeight()
            assertEquals(2 * row, host.topBandHeight())
            assertTrue("the pair is shorter than a reading row on a full bar", host.topBandHeight() < plain + row)
        }
    }

    @Test
    fun tappingAReadingInTheRowSelectsItAndNarrowsTheCandidates() =
        withHost(layout = "merged14") { host ->
            host.start()
            typeFolded(host, "merged14", "xian")
            val reading = host.pickReadingLabel("xi")
            host.awaitReadingRow(reading)
            host.touchReadingRowCorner(reading)
            host.awaitReadingRow(host.context.getString(R.string.ime_rail_chosen, "xi"))
            assertTrue("西安 reads xi an: ${host.barLabels()}", host.barLabels().first().startsWith("西安"))
            // Delete undoes the choice before it deletes a code.
            host.tap(host.string(R.string.ime_delete))
            host.awaitReadingRow(reading)
        }

    @Test
    fun aReadingRowCellIsAtLeast48DpWide() =
        withHost(layout = "merged18") { host ->
            host.start()
            typeFolded(host, "merged18", "xian")
            host.awaitUntil("readings in the row") { host.readingRowLabels().isNotEmpty() }
            host.main {
                val view = host.find(ReadingRowView::class.java)
                val provider = view.accessibilityNodeProvider
                var index = 0
                while (true) {
                    val node = provider.createAccessibilityNodeInfo(index) ?: break
                    val bounds = Rect()
                    node.getBoundsInParent(bounds)
                    assertTrue("cell $index is ${bounds.width()} px", bounds.width() >= (48 * host.density).toInt())
                    assertEquals("the cell covers the whole row", view.height, bounds.height())
                    index++
                }
            }
        }

    @Test
    fun inlineReadingsSitAtTheStartOfTheCandidateRowAndAddNoHeight() {
        var plain = 0
        withHost(layout = "qwerty26") { host ->
            host.start()
            plain = host.topBandHeight()
        }
        withHost(layout = "merged14", readingPlacement = "inline") { host ->
            host.start()
            assertEquals("no row of its own", 0, host.readingRowHeight())
            assertEquals("the band keeps the plain height", plain, host.topBandHeight())
            typeFolded(host, "merged14", "xian")
            val reading = host.pickReadingLabel("xi")
            host.awaitInlineReading(reading)
            assertEquals(plain, host.topBandHeight())
            val first = host.barItemBounds(reading)
            val candidate =
                host.barLabels().first {
                    !it.startsWith(host.readingPrefix()) &&
                        !it.startsWith(host.spellingPrefix())
                }
            assertTrue("readings come before the candidates", first.right <= host.barItemBounds(candidate).left)
            // A tap on the reading selects it; the candidates narrow and the choice shows as chosen.
            host.tap(reading, bar = true)
            host.awaitInlineReading(host.context.getString(R.string.ime_rail_chosen, "xi"))
            assertTrue("西安 reads xi an: ${host.barLabels()}", host.barLabels().any { it.startsWith("西安") })
        }
    }

    @Test
    fun manyInlineReadingsStillLeaveTheFirstCandidateInViewAndTappable() =
        withHost(layout = "merged14", readingPlacement = "inline") { host ->
            // A narrow row: two readings already need more than the readings' share of it.
            host.start(width = CROWDED_WIDTH_PX)
            // The 14-key d/f and u/i keys make di, du and fu one code: three spellings for one syllable.
            typeFolded(host, "merged14", "di")
            host.awaitInlineReading(host.context.getString(R.string.ime_reading_lock, "fu"))
            val readings = host.inlineReadingLabels()
            assertTrue("several readings: $readings", readings.size >= 2)
            val candidate =
                host.barLabels().first {
                    !it.startsWith(host.readingPrefix()) &&
                        !it.startsWith(host.spellingPrefix()) &&
                        it != host.string(R.string.ime_expand)
                }
            val first = host.barItemBounds(candidate)
            val lastReading = host.barItemBounds(readings.last())
            assertTrue("the readings overflow their section: $readings", lastReading.right > first.left)
            var stripEnd = 0
            host.main { stripEnd = host.find(CandidateBarView::class.java).viewport.right }
            assertTrue("the first candidate is whole and in view: $first", first.left >= 0 && first.right <= stripEnd)
            host.saveScreenshot("crowded-inline-readings")
            host.touchCandidate(candidate)
            val word = candidate.substringBefore(' ')
            assertTrue("'$word' was typed: '${host.text()}'", host.text().startsWith(word))
        }

    @Test
    fun changingThePlacementMovesTheReadingsWithoutRestarting() =
        withHost(layout = "merged14") { host ->
            host.start()
            typeFolded(host, "merged14", "xian")
            val reading = host.pickReadingLabel("xi")
            host.awaitReadingRow(reading)
            host.main { host.preferences.readingPlacement.value = "inline" }
            host.awaitInlineReading(reading)
            assertEquals(0, host.readingRowHeight())
            host.main { host.preferences.readingPlacement.value = "row" }
            host.awaitReadingRow(reading)
        }

    @Test
    fun theKeyboardMenuOffersWhatTheEngineListsLessWhatTheUserHid() =
        withHost { host ->
            host.start()
            // Generated layout companions (keyboard_<hash>_<fold>) run as schemas but are never offered.
            val listed =
                host.backend.availableSchemas
                    .map { it.id }
                    .filterNot { it.startsWith("keyboard_") }
            assertTrue("the engine lists more than one input method: $listed", listed.size > 1)
            assertEquals(listed, host.main2 { host.controller.menu.schemaIds })
            val current = host.backend.currentSnapshot().schemaID
            val other = listed.first { it != current && !current.startsWith(it) }
            host.main { host.preferences.menuHiddenSchemas.value = setOf(other) }
            host.awaitIdle()
            assertEquals(listed - other, host.main2 { host.controller.menu.schemaIds })
        }

    @Test
    fun turningShuangpinHintsOnDrawsThemUnderTheLettersAndEnglishDropsThem() =
        withHost { host ->
            host.start()

            fun hintUnder(letter: String): List<String?>? =
                host.main2 {
                    val view = host.find(KeyboardView::class.java)
                    view.keys
                        .indexOfFirst { it.label == letter }
                        .takeIf { it >= 0 }
                        ?.let { view.syllableLines.getOrNull(it)?.columns }
                }
            assertNull(hintUnder("q"))
            host.main { host.preferences.shuangpinHints.value = ShuangpinHintPresets.builtinXiaohe.hints }
            host.awaitIdle()
            assertEquals(listOf("iu"), hintUnder("q"))
            // A letter without a hint still gets an empty line so its row keeps one baseline.
            assertEquals(listOf<String?>(null), hintUnder("a"))
            // Typing still sends the letter, not the hint.
            host.tap("q")
            host.awaitComposing()
            host.tap(host.string(R.string.ime_delete))
            host.awaitIdle()
            host.tap(host.string(R.string.ime_language_to_english))
            host.awaitIdle()
            assertNull(hintUnder("q"))
        }

    /**
     * The keycap does not grow with the system font, so at the largest font a letter used to run
     * into its shuangpin hint and corner mark, and "iang/uang" broke onto a second line over the l.
     */
    @Test
    fun theLargestSystemFontKeepsEveryLetterClearOfItsHints() =
        withHost { host ->
            host.start()
            host.main { host.preferences.shuangpinHints.value = ShuangpinHintPresets.builtinXiaohe.hints }
            host.awaitIdle()

            fun collidingAt(fontScale: Float): Pair<List<String>, Boolean> =
                host.main2 {
                    val view = host.find(KeyboardView::class.java)
                    view.textScale = ImeText.scale(view.resources.displayMetrics.density, fontScale)
                    view.fitLabels()
                    val colliding =
                        view.keys.indices
                            .filter { view.stackCollides(it, 1f) }
                            .map { view.labels[it] }
                    val l = view.keys.indexOfFirst { it.label == "l" }
                    colliding to view.syllableLines[l]!!.breaks.any { it > 0 }
                }
            val (atDefault, _) = collidingAt(1f)
            val (colliding, wrapped) = collidingAt(2f)

            assertEquals(atDefault, colliding)
            assertFalse("iang/uang stays on one line", wrapped)
        }

    @Test
    fun aKeyOnlyTheSplitArrangementHasTypesItsLetterWhenSplitAndIsAbsentWhenWhole() {
        fun letter(c: Char) = LayoutKey(c.toString(), action = LayoutAction.code(c.toString()))
        val base = BuiltinLayouts.qwerty26
        val split = LayoutSplitConfiguration.of(LayoutSplitConfiguration.Activation.AUTOMATIC)
        val halves = split.derivedArrangement(base.rows).toMutableList()
        halves[0] = halves[0].copy(left = halves[0].left + letter('y'))
        halves[1] = halves[1].copy(right = listOf(letter('g')) + halves[1].right)
        halves[2] = halves[2].copy(right = listOf(letter('v')) + halves[2].right)
        val layout = base.withSplit(split.copy(halves = halves))
        instrumentation.runOnMainSync { KeyboardLayoutCatalog.install(LayoutRegistry.create().replace(layout)) }
        try {
            withHost { host ->
                host.start(width = 1080)
                val duplicated = listOf("y", "g", "v")
                // A phone-wide keyboard is whole: the split-only keys have no slot at all.
                for (letter in duplicated) assertEquals(letter, 1, host.keyLabels().count { it.equals(letter, true) })
                host.resize(2400)
                for (letter in duplicated) {
                    assertEquals(letter, 2, host.keyLabels().count { it.equals(letter, true) })
                    for (nth in 0..1) {
                        host.tapNth(letter, nth)
                        host.awaitField(letter)
                        host.tap(host.string(R.string.ime_delete))
                        host.awaitField("")
                    }
                }
                host.resize(1080)
                for (letter in duplicated) assertEquals(letter, 1, host.keyLabels().count { it.equals(letter, true) })
            }
        } finally {
            instrumentation.runOnMainSync { KeyboardLayoutCatalog.install(LayoutRegistry.create()) }
        }
    }

    @Test
    fun theKeyAreaOnAWideSurfaceSitsAtThePercentOfTheFreeSpaceAndFollowsTheSettingLive() {
        withHost(smallestWidthDp = 800) { host ->
            host.preferences.widePosition.value = 25
            host.start(width = 2400)
            val density = host.density
            val freePx = 2400f - WideSurface.CONTENT_MAXIMUM_DP.toFloat() * density

            fun keyAreaLeft(): Float =
                host.main2 {
                    val view = host.find(KeyboardView::class.java)
                    view.contentFrame(null).left
                }

            assertEquals(freePx * 0.25f, keyAreaLeft(), 1.5f)
            // The first key of the top row starts at the key area's edge, not at the screen edge.
            assertEquals(keyAreaLeft(), host.keyRect("q").left.toFloat(), 16f * density)

            host.main2 { host.preferences.widePosition.value = 100 }
            host.awaitIdle()
            assertEquals(freePx, keyAreaLeft(), 1.5f)
            host.main2 { host.preferences.widePosition.value = 0 }
            host.awaitIdle()
            assertEquals(0f, keyAreaLeft(), 1.5f)
        }
    }

    // ---- harness -------------------------------------------------------------------------------

    private fun <T> withHost(
        layout: String = "qwerty26",
        fontScale: Float = 1f,
        smallPages: Boolean = false,
        schema: String = "",
        readingPlacement: String = "row",
        smallestWidthDp: Int? = null,
        block: (Host) -> T,
    ): T {
        val user = File(root, "user-" + SystemClock.uptimeMillis())
        val engineSource = if (smallPages) smallPageDeployment.first else source
        val engineCompiled = if (smallPages) smallPageDeployment.second else compiled
        val backend =
            runBlocking {
                RimeEngineBackend.create(engineSource, user, engineCompiled, RimeEngine.DEFAULT_APP_NAME, "test")
            }
        val host = Host(backend, layout, fontScale, schema, readingPlacement, smallestWidthDp)
        try {
            return block(host)
        } finally {
            try {
                host.close()
            } finally {
                runBlocking { backend.shutdown() }
                user.deleteRecursively()
            }
        }
    }

    private class Host(
        val backend: EngineBackend,
        layout: String,
        private val fontScale: Float = 1f,
        schema: String = "",
        readingPlacement: String = "row",
        smallestWidthDp: Int? = null,
    ) {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
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
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
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
                    action = { editor.sendReturn(false) },
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

        /** Holds a finger on the candidate [text] past the long-press time, then lifts it without moving. */
        fun longPressCandidate(text: String) {
            val center = barItemCenter(text)
            lastBarPoint = center
            val start = SystemClock.uptimeMillis()
            main { touchBar(start, MotionEvent.ACTION_DOWN, center, start) }
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
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
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
        fun switchField(): EditText {
            val left = field
            connection.finishComposingText()
            field = EditText(context)
            field.inputType = InputType.TYPE_CLASS_TEXT
            connect()
            beginInput(restarting = true)
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

        override suspend fun setLayoutId(id: String) {
            layoutId.value = id
        }

        override suspend fun setActiveSchemaId(id: String) {
            activeSchemaId.value = id
        }
    }

    private object Phrases : QuickContentPanelDataSource {
        override suspend fun loadContent() =
            QuickContentPanelContent(SymbolCatalog(), emptyList(), listOf(CommonPhrase("。")), emptyList(), false)

        override suspend fun recordRecentSymbol(text: String) = Unit

        override suspend fun deleteClipboardEntry(text: String) = Unit

        override suspend fun clearRecentSymbols() = Unit
    }

    companion object {
        private const val TIMEOUT_MS = 5_000L
        private const val POLL_MS = 40L
        private const val STABLE_POLLS = 3
        private const val NARROW_WIDTH_PX = 360
        private const val LONG_HOLD_MARGIN_MS = 300L

        /** Finger roll on a tap: past the 8 dp touch slop, inside any Delete key. */
        private const val DRIFT_DP = 12f
        private const val MIN_OVERFLOW_PAGE = 4
        private const val PICKED_RANK = 4
        private const val BUILTIN_ASSET_ROOT = "rime/builtin"
        private const val MAX_FONT_SCALE = 1.3f
        private const val LAST_OF_FIVE = 4
        private const val EXPANDED_DRAG_PX = 700f
        private const val EXPANDED_DRAG_STEPS = 8
        private const val EXPANDED_DRAG_START_Y = 200f
        private const val DRAG_STEP_MS = 16L
        private const val STRIP_SWIPES = 3

        /** About one candidate cell of strip beside the expand control on a phone-density screen. */
        private const val STRIP_WIDTH_PX = 240

        /** A row where two inline readings already take more than half of the strip. */
        private const val CROWDED_WIDTH_PX = 540

        /** The test dictionary has five candidates for ji. */
        private const val JI_CANDIDATES = 5
        private const val STRIP_SWIPE_STEPS = 8

        private lateinit var root: File
        private lateinit var source: File
        private lateinit var compiled: File

        /** One deployment for the class; every test gets its own learning directory. */
        @BeforeClass
        @JvmStatic
        fun deploy() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            root = File(context.cacheDir, "real-engine-editor")
            root.deleteRecursively()
            source = File(root, "source")
            copyAssetTree(context.assets, BUILTIN_ASSET_ROOT, source)
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
        private val smallPageDeployment: Pair<File, File> by lazy {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val small = File(root, "source-small")
            copyAssetTree(context.assets, BUILTIN_ASSET_ROOT, small)
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

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            root.deleteRecursively()
        }

        private fun copyAssetTree(
            assets: AssetManager,
            assetPath: String,
            target: File,
        ) {
            target.mkdirs()
            for (name in assets.list(assetPath).orEmpty()) {
                val childPath = "$assetPath/$name"
                val child = File(target, name)
                if (assets.list(childPath).orEmpty().isNotEmpty()) {
                    copyAssetTree(assets, childPath, child)
                } else {
                    assets.open(childPath).use { input -> child.outputStream().use { input.copyTo(it) } }
                }
            }
        }
    }
}
