package app.songyan.ime.keyboard.session

import app.songyan.ime.keyboard.engine.AmbiguityApplyOutcome
import app.songyan.ime.keyboard.engine.AmbiguityLockMode
import app.songyan.ime.keyboard.engine.AmbiguitySpellingsReport
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from KeyboardCoreTests.AmbiguityQueryGateTests: the physical-structure gate fails closed for
 * every engine query and rewrite, is granted and revoked immediately, and is restored by every
 * failed layout transition. Complements [SpellingDemandGateTest].
 */
class AmbiguityQueryGateTest {
    private suspend fun makeSession(layout: KeyboardLayoutKind): Pair<InputSession, ScriptedAmbiguityEngine> {
        val engine = ScriptedAmbiguityEngine()
        val session = InputSession(engine, layout)
        session.onDispatchCommit = { true }
        session.refresh()
        assertTrue(session.snapshot.isComposing)
        return session to engine
    }

    private fun report() =
        AmbiguitySpellingsReport(
            revisionInput = "ug",
            segment = 0 until 2,
            options = listOf("chang", "shang"),
            allSpellings = listOf("chang", "shang"),
            lockMode = AmbiguityLockMode.TOKEN_ALIAS,
            isComplete = true,
        )

    @Test
    fun anUnauthorizedPhysicalLayoutQueriesNothingEvenWhenTheKindIsCompact() =
        runTest {
            for (layout in listOf(KeyboardLayoutKind.PHONE9, KeyboardLayoutKind.MERGED14)) {
                val (session, engine) = makeSession(layout)
                assertFalse("$layout fails closed by default", session.compactSpellingLayout)
                assertNull(session.ambiguitySpellings())
                assertNull(session.readCandidatesWithReadings(0, 32))
                assertEquals("$layout: no spelling query", 0, engine.ambiguityCalls)
                assertEquals("$layout: no reading read", 0, engine.readingCalls)
            }
        }

    @Test
    fun aTwentySixKeyKindQueriesNothing() =
        runTest {
            val (session, engine) = makeSession(KeyboardLayoutKind.QWERTY26)
            session.compactSpellingLayout = false
            assertNull(session.ambiguitySpellings())
            assertNull(session.readCandidatesWithReadings(0, 32))
            assertEquals(0, engine.ambiguityCalls)
            assertEquals(0, engine.readingCalls)
        }

    @Test
    fun aGrantedCapabilityForwardsEachQueryExactlyOnce() =
        runTest {
            val (session, engine) = makeSession(KeyboardLayoutKind.MERGED14)
            session.compactSpellingLayout = true
            engine.report = report()
            assertEquals(listOf("chang", "shang"), session.ambiguitySpellings()?.options)
            assertNotNull(session.readCandidatesWithReadings(4, 32))
            assertEquals(1, engine.ambiguityCalls)
            assertEquals(1, engine.readingCalls)
        }

    @Test
    fun aWithdrawnCapabilityStopsQueryingImmediately() =
        runTest {
            val (session, engine) = makeSession(KeyboardLayoutKind.MERGED14)
            session.compactSpellingLayout = true
            engine.report = report()
            session.ambiguitySpellings()
            session.readCandidatesWithReadings(0, 32)
            assertEquals(1, engine.ambiguityCalls)
            assertEquals(1, engine.readingCalls)

            session.compactSpellingLayout = false
            assertNull(session.ambiguitySpellings())
            assertNull(session.readCandidatesWithReadings(0, 32))
            assertEquals("no more queries after revocation", 1, engine.ambiguityCalls)
            assertEquals("no more reading reads after revocation", 1, engine.readingCalls)
        }

    @Test
    fun anUnauthorizedPhysicalLayoutPerformsNoRewrite() =
        runTest {
            for (layout in listOf(
                KeyboardLayoutKind.PHONE9,
                KeyboardLayoutKind.MERGED14,
                KeyboardLayoutKind.QWERTY26,
            )) {
                val (session, engine) = makeSession(layout)
                assertFalse("$layout fails closed by default", session.compactSpellingLayout)
                assertEquals(
                    AmbiguityApplyOutcome.UNSUPPORTED,
                    session.applyAmbiguityOption("shang", expectedInput = "ug", segment = 0 until 2),
                )
                assertEquals(
                    AmbiguityApplyOutcome.UNSUPPORTED,
                    session.restoreAmbiguity("9084", 0 until 4, "shang", "ug"),
                )
                assertTrue("$layout: no apply when unauthorized", engine.applied.isEmpty())
                assertTrue("$layout: no restore when unauthorized", engine.restored.isEmpty())
            }
        }

    @Test
    fun aGrantedCapabilityForwardsRewritesVerbatim() =
        runTest {
            val (session, engine) = makeSession(KeyboardLayoutKind.MERGED14)
            session.compactSpellingLayout = true
            assertEquals(
                AmbiguityApplyOutcome.APPLIED,
                session.applyAmbiguityOption("shang", expectedInput = "ug", segment = 0 until 2),
            )
            assertEquals(
                AmbiguityApplyOutcome.APPLIED,
                session.restoreAmbiguity("9084", 0 until 4, "shang", "ug"),
            )
            assertEquals(Triple("ug", 0 until 2, "shang"), engine.applied.single())
            assertEquals(listOf<Any>("9084", 0 until 4, "shang", "ug"), engine.restored.single())
        }

    @Test
    fun aWithdrawnCapabilityStopsRewritesImmediately() =
        runTest {
            val (session, engine) = makeSession(KeyboardLayoutKind.MERGED14)
            session.compactSpellingLayout = true
            session.applyAmbiguityOption("shang", expectedInput = "ug", segment = 0 until 2)
            session.restoreAmbiguity("9084", 0 until 4, "shang", "ug")
            assertEquals(1, engine.applied.size)
            assertEquals(1, engine.restored.size)

            session.compactSpellingLayout = false
            assertEquals(
                AmbiguityApplyOutcome.UNSUPPORTED,
                session.applyAmbiguityOption("chang", expectedInput = "ug", segment = 0 until 2),
            )
            assertEquals(
                AmbiguityApplyOutcome.UNSUPPORTED,
                session.restoreAmbiguity("9084", 0 until 4, "shang", "ug"),
            )
            assertEquals(1, engine.applied.size)
            assertEquals(1, engine.restored.size)
        }

    @Test
    fun aLayoutSwitchInvalidatesTheCapabilityBeforeAnyRefresh() =
        runTest {
            val (session, engine) = makeSession(KeyboardLayoutKind.MERGED14)
            session.compactSpellingLayout = true
            engine.report = report()
            val capabilityAtUpdate = mutableListOf<Boolean>()
            session.onUpdate = { capabilityAtUpdate += session.compactSpellingLayout }

            session.selectLayout(KeyboardLayoutKind.QWERTY26)

            assertTrue("the switch must have refreshed", capabilityAtUpdate.isNotEmpty())
            assertFalse("no update may see the old eligibility", capabilityAtUpdate.contains(true))
            assertFalse(session.compactSpellingLayout)
            assertEquals("zero queries during the switch", 0, engine.ambiguityCalls)
        }

    @Test
    fun aFailedTransitionRestoresTheCapabilityAndQueriesNothing() =
        runTest {
            val (session, engine) = makeSession(KeyboardLayoutKind.MERGED14)
            session.compactSpellingLayout = true
            engine.report = report()
            engine.rejectSchemaIDs = setOf("songyan_quanpin")

            session.selectLayout(KeyboardLayoutKind.QWERTY26, "songyan_quanpin")

            assertEquals(KeyboardLayoutKind.MERGED14, session.layout)
            assertEquals("zero queries during a failed switch", 0, engine.ambiguityCalls)
            assertTrue("a failure restores the pre-switch eligibility", session.compactSpellingLayout)
            assertNotNull(session.ambiguitySpellings())
            assertEquals(1, engine.ambiguityCalls)
        }

    @Test
    fun selectingTheCurrentLayoutIsANoOpThatKeepsTheCapability() =
        runTest {
            val (session, engine) = makeSession(KeyboardLayoutKind.MERGED14)
            session.compactSpellingLayout = true
            engine.report = report()

            session.selectLayout(KeyboardLayoutKind.MERGED14)
            assertTrue(session.compactSpellingLayout)
            assertNotNull(session.ambiguitySpellings())
            assertEquals(1, engine.ambiguityCalls)

            session.selectLayout(KeyboardLayoutKind.MERGED14, session.selectedSchemaID)
            assertTrue("reselecting layout and schema keeps the proof", session.compactSpellingLayout)
            assertEquals(1, engine.ambiguityCalls)
        }

    @Test
    fun everyFailurePathOfASwitchRestoresTheCapability() =
        runTest {
            // Validation failure on a bound entry.
            val (validate, validateEngine) = makeSession(KeyboardLayoutKind.MERGED14)
            validate.compactSpellingLayout = true
            validateEngine.rejectSchemaIDs = setOf("missing_schema")
            validate.selectLayout(KeyboardLayoutKind.QWERTY26, "missing_schema")
            assertEquals(KeyboardLayoutKind.MERGED14, validate.layout)
            assertTrue(validate.compactSpellingLayout)

            // The composition cannot be confirmed.
            val (confirm, confirmEngine) = makeSession(KeyboardLayoutKind.MERGED14)
            confirm.compactSpellingLayout = true
            confirmEngine.commitSucceeds = false
            confirm.selectLayout(KeyboardLayoutKind.QWERTY26)
            assertEquals(KeyboardLayoutKind.MERGED14, confirm.layout)
            assertTrue(confirm.compactSpellingLayout)

            // Validation passes but the engine rejects the schema.
            val (select, selectEngine) = makeSession(KeyboardLayoutKind.MERGED14)
            select.compactSpellingLayout = true
            selectEngine.failSchemaSelect = true
            select.selectLayout(KeyboardLayoutKind.QWERTY26)
            assertEquals(KeyboardLayoutKind.MERGED14, select.layout)
            assertTrue(select.compactSpellingLayout)
        }

    @Test
    fun confirmingForAConfigurationChangeRestoresTheCapability() =
        runTest {
            val (session, _) = makeSession(KeyboardLayoutKind.MERGED14)
            session.compactSpellingLayout = true
            assertTrue(session.prepareForConfigurationChange())
            assertTrue("a confirmation does not change the definition", session.compactSpellingLayout)
        }
}
