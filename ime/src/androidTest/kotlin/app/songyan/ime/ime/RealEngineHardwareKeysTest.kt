package app.songyan.ime.ime

import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.keyboard.session.InputMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hardware keys through the keyboard's own session over the real engine. The host calls the same
 * [KeyboardController.hardwareKey] the input method service calls; the service wiring is covered by
 * the app's end-to-end test.
 */
@RunWith(AndroidJUnit4::class)
class RealEngineHardwareKeysTest : RealEngineEditorTestBase() {
    private val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
    private val shift = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON

    private fun Host.firstCandidate() =
        backend
            .currentSnapshot()
            .candidates
            .first()
            .text

    @Test
    fun lettersComposeAndSpaceCommitsTheFirstCandidate() =
        withHost { host ->
            host.start()
            val before = host.text()
            host.typeHardware("ni")
            host.awaitField(before + "ni")
            val first = host.firstCandidate()
            val taken = host.hardware(KeyEvent.KEYCODE_SPACE)
            assertTrue(taken.down && taken.up)
            host.awaitField(before + first)
            assertFalse(host.editor.composing)
            assertTrue("nothing is forwarded: ${host.keyEvents}", host.keyEvents.isEmpty())
        }

    @Test
    fun aDigitChoosesThatCandidate() =
        withHost { host ->
            host.start()
            host.typeHardware("shi")
            host.awaitField("shi")
            val second =
                host.backend
                    .currentSnapshot()
                    .candidates[1]
                    .text
            host.hardware(KeyEvent.KEYCODE_2)
            host.awaitField(second)
            assertTrue(host.keyEvents.isEmpty())
        }

    @Test
    fun escapeClearsTheComposition() =
        withHost { host ->
            host.start()
            host.typeHardware("ni")
            host.awaitField("ni")
            host.hardware(KeyEvent.KEYCODE_ESCAPE)
            host.awaitField("")
            assertFalse(host.backend.currentSnapshot().isComposing)
            assertTrue(host.keyEvents.isEmpty())
        }

    @Test
    fun backspaceEditsTheCompositionThenGoesToTheEditorWhenIdle() =
        withHost { host ->
            host.start()
            host.typeHardware("ni")
            host.awaitField("ni")
            host.hardware(KeyEvent.KEYCODE_DEL)
            host.awaitField("n")
            host.hardware(KeyEvent.KEYCODE_DEL)
            host.awaitField("")
            assertTrue("the composition took both: ${host.keyEvents}", host.keyEvents.isEmpty())
            host.sentHardware.clear()
            host.hardware(KeyEvent.KEYCODE_DEL)
            assertEquals(2, host.keyEvents.size)
            assertSame(host.sentHardware[0], host.keyEvents[0])
            assertSame(host.sentHardware[1], host.keyEvents[1])
        }

    @Test
    fun idleArrowsAndCtrlShortcutsGoBackToTheEditorUnchanged() =
        withHost { host ->
            host.start(initial = "abc")
            val arrow = host.hardware(KeyEvent.KEYCODE_DPAD_LEFT)
            assertTrue(arrow.down && arrow.up)
            assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), host.keyEvents.map { it.action })
            host.keyEvents.clear()
            host.hardware(KeyEvent.KEYCODE_C, ctrl)
            assertEquals(2, host.keyEvents.size)
            assertEquals(KeyEvent.KEYCODE_C, host.keyEvents[0].keyCode)
            assertEquals(ctrl, host.keyEvents[0].metaState)
            assertEquals(ctrl, host.keyEvents[1].metaState)
        }

    @Test
    fun anArrowInsideACompositionIsNotForwarded() =
        withHost { host ->
            host.start()
            host.typeHardware("ni")
            host.awaitField("ni")
            host.hardware(KeyEvent.KEYCODE_DPAD_LEFT)
            assertTrue(host.keyEvents.isEmpty())
            assertTrue(host.backend.currentSnapshot().isComposing)
        }

    @Test
    fun theUpOfASwallowedDownIsSwallowedAndTheUpOfAForwardedDownIsForwarded() =
        withHost { host ->
            host.start()
            host.typeHardware("ni")
            host.awaitField("ni")
            host.hardwareEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
            host.awaitIdle()
            // The composition ends before the key is released: its release is still not the app's.
            host.tapSpace()
            assertFalse(host.backend.currentSnapshot().isComposing)
            assertTrue(host.hardwareEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
            host.awaitIdle()
            assertTrue("the release was swallowed: ${host.keyEvents}", host.keyEvents.isEmpty())

            host.hardwareEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)
            host.awaitIdle()
            assertEquals(1, host.keyEvents.size)
            // A composition starts before the release: the release still goes where its press went.
            host.tap("n")
            host.hardwareEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT)
            host.awaitIdle()
            assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), host.keyEvents.map { it.action })
        }

    @Test
    fun shiftSwitchesToEnglishThroughTheSchemeAndBack() =
        withHost(schema = PREDICTION_SCHEMA, fixtureAssetRoot = PREDICTION_FIXTURE) { host ->
            host.start()
            assertFalse(host.backend.currentSnapshot().isAsciiMode)
            host.hardware(KeyEvent.KEYCODE_SHIFT_LEFT, shift)
            assertTrue("Shift switched Rime to ASCII", host.backend.currentSnapshot().isAsciiMode)
            host.keyEvents.clear()
            host.hardware(KeyEvent.KEYCODE_N)
            assertEquals("the letter is the app's now", 2, host.keyEvents.size)
            assertFalse(host.backend.currentSnapshot().isComposing)
            host.hardware(KeyEvent.KEYCODE_SHIFT_LEFT, shift)
            assertFalse(host.backend.currentSnapshot().isAsciiMode)
            host.keyEvents.clear()
            host.hardware(KeyEvent.KEYCODE_N)
            host.awaitField("n")
            assertTrue(host.keyEvents.isEmpty())
        }

    @Test
    fun theKeyboardsOwnEnglishModeBecomesTheEnginesAsciiMode() =
        withHost(schema = PREDICTION_SCHEMA, fixtureAssetRoot = PREDICTION_FIXTURE) { host ->
            host.start()
            host.tapLanguageKey()
            host.main { assertEquals(InputMode.ENGLISH, host.controller.session?.mode) }
            host.hardware(KeyEvent.KEYCODE_A)
            host.main { assertEquals(InputMode.CHINESE, host.controller.session?.mode) }
            assertTrue(host.backend.currentSnapshot().isAsciiMode)
            // The scheme's own switch now brings Chinese back.
            host.hardware(KeyEvent.KEYCODE_SHIFT_LEFT, shift)
            assertFalse(host.backend.currentSnapshot().isAsciiMode)
            host.keyEvents.clear()
            host.typeHardware("ni")
            host.awaitField("ni")
            assertTrue(host.keyEvents.isEmpty())
        }

    @Test
    fun aFoldedLayoutTypesOnTheFullKeyboardWhileHardwareKeysAreUsed() =
        withHost(layout = "phone9") { host ->
            host.start()
            assertEquals(KeyboardLayoutKind.PHONE9, host.controller.layoutKind)
            host.typeHardware("ni")
            host.awaitField("ni")
            assertEquals(KeyboardLayoutKind.QWERTY26, host.controller.layoutKind)
            host.main { host.switchField() }
            host.awaitIdle()
            assertEquals("a new field keeps the full keyboard", KeyboardLayoutKind.QWERTY26, host.controller.layoutKind)
            host.main { host.controller.setHardwareKeyboardAttached(false) }
            host.awaitIdle()
            assertEquals(KeyboardLayoutKind.PHONE9, host.controller.layoutKind)
        }

    @Test
    fun keysTypedWhileTheSessionIsPreparedArriveInOrder() =
        withHost { host ->
            host.start(await = false)
            host.hardwareEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_N)
            host.hardwareEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_N)
            host.hardwareEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_I)
            host.hardwareEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_I)
            host.awaitField("ni")
        }

    @Test
    fun softAndHardwareKeysShareOneComposition() =
        withHost { host ->
            host.start()
            host.tap("n")
            host.hardware(KeyEvent.KEYCODE_I)
            host.awaitField("ni")
            val first = host.firstCandidate()
            host.hardware(KeyEvent.KEYCODE_SPACE)
            host.awaitField(first)
        }

    @Test
    fun aMovedSelectionDiscardsTheComposition() =
        withHost { host ->
            host.start(initial = "abc")
            host.typeHardware("ni")
            host.awaitField("abcni")
            host.main {
                host.field.setSelection(1)
                host.report(oldStart = 5, newStart = 1, candidatesStart = 3, candidatesEnd = 5)
            }
            host.awaitIdle()
            assertEquals("abc", host.text())
            assertFalse(host.backend.currentSnapshot().isComposing)
        }

    @Test
    fun finishInputDropsQueuedKeysAndForgetsSwallowedDowns() =
        withHost { host ->
            host.start()
            host.typeHardware("ni")
            host.awaitField("ni")
            host.hardwareEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
            host.awaitIdle()
            host.main { assertEquals(setOf(KeyEvent.KEYCODE_DPAD_RIGHT), host.controller.swallowedHardwareDowns) }
            host.main { host.finishInput() }
            host.awaitIdle()
            host.main { assertTrue(host.controller.swallowedHardwareDowns.isEmpty()) }
            host.keyEvents.clear()
            host.hardwareEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT)
            host.awaitIdle()
            assertTrue("nothing reaches a finished input: ${host.keyEvents}", host.keyEvents.isEmpty())
        }

    @Test
    fun aPredictionIsNotChosenByAHardwareKey() =
        withHost(schema = PREDICTION_SCHEMA, fixtureAssetRoot = PREDICTION_FIXTURE) { host ->
            host.start()
            typePredictionContext(host)
            host.keyEvents.clear()
            host.hardware(KeyEvent.KEYCODE_SPACE)
            assertEquals("the key went on to the app", 2, host.keyEvents.size)
            val snapshot = host.backend.currentSnapshot()
            assertFalse("the prediction is gone: $snapshot", snapshot.isPrediction)
            assertEquals("好 was not committed", -1, host.text().indexOf('好'))
        }

    @Test
    fun withoutAnEngineEveryKeyIsLeftToTheSystem() =
        withHost { host ->
            host.start()
            host.main { host.controller.setEngineNotice(EngineNotice(EngineNoticeKind.PREPARING)) }
            assertFalse(host.hardwareEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_N))
            assertFalse(host.hardwareEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_N))
            host.main {
                host.controller.setEngineNotice(null)
                host.controller.unbind()
            }
            assertFalse(host.hardwareEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_N))
            assertFalse(host.hardwareEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_N))
        }

    @Test
    fun forcedEnglishNumericAndKeyEventEditorsAreLeftToTheSystem() {
        val editors =
            listOf(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_CLASS_NUMBER,
            )
        for (type in editors) {
            withHost { host ->
                host.start(inputType = type)
                assertFalse("type $type", host.hardwareEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_N))
                assertFalse("type $type", host.hardwareEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_N))
                assertTrue(host.keyEvents.isEmpty())
            }
        }
    }

    @Test
    fun aPasswordFieldWithoutLearningProtectionIsLeftToTheSystem() =
        withHost(learningSuppression = false) { host ->
            host.start(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            assertFalse(host.hardwareEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_N))
            assertFalse(host.hardwareEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_N))
        }

    @Test
    fun aNoPersonalizedLearningFieldComposesButDoesNotLearn() {
        // Control: an ordinary field learns what hardware keys pick.
        val learned = withHost { host -> hardwarePickAndRetype(host, imeOptions = 0) }
        assertTrue("an ordinary field must learn: $learned", learned.second < learned.first)
        val incognito =
            withHost { host ->
                hardwarePickAndRetype(host, imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
            }
        assertEquals("an incognito pick must not be learned", incognito.first, incognito.second)
        assertNotEquals(-1, incognito.first)
    }

    @Test
    fun barOnlyShowsTheBarAndNoKeys() =
        withHost { host ->
            host.start()
            host.main { host.controller.setBarOnly(true) }
            host.typeHardware("ni")
            host.awaitField("ni")
            assertEquals(null, host.findOrNullOnMain(KeyboardView::class.java))
            assertTrue(host.findOrNullOnMain(CandidateBarView::class.java) != null)
            assertTrue("the bar lists candidates: ${host.barLabels()}", host.barLabels().isNotEmpty())
            val band = host.bandHeight()
            host.relayout()
            var height = 0
            host.main { height = host.controller.view.measuredHeight }
            assertEquals("the window is as tall as the bar", band, height)
        }

    @Test
    fun barOnlyCandidatesCanBeTapped() =
        withHost { host ->
            host.start()
            host.main { host.controller.setBarOnly(true) }
            host.typeHardware("shi")
            host.awaitField("shi")
            val label = host.barLabels()[1]
            host.tap(label, bar = true)
            host.awaitField(label.substringBefore(' '))
            assertFalse(host.editor.composing)
        }

    @Test
    fun barOnlyIdleShowsAnEmptyBarNotTheToolbar() =
        withHost { host ->
            host.start()
            host.main { host.controller.setBarOnly(true) }
            host.awaitIdle()
            assertTrue(host.toolbarLabelsNow().isEmpty())
            assertTrue(host.findOrNullOnMain(CandidateBarView::class.java) != null)
            assertTrue(host.barLabels().isEmpty())
        }

    @Test
    fun leavingBarOnlyBringsTheKeysBack() =
        withHost { host ->
            host.start()
            host.main { host.controller.setBarOnly(true) }
            host.awaitIdle()
            host.main { host.controller.setBarOnly(false) }
            host.awaitIdle()
            assertTrue(host.findOrNullOnMain(KeyboardView::class.java) != null)
            assertTrue(host.toolbarLabelsNow().isNotEmpty())
            host.tap("n")
            host.awaitField("n")
        }

    /** Picks the third `shi` candidate with a digit key, retypes `shi` in an ordinary field; its rank before and after. */
    private fun hardwarePickAndRetype(
        host: Host,
        imeOptions: Int,
    ): Pair<Int, Int> {
        host.start(imeOptions = imeOptions)
        host.typeHardware("shi")
        host.awaitComposing()
        val picked =
            host.backend
                .currentSnapshot()
                .candidates[2]
                .text
        host.hardware(KeyEvent.KEYCODE_3)
        host.awaitField(picked)
        host.main { host.reconnect(imeOptions = 0) }
        host.awaitIdle()
        host.typeHardware("shi")
        host.awaitComposing()
        val after =
            host.backend
                .currentSnapshot()
                .candidates
                .indexOfFirst { it.text == picked }
        return 2 to (if (after < 0) Int.MAX_VALUE else after)
    }
}
