package app.songyan.ime.candidate

import app.songyan.ime.keyboard.engine.AmbiguityLockMode
import app.songyan.ime.keyboard.engine.AmbiguitySpellingsReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from CandidateKitTests.AmbiguitySpellingPickerTests. */
class AmbiguitySpellingPickerTest {
    private fun report(
        options: List<String>,
        all: List<String>,
        complete: Boolean = true,
        mode: AmbiguityLockMode = AmbiguityLockMode.TOKEN_ALIAS,
        input: String = "ug",
    ) = AmbiguitySpellingsReport(
        revisionInput = input,
        segment = 0 until input.toByteArray().size,
        options = options,
        allSpellings = all,
        lockMode = mode,
        isComplete = complete,
    )

    private fun available(
        options: List<String>,
        revision: Int = 1,
        input: String = "ug",
    ) = AmbiguitySpellingPicker().also {
        it.update(report(options, options, input = input), revision, isEligible = true)
    }

    @Test
    fun availableOnlyExposesLockableOptions() {
        val picker = AmbiguitySpellingPicker()
        picker.update(
            report(listOf("chang", "cheng"), listOf("chang", "cheng", "shang")),
            revision = 3,
            isEligible = true,
        )
        assertEquals(AmbiguitySpellingPicker.Phase.AVAILABLE, picker.phase)
        assertTrue(picker.showsPicker)
        assertEquals(listOf("chang", "cheng"), picker.report?.options)
        assertEquals(listOf("chang", "cheng", "shang"), picker.allSpellings)
        assertEquals(3, picker.revision)
    }

    @Test
    fun fewerThanTwoLockableHidesPickerButKeepsDiagnostics() {
        val picker = AmbiguitySpellingPicker()
        picker.update(report(listOf("chang"), listOf("chang", "cheng")), revision = 1, isEligible = true)
        assertEquals(AmbiguitySpellingPicker.Phase.IDLE, picker.phase)
        assertFalse(picker.showsPicker)
        assertNull(picker.report)
        assertEquals(listOf("chang", "cheng"), picker.allSpellings)
    }

    @Test
    fun incompleteReportIsNeverPresentedAsFullPicker() {
        val picker = AmbiguitySpellingPicker()
        picker.update(report(listOf("a", "b"), listOf("a", "b"), complete = false), revision = 1, isEligible = true)
        assertEquals(AmbiguitySpellingPicker.Phase.IDLE, picker.phase)
        assertNull(picker.report)
        assertNotNull(picker.problem)
    }

    @Test
    fun ineligibleClears() {
        val picker = available(listOf("a", "b"))
        picker.update(null, revision = 2, isEligible = false)
        assertEquals(AmbiguitySpellingPicker.Phase.IDLE, picker.phase)
        assertNull(picker.report)
    }

    @Test
    fun staleRevisionAndInputRejected() {
        val picker = available(listOf("a", "b"), revision = 5)
        assertTrue(picker.accepts("a", revision = 5, input = "ug"))
        assertFalse(picker.accepts("a", revision = 6, input = "ug"))
        assertFalse(picker.accepts("a", revision = 5, input = "ugx"))
        assertFalse(picker.accepts("z", revision = 5, input = "ug"))
    }

    @Test
    fun lockLifecycleRecordsOriginalAndRewritten() {
        val picker = available(listOf("shang", "chang"), revision = 7)
        picker.markLocked("shang", "ug", 0 until 2, "9084", 0 until 4)
        assertEquals(AmbiguitySpellingPicker.Phase.LOCKED, picker.phase)
        assertTrue(picker.canUndoLock)
        assertTrue(picker.showsPicker)
        assertEquals("ug", picker.lock?.originalInput)
        assertEquals("shang", picker.lock?.chosenOption)
        assertEquals("9084", picker.lock?.rewrittenInput)
        // A locked picker stays locked across an unrelated refresh that keeps the input.
        picker.update(null, revision = 8, isEligible = true)
        assertEquals(AmbiguitySpellingPicker.Phase.LOCKED, picker.phase)
        picker.update(report(listOf("shang"), listOf("shang")), revision = 9, isEligible = true)
        assertEquals(AmbiguitySpellingPicker.Phase.LOCKED, picker.phase)
        picker.update(
            report(listOf("shang", "chang"), listOf("shang", "chang"), complete = false),
            revision = 10,
            isEligible = true,
        )
        assertEquals(AmbiguitySpellingPicker.Phase.LOCKED, picker.phase)
        picker.markUnlocked()
        assertEquals(AmbiguitySpellingPicker.Phase.IDLE, picker.phase)
        assertNull(picker.lock)
    }

    @Test
    fun failureEntersErrorAndDropsFakeButtons() {
        val picker = available(listOf("a", "b"))
        picker.fail(AmbiguitySpellingPicker.Problem.STALE)
        assertEquals(AmbiguitySpellingPicker.Phase.ERROR, picker.phase)
        assertNull(picker.report)
        assertEquals(AmbiguitySpellingPicker.Problem.STALE, picker.problem)
        assertFalse(picker.showsPicker)
    }

    @Test
    fun failedRestoreKeepsTheLock() {
        val picker = available(listOf("shang", "chang"))
        picker.markLocked("shang", "ug", 0 until 2, "9084", 0 until 4)
        picker.markRestoreFailed(AmbiguitySpellingPicker.Problem.RESTORE_FAILED)
        assertTrue(picker.canUndoLock)
        assertEquals(AmbiguitySpellingPicker.Problem.RESTORE_FAILED, picker.problem)
    }

    @Test
    fun mergedTrajectoryBuExposesBuAndNi() {
        val picker = available(listOf("bu", "ni"), revision = 2, input = "bu")
        assertEquals(AmbiguitySpellingPicker.Phase.AVAILABLE, picker.phase)
        assertTrue(picker.showsPicker)
        assertEquals(listOf("bu", "ni"), picker.report?.options)
        assertTrue(picker.accepts("bu", revision = 2, input = "bu"))
        assertTrue(picker.accepts("ni", revision = 2, input = "bu"))
        assertFalse(picker.accepts("ni", revision = 3, input = "bu"))
        assertFalse(picker.accepts("ni", revision = 2, input = "bug"))
    }

    @Test
    fun lockMatchesUsesRewrittenInput() {
        val picker = available(listOf("shang", "chang"))
        picker.markLocked("shang", "ug", 0 until 2, "9084", 0 until 4)
        assertTrue(picker.lockMatches("9084"))
        assertFalse(picker.lockMatches("ug"))
        assertFalse(picker.lockMatches("9084x"))
        assertFalse(AmbiguitySpellingPicker().lockMatches("9084"))
    }
}
