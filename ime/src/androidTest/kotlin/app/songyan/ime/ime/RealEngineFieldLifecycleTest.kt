package app.songyan.ime.ime

import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songyan.ime.ime.panels.EditPanelView
import app.songyan.ime.ime.panels.PhraseClipboardPanelView
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutActionKind
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
 * Fields that restart, end or change under a running composition, predictions, queued presses and panels.
 */
@RunWith(AndroidJUnit4::class)
class RealEngineFieldLifecycleTest : RealEngineEditorTestBase() {
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

    /** A Delete answered after its field ended must not delete in the field that replaced it. */
    @Test
    fun aDeleteAnsweredAfterItsFieldEndedDeletesNothingInTheNextField() =
        withHost(
            schema = PREDICTION_SCHEMA,
            fixtureAssetRoot = PREDICTION_FIXTURE,
            pauseDismiss = true,
        ) { host ->
            host.start(initial = "abc")
            typePredictionContext(host)
            val paused = host.backend as PausedDismissBackend
            val gate = CompletableDeferred<Unit>()
            host.main { paused.gate = gate }
            val delete = host.keyCenter(host.string(R.string.ime_delete))
            val start = SystemClock.uptimeMillis()
            host.main { host.touch(start, MotionEvent.ACTION_DOWN, delete) }
            host.awaitUntil("the engine to be asked to dismiss the prediction") { paused.dismissals > 0 }
            // Release before the field changes: a finger still down would add its own repeats.
            host.main { host.touch(SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, delete, downTime = start) }
            host.main {
                host.finishInput()
                host.switchField(restarting = false, initialText = "abcdef")
            }
            host.main { gate.complete(Unit) }
            host.awaitIdle()
            assertEquals("the new field keeps its text", "abcdef", host.text())
        }

    /** The same Delete in the field it was made in: dismissing the prediction still deletes once. */
    @Test
    fun aDeleteStillDeletesAfterDismissingThePredictionInItsOwnField() =
        withHost(
            schema = PREDICTION_SCHEMA,
            fixtureAssetRoot = PREDICTION_FIXTURE,
            pauseDismiss = true,
        ) { host ->
            host.start(initial = "abc")
            typePredictionContext(host)
            val paused = host.backend as PausedDismissBackend
            val gate = CompletableDeferred<Unit>()
            host.main { paused.gate = gate }
            val delete = host.keyCenter(host.string(R.string.ime_delete))
            val start = SystemClock.uptimeMillis()
            host.main { host.touch(start, MotionEvent.ACTION_DOWN, delete) }
            host.awaitUntil("the engine to be asked to dismiss the prediction") { paused.dismissals > 0 }
            // Release before the gate opens: a finger still down would add its own repeats.
            host.main { host.touch(SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, delete, downTime = start) }
            host.main { gate.complete(Unit) }
            host.awaitIdle()
            assertEquals("the committed word is deleted", "abc", host.text())
        }

    /** A space after a prediction types only a space: the predicted word is never picked for the user. */
    @Test
    fun aSpaceAfterAPredictionTypesOnlyASpace() =
        withHost(schema = PREDICTION_SCHEMA, fixtureAssetRoot = PREDICTION_FIXTURE) { host ->
            host.start()
            typePredictionContext(host)
            assertTrue(
                "the row offers the prediction: " + host.barLabels(),
                host.barLabels().any { it.startsWith("好") },
            )
            host.tap(host.string(R.string.ime_space))
            host.awaitField("你 ")
            host.awaitIdle()
            assertFalse("the prediction is gone", host.backend.currentSnapshot().isPrediction)
            assertFalse(
                "the predicted word was not typed: " + host.text(),
                host.text().contains("好"),
            )
        }

    /** A punctuation key after a prediction types only that mark, never the predicted word. */
    @Test
    fun aMarkAfterAPredictionTypesOnlyTheMark() =
        withHost(schema = PREDICTION_SCHEMA, fixtureAssetRoot = PREDICTION_FIXTURE) { host ->
            host.start()
            typePredictionContext(host)
            assertTrue(
                "the row offers the prediction: " + host.barLabels(),
                host.barLabels().any { it.startsWith("好") },
            )
            host.tap(host.string(R.string.ime_open_numbers_page))
            host.tap(host.string(R.string.ime_open_symbols_page))
            host.tap("【")
            host.awaitField("你【")
            host.awaitIdle()
            assertFalse("the prediction is gone", host.backend.currentSnapshot().isPrediction)
            assertFalse(
                "the predicted word was not typed: " + host.text(),
                host.text().contains("好"),
            )
        }

    /** Choosing the predicted word in the row is the one way it commits. */
    @Test
    fun tappingAPredictedCandidateCommitsIt() =
        withHost(schema = PREDICTION_SCHEMA, fixtureAssetRoot = PREDICTION_FIXTURE) { host ->
            host.start()
            typePredictionContext(host)
            val word = host.barLabels().firstOrNull { it.startsWith("好") }
            assertTrue("the row offers the prediction: " + host.barLabels(), word != null)
            host.tap(word!!, bar = true)
            host.awaitField("你好")
            host.awaitIdle()
            assertFalse("the prediction is gone", host.backend.currentSnapshot().isPrediction)
        }

    /** Typing on after a prediction types only what was typed: the predicted word never leaks in. */
    @Test
    fun typingAfterAPredictionDoesNotTypeThePredictedWord() =
        withHost(schema = PREDICTION_SCHEMA, fixtureAssetRoot = PREDICTION_FIXTURE) { host ->
            host.start()
            typePredictionContext(host)
            host.tap("n")
            host.tap("i")
            host.awaitField("你ni")
            host.awaitIdle()
            assertFalse(
                "the predicted word was not typed: " + host.text(),
                host.text().contains("好"),
            )
            assertTrue(
                "the new keys compose: " + host.backend.currentSnapshot(),
                host.backend.currentSnapshot().isComposing,
            )
        }

    /**
     * A panel edit confirms the composition through the engine before it edits. If the field ends
     * while that answer is pending, the edit must not reach the field that replaced it.
     */
    @Test
    fun aPanelEditAnsweredAfterItsFieldEndedEditsNothingInTheNextField() =
        withHost(
            schema = PREDICTION_SCHEMA,
            fixtureAssetRoot = PREDICTION_FIXTURE,
            pauseDismiss = true,
        ) { host ->
            host.start(initial = "abcdef")
            typePredictionContext(host)
            host.main { host.controller.openEditPanel() }
            host.awaitIdle()
            val paused = host.backend as PausedDismissBackend
            val gate = CompletableDeferred<Unit>()
            host.main { paused.gate = gate }
            host.tapPanelCell(EditPanelView::class.java, host.string(R.string.ime_edit_delete))
            host.awaitUntil("the engine to be asked to dismiss the prediction") { paused.dismissals > 0 }
            host.main {
                host.finishInput()
                host.switchField(restarting = false, initialText = "abcdef")
            }
            host.main { gate.complete(Unit) }
            host.awaitIdle()
            assertEquals("the new field keeps its text", "abcdef", host.text())
        }

    /** A prediction belongs to its field: it must not survive into the next one. */
    @Test
    fun aPredictionDoesNotSurviveIntoTheNextField() =
        withHost(schema = PREDICTION_SCHEMA, fixtureAssetRoot = PREDICTION_FIXTURE) { host ->
            host.start(initial = "abcdef")
            typePredictionContext(host)
            host.main {
                host.finishInput()
                host.switchField(restarting = false, initialText = "abcdef")
            }
            host.awaitIdle()
            assertEquals("the new field keeps its text", "abcdef", host.text())
            assertFalse("the prediction went with its field", host.backend.currentSnapshot().isPrediction)
            assertTrue("no predicted word is offered", host.barLabels().none { it.startsWith("好") })
        }

    /** Enter on a multi-line host types a newline and closes the prediction, never a predicted word. */
    @Test
    fun aReturnAfterAPredictionTypesANewline() =
        withHost(schema = PREDICTION_SCHEMA, fixtureAssetRoot = PREDICTION_FIXTURE) { host ->
            host.start(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
            typePredictionContext(host)
            host.tap(host.string(R.string.ime_enter))
            host.awaitField("你\n")
            host.awaitIdle()
            assertFalse("the prediction is gone", host.backend.currentSnapshot().isPrediction)
            assertFalse("the predicted word was not typed: " + host.text(), host.text().contains("好"))
        }

    /**
     * The keyboard is still busy with a hold when the finger lifts: the hold's queued repeat was
     * made for that hold and must delete nothing, not even when the same key is pressed again.
     */
    @Test
    fun aQueuedRepeatThatOutlivesItsHoldDeletesNothing() =
        withHost { host ->
            host.start(initial = "abcd")
            val queue = CompletableDeferred<Unit>()
            host.main { host.controller.enqueue { queue.await() } }
            val delete = host.keyCenter(host.string(R.string.ime_delete))
            val first = SystemClock.uptimeMillis()
            host.main {
                host.touch(first, MotionEvent.ACTION_DOWN, delete)
                // The hold's first step: a real repeat, queued behind the blocked operation.
                val pointers = host.controller.keyboard.pointers
                requireNotNull(pointers.valueAt(0).task).run()
                host.touch(first + 20, MotionEvent.ACTION_UP, delete, downTime = first)
            }
            // A new press on the same key while the old hold's step is still queued.
            val second = SystemClock.uptimeMillis()
            host.main {
                host.touch(second, MotionEvent.ACTION_DOWN, delete)
                host.touch(second + 20, MotionEvent.ACTION_UP, delete, downTime = second)
            }
            host.main { queue.complete(Unit) }
            host.awaitIdle()
            assertEquals("one delete per press, none from the finished hold", "ab", host.text())
        }

    @Test
    fun aQueuedRepeatStopsOnceTheCompositionItWasDeletingIsGone() =
        withHost { host ->
            host.start(initial = "abc")
            host.tap("n")
            host.awaitField("abcn")
            val queue = CompletableDeferred<Unit>()
            host.main { host.controller.enqueue { queue.await() } }
            val delete = host.keyCenter(host.string(R.string.ime_delete))
            val start = SystemClock.uptimeMillis()
            host.main {
                host.touch(start, MotionEvent.ACTION_DOWN, delete)
                // The hold's first step is queued while the composition it deletes is still there.
                val pointers = host.controller.keyboard.pointers
                requireNotNull(pointers.valueAt(0).task).run()
            }
            host.main { queue.complete(Unit) }
            // The DOWN deletes the last letter and the composition ends: the queued repeat must not
            // delete the text that is already committed (the finger is still down, so only the
            // composition rule can stop it).
            host.awaitIdle()
            host.main { host.touch(SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, delete, downTime = start) }
            host.awaitIdle()
            assertEquals("abc", host.text())
        }

    /**
     * The expanded candidates' held Delete repeats through the keyboard's queue too: a step queued
     * by one touch must not delete for the next touch on the same control, even though the control,
     * the grid and the composition rule all say "held" again.
     */
    @Test
    fun aQueuedCandidateRepeatDoesNotDeleteForTheNextTouchOnTheSameControl() =
        withHost { host ->
            host.start(initial = "xyz")
            host.typeKeys("nihao")
            host.main {
                host.controller.candidates.setExpanded(true)
                host.relayout()
            }
            host.awaitIdle()
            val queue = CompletableDeferred<Unit>()
            host.main { host.controller.enqueue { queue.await() } }
            val bar = host.controller.candidates
            val (x, y) = host.main2 { bar.controlCells[1].let { it.exactCenterX() to it.exactCenterY() } }

            fun touch(
                action: Int,
                downTime: Long,
                eventTime: Long,
            ) {
                val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
                bar.dispatchTouchEvent(event)
                event.recycle()
            }
            val first = SystemClock.uptimeMillis()
            host.main {
                touch(MotionEvent.ACTION_DOWN, first, first)
                // The touch's own hold step, queued behind the blocked operation.
                bar.repeatBackspace.run()
                touch(MotionEvent.ACTION_UP, first, first + 20)
                // No second step of this touch: the one below is what the queue must judge.
                bar.ui.removeCallbacks(bar.repeatBackspace)
            }
            // A new hold on the same control, still down when the queue is let go.
            val second = SystemClock.uptimeMillis()
            host.main {
                touch(MotionEvent.ACTION_DOWN, second, second)
                bar.ui.removeCallbacks(bar.repeatBackspace)
            }
            host.main { queue.complete(Unit) }
            host.awaitIdle()
            host.main { touch(MotionEvent.ACTION_UP, second, SystemClock.uptimeMillis()) }
            host.awaitIdle()
            assertEquals(
                "only the two presses delete, while composition stays active",
                "nih",
                host.backend.currentSnapshot().rawInput,
            )
            assertTrue("committed text stays untouched", host.text().startsWith("xyz"))
        }

    /**
     * The keyboard is still busy with a press when its field ends: that press was made for the field
     * and must not be typed into the one that replaces it, while the new field's own press is typed
     * there and only there.
     */
    @Test
    fun aPressQueuedWhenItsFieldEndsIsNotTypedIntoTheNextField() =
        withHost { host ->
            host.start()
            val queue = CompletableDeferred<Unit>()
            // Hold the keyboard's operation queue so the press is still queued when the field ends:
            // the engine answering a press opens the same window while typing.
            host.main { host.controller.enqueue { queue.await() } }
            host.main { host.controller.enqueueKey(LayoutAction(LayoutActionKind.CODE, "h")) }
            var left: EditText? = null
            // The system finishes the old input before it starts the next field.
            host.main {
                host.connection.finishComposingText()
                host.finishInput()
                left = host.switchField(restarting = false)
            }
            // The new field's own press, made while its session is still being prepared.
            host.main { host.controller.enqueueKey(LayoutAction(LayoutActionKind.CODE, "n")) }
            host.main { queue.complete(Unit) }
            // Only the new field's letter: the finished field's press is dropped, not the new one's.
            host.awaitField("n")
            assertEquals("n", host.backend.currentSnapshot().rawInput)
            var leftText = ""
            host.main { leftText = left!!.text.toString() }
            assertEquals("the field that ended does not take it", "", leftText)
        }

    /**
     * A restart of the same field does not finish its input, so a press queued for it is still its
     * own and arrives once the restarted session is ready.
     */
    @Test
    fun aPressQueuedForARestartOfItsFieldIsStillTyped() =
        withHost { host ->
            host.start()
            val queue = CompletableDeferred<Unit>()
            host.main { host.controller.enqueue { queue.await() } }
            host.main { host.controller.enqueueKey(LayoutAction(LayoutActionKind.CODE, "n")) }
            // onStartInput(restarting = true) on the same field, as the service does on a restart.
            host.main {
                val keep = host.editor.startInput(host.info, restarting = true)
                val policy = EditorPolicy.from(host.info)
                host.controller.configure(policy)
                host.controller.startInput(policy, keep)
            }
            host.main { queue.complete(Unit) }
            host.awaitIdle()
            host.awaitField("n")
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
}
