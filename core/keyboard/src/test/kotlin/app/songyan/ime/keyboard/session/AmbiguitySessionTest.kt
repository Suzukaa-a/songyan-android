package app.songyan.ime.keyboard.session

import app.songyan.ime.keyboard.engine.AmbiguityApplyOutcome
import app.songyan.ime.keyboard.engine.AmbiguityLockMode
import app.songyan.ime.keyboard.engine.AmbiguitySpellingsReport
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Ported from KeyboardCoreTests.AmbiguitySessionTests: report, apply and restore through the session. */
class AmbiguitySessionTest {
    private suspend fun makeSession(): Pair<InputSession, ScriptedAmbiguityEngine> {
        val engine = ScriptedAmbiguityEngine()
        val session = InputSession(engine, KeyboardLayoutKind.MERGED14)
        // The physical-structure gate fails closed by default; these cases cover an authorized compact layout.
        session.compactSpellingLayout = true
        session.refresh()
        return session to engine
    }

    private fun report(
        input: String = "ug",
        segment: IntRange = 0 until 2,
        options: List<String> = listOf("chang", "shang"),
    ) = AmbiguitySpellingsReport(
        revisionInput = input,
        segment = segment,
        options = options,
        allSpellings = options,
        lockMode = AmbiguityLockMode.TOKEN_ALIAS,
        isComplete = true,
    )

    @Test
    fun theSessionForwardsTheEngineReport() =
        runTest {
            val (session, engine) = makeSession()
            engine.report = report()
            val forwarded = session.ambiguitySpellings()
            assertEquals(listOf("chang", "shang"), forwarded?.options)
            assertEquals(0 until 2, forwarded?.segment)
        }

    @Test
    fun applyForwardsTheCompareAndSwapAndRefreshesOnSuccess() =
        runTest {
            val (session, engine) = makeSession()
            val outcome = session.applyAmbiguityOption("shang", expectedInput = "ug", segment = 0 until 2)
            assertEquals(AmbiguityApplyOutcome.APPLIED, outcome)
            assertEquals(1, engine.applied.size)
            assertEquals("shang", engine.applied.first().third)
            assertEquals("refreshed from the real post-state", "9084", session.snapshot.rawInput)
        }

    @Test
    fun aFailedApplyDoesNotRefreshAndIsReported() =
        runTest {
            val (session, engine) = makeSession()
            engine.applyOutcome = AmbiguityApplyOutcome.STALE
            val outcome = session.applyAmbiguityOption("shang", expectedInput = "ug", segment = 0 until 2)
            assertEquals(AmbiguityApplyOutcome.STALE, outcome)
            assertEquals("no state change on failure", "ug", session.snapshot.rawInput)
        }

    @Test
    fun restoreForwardsAndRefreshes() =
        runTest {
            val (session, engine) = makeSession()
            val outcome = session.restoreAmbiguity("9084", 0 until 4, "shang", "ug")
            assertEquals(AmbiguityApplyOutcome.APPLIED, outcome)
            assertEquals(1, engine.restored.size)
            assertEquals("9084", engine.restored.first()[0])
            assertEquals("ug", engine.restored.first()[3])
            assertEquals("ug", session.snapshot.rawInput)
        }

    @Test
    fun anUnverifiedRollbackSurfacesDistinctly() =
        runTest {
            val (session, engine) = makeSession()
            engine.restoreOutcome = AmbiguityApplyOutcome.FAILED_ROLLBACK_UNVERIFIED
            val outcome = session.restoreAmbiguity("9084", 0 until 4, "shang", "ug")
            assertEquals(AmbiguityApplyOutcome.FAILED_ROLLBACK_UNVERIFIED, outcome)
        }

    @Test
    fun mergedBuForwardsBothOptionsAndAppliesNi() =
        runTest {
            val (session, engine) = makeSession()
            engine.snapshot = engine.snapshot.copy(preedit = "bu", rawInput = "bu")
            session.refresh()
            engine.report = report("bu", options = listOf("bu", "ni"))
            val forwarded = session.ambiguitySpellings()
            assertEquals(listOf("bu", "ni"), forwarded?.options)
            assertEquals(0 until 2, forwarded?.segment)
            engine.postApplyInput = "9070"
            val outcome = session.applyAmbiguityOption("ni", expectedInput = "bu", segment = 0 until 2)
            assertEquals(AmbiguityApplyOutcome.APPLIED, outcome)
            assertEquals("ni", engine.applied.single().third)
            assertEquals("9070", session.snapshot.rawInput)
        }

    @Test
    fun aPrefixReportForwardsItsNarrowingKeysAndSemantics() =
        runTest {
            val (session, engine) = makeSession()
            engine.snapshot = engine.snapshot.copy(preedit = "2", rawInput = "2")
            session.refresh()
            engine.report =
                AmbiguitySpellingsReport(
                    revisionInput = "2",
                    segment = 0 until 1,
                    options = listOf("a", "b", "c"),
                    allSpellings = listOf("a"),
                    lockMode = AmbiguityLockMode.PREFIX_TOKEN,
                    isComplete = true,
                    prefixOptions = listOf("a", "b", "c"),
                    optionSemantics = mapOf("a" to "mixed", "b" to "initial", "c" to "initial"),
                )
            val forwarded = session.ambiguitySpellings()
            assertEquals(AmbiguityLockMode.PREFIX_TOKEN, forwarded?.lockMode)
            assertEquals(listOf("a", "b", "c"), forwarded?.prefixOptions)
            assertEquals("initial", forwarded?.optionSemantics?.get("b"))
            engine.postApplyInput = "b"
            val outcome = session.applyAmbiguityOption("b", expectedInput = "2", segment = 0 until 1)
            assertEquals(AmbiguityApplyOutcome.APPLIED, outcome)
            assertEquals("b", engine.applied.first().third)
            assertEquals("b", session.snapshot.rawInput)
            // Restore is the ordinary first-Backspace contract, unchanged.
            val restore = session.restoreAmbiguity("b", 0 until 1, "b", "2")
            assertEquals(AmbiguityApplyOutcome.APPLIED, restore)
            assertEquals("2", engine.restored.first()[3])
        }
}
