package app.songyan.ime.keyboard.session

import app.songyan.ime.keyboard.StubEngine
import app.songyan.ime.keyboard.layout.KeyAction
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.keyboard.support.ShiftState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shift / Caps Lock in English direct input.
 *
 * The English letter page uses key codes, but merged and phone layouts reach the session as literals,
 * so both text-key kinds must obey Shift. These tests also pin the interaction with the host's
 * automatic caps updates: a late selection callback must not cancel a capital the user armed.
 */
class EnglishShiftCapsTest {
    private class Harness {
        val engine = StubEngine()
        val session = InputSession(engine, KeyboardLayoutKind.QWERTY26)
        val commits = mutableListOf<String>()

        init {
            session.onCommitted = { commits += it }
        }
    }

    @Test
    fun aLiteralLetterConsumesTheOneShotShift() =
        runTest {
            val h = Harness()
            h.session.setMode(InputMode.ENGLISH)
            h.session.tapShift()
            assertEquals(ShiftState.ONCE, h.session.shiftState)

            h.session.tap(KeyAction.Literal("c"))
            assertEquals(listOf("C"), h.commits)
            assertFalse("the one-shot shift is spent", h.session.shiftState.isActive)
        }

    @Test
    fun capsLockUppercasesEveryLiteralUntilToggledOff() =
        runTest {
            val h = Harness()
            h.session.setMode(InputMode.ENGLISH)
            h.session.doubleTapShift()
            assertEquals(ShiftState.LOCKED, h.session.shiftState)

            h.session.tap(KeyAction.Literal("a"))
            h.session.tap(KeyAction.Literal("b"))
            h.session.tap(KeyAction.Literal("c"))
            assertEquals(listOf("A", "B", "C"), h.commits)
            assertEquals(ShiftState.LOCKED, h.session.shiftState)

            h.session.doubleTapShift()
            h.session.tap(KeyAction.Literal("d"))
            assertEquals(listOf("A", "B", "C", "d"), h.commits)
        }

    @Test
    fun chineseModeLiteralsAreUnaffectedByShift() =
        runTest {
            val h = Harness()
            // Chinese mode arms Shift for the next key code (a capital letter); a literal is not a
            // letter key, so it is typed as it is and does not spend the one-shot.
            h.session.tapShift()
            assertEquals(ShiftState.ONCE, h.session.shiftState)
            h.session.tap(KeyAction.Literal("你"))
            assertEquals(listOf("你"), h.commits)
            assertEquals(ShiftState.ONCE, h.session.shiftState)
        }

    @Test
    fun punctuationKeepsTheShiftArmedForTheNextLetter() =
        runTest {
            val h = Harness()
            h.session.setMode(InputMode.ENGLISH)
            h.session.tapShift()

            h.session.tap(KeyAction.Literal(","))
            assertEquals(listOf(","), h.commits)
            assertTrue("punctuation must not spend the shift", h.session.shiftState.isActive)

            h.session.tap(KeyAction.Literal("d"))
            assertEquals(listOf(",", "D"), h.commits)
            assertFalse(h.session.shiftState.isActive)
        }

    @Test
    fun digitKeysKeepTheShiftArmed() =
        runTest {
            val h = Harness()
            h.session.setMode(InputMode.ENGLISH)
            h.session.tapShift()
            h.session.tap(KeyAction.Literal("1"))
            assertEquals(listOf("1"), h.commits)
            assertTrue(h.session.shiftState.isActive)
        }

    @Test
    fun aManualOneShotShiftSurvivesALateAutomaticCapsUpdate() =
        runTest {
            // The reported repro: type a,b, tap Shift, then a queued selection callback arrives with
            // caps off before the next letter. The capital must not be silently cancelled.
            val h = Harness()
            h.session.setMode(InputMode.ENGLISH)
            h.session.tap(KeyAction.Literal("a"))
            h.session.tap(KeyAction.Literal("b"))
            h.session.tapShift()

            h.session.setAutomaticShift(false)

            assertEquals(ShiftState.ONCE, h.session.shiftState)
            h.session.tap(KeyAction.Literal("c"))
            assertEquals(listOf("a", "b", "C"), h.commits)
        }

    @Test
    fun anAutomaticOneShotIsStillClearedByTheHost() =
        runTest {
            val h = Harness()
            h.session.setMode(InputMode.ENGLISH)
            h.session.setAutomaticShift(true)
            assertEquals(ShiftState.ONCE, h.session.shiftState)

            h.session.setAutomaticShift(false)
            assertEquals(ShiftState.OFF, h.session.shiftState)
            h.session.tap(KeyAction.Literal("f"))
            assertEquals(listOf("f"), h.commits)
        }

    @Test
    fun capsLockSurvivesAutomaticCapsUpdates() =
        runTest {
            val h = Harness()
            h.session.setMode(InputMode.ENGLISH)
            h.session.doubleTapShift()
            h.session.setAutomaticShift(false)
            assertEquals(ShiftState.LOCKED, h.session.shiftState)
            h.session.tap(KeyAction.Literal("g"))
            assertEquals(listOf("G"), h.commits)
        }

    @Test
    fun aKeyCodeLetterAlsoUppercasesAndSpendsTheOneShot() =
        runTest {
            val h = Harness()
            h.session.setMode(InputMode.ENGLISH)
            h.session.tapShift()
            h.session.tap(KeyAction.Code("h"))
            assertEquals(listOf("H"), h.commits)
            assertFalse(h.session.shiftState.isActive)
        }
}
