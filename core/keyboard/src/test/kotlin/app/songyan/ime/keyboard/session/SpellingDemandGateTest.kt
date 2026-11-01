package app.songyan.ime.keyboard.session

import app.songyan.ime.keyboard.engine.AmbiguityApplyOutcome
import app.songyan.ime.keyboard.engine.AmbiguityLockMode
import app.songyan.ime.keyboard.engine.AmbiguitySpellingsReport
import app.songyan.ime.keyboard.engine.Candidate
import app.songyan.ime.keyboard.engine.EngineBackend
import app.songyan.ime.keyboard.engine.EngineBackendKind
import app.songyan.ime.keyboard.engine.EngineSnapshot
import app.songyan.ime.keyboard.engine.KeyboardSchemaDescriptor
import app.songyan.ime.keyboard.engine.ReadingCandidate
import app.songyan.ime.keyboard.layout.KeyAction
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from KeyboardCoreTests.SpellingDemandGateTests / AmbiguityQueryGateTests: the physical
 * layout gate must fail closed. A layout whose definition was not proven to be a compact spelling
 * layout (including a 26-key definition mislabelled as a merged/phone layout) must reach neither the
 * engine's spelling query nor its rewrite.
 */
class SpellingDemandGateTest {
    private class RecordingEngine : EngineBackend {
        override val kind: EngineBackendKind = EngineBackendKind.DEMO_SIMULATOR
        override val inputUnavailableReason: String? = null
        override val availableSchemas = emptyList<KeyboardSchemaDescriptor>()
        override var spellingDemand: (() -> Boolean)? = null
        override val statusSummary: String get() = "recording"
        override val supportsReadOnlyBrowsing: Boolean get() = true

        var ambiguityQueries = 0
        var readingQueries = 0
        var rewrites = 0
        var restores = 0
        private var input = ""

        var failSchemaSelect = false

        override suspend fun setup(
            sharedDirectory: String,
            userDirectory: String,
        ) = Unit

        override suspend fun selectSchema(schemaID: String) {
            if (failSchemaSelect) {
                throw app.songyan.ime.keyboard.engine.EngineBackendError
                    .OperationFailed("rejected")
            }
        }

        override suspend fun processKey(keyValue: String): EngineSnapshot {
            when (keyValue) {
                EngineBackend.BACKSPACE_KEY -> if (input.isNotEmpty()) input = input.dropLast(1)
                EngineBackend.RETURN_KEY -> {
                    val committed = "你"
                    input = ""
                    return currentSnapshot().copy(committed = committed)
                }
                else -> input += keyValue
            }
            return currentSnapshot()
        }

        override suspend fun selectCandidate(index: Int): EngineSnapshot {
            input = ""
            return currentSnapshot().copy(committed = "好")
        }

        override suspend fun nextPage(): EngineSnapshot = currentSnapshot()

        override suspend fun previousPage(): EngineSnapshot = currentSnapshot()

        override fun currentSnapshot(): EngineSnapshot =
            EngineSnapshot(
                candidates = if (input.isEmpty()) emptyList() else listOf(Candidate(0, "你")),
                preedit = input,
                rawInput = input,
                isComposing = input.isNotEmpty(),
                pageSize = if (input.isEmpty()) 0 else 1,
            )

        override suspend fun shutdown() = Unit

        override suspend fun ambiguitySpellings(): AmbiguitySpellingsReport {
            ambiguityQueries += 1
            return AmbiguitySpellingsReport(
                revisionInput = input,
                segment = 0 until input.length,
                options = listOf("gang", "hang"),
                allSpellings = listOf("gang", "hang"),
                lockMode = AmbiguityLockMode.TOKEN_ALIAS,
                isComplete = true,
            )
        }

        override suspend fun readCandidatesWithReadings(
            fromIndex: Int,
            count: Int,
        ): List<ReadingCandidate> {
            readingQueries += 1
            return listOf(ReadingCandidate(0, "你", readingPaths = listOf(listOf("ni"))))
        }

        override suspend fun applyAmbiguitySpelling(
            expectedInput: String,
            segment: IntRange,
            option: String,
        ): AmbiguityApplyOutcome {
            rewrites += 1
            return AmbiguityApplyOutcome.APPLIED
        }

        override suspend fun restoreAmbiguitySpelling(
            expectedInput: String,
            segment: IntRange,
            expectedOption: String,
            reducedCode: String,
        ): AmbiguityApplyOutcome {
            restores += 1
            return AmbiguityApplyOutcome.APPLIED
        }
    }

    @Test
    fun aLayoutNotProvenCompactReachesNoEngineQuery() =
        runTest {
            val engine = RecordingEngine()
            val session = InputSession(engine, KeyboardLayoutKind.PHONE9)
            session.tap(KeyAction.Code("6"))
            session.tap(KeyAction.Code("4"))

            assertNull(session.ambiguitySpellings())
            assertNull(session.readCandidatesWithReadings(0, 4))
            assertEquals(AmbiguityApplyOutcome.UNSUPPORTED, session.applyAmbiguityOption("gang", "64", 0..2))
            assertEquals(
                AmbiguityApplyOutcome.UNSUPPORTED,
                session.restoreAmbiguity("64", 0..2, "gang", "64"),
            )
            assertEquals("no spelling query may reach the engine", 0, engine.ambiguityQueries)
            assertEquals(0, engine.readingQueries)
            assertEquals(0, engine.rewrites)
            assertEquals(0, engine.restores)
        }

    @Test
    fun theInjectedCompactLayoutGateOpensTheQueries() =
        runTest {
            val engine = RecordingEngine()
            val session = InputSession(engine, KeyboardLayoutKind.MERGED14)
            session.compactSpellingLayout = true
            session.tap(KeyAction.Code("g"))
            session.tap(KeyAction.Code("c"))

            val report = requireNotNull(session.ambiguitySpellings())
            assertEquals(listOf("gang", "hang"), report.options)
            assertEquals(1, engine.ambiguityQueries)
            assertTrue(session.readCandidatesWithReadings(0, 4)!!.isNotEmpty())
            assertEquals(1, engine.readingQueries)
            assertEquals(AmbiguityApplyOutcome.APPLIED, session.applyAmbiguityOption("gang", "gc", 0..2))
            assertEquals(1, engine.rewrites)
        }

    @Test
    fun theSpellingDemandClosureFollowsTheGate() =
        runTest {
            val engine = RecordingEngine()
            val session = InputSession(engine, KeyboardLayoutKind.MERGED14)
            // The session installs the closure at construction, so the engine reads the live gate.
            assertFalse("the gate starts closed", requireNotNull(engine.spellingDemand).invoke())
            session.compactSpellingLayout = true
            assertTrue(requireNotNull(engine.spellingDemand).invoke())
            session.compactSpellingLayout = false
            assertFalse(requireNotNull(engine.spellingDemand).invoke())
        }

    @Test
    fun aLayoutSwitchClosesTheGateUntilTheFrontEndReauthorizesIt() =
        runTest {
            val engine = RecordingEngine()
            val session = InputSession(engine, KeyboardLayoutKind.MERGED14)
            session.onCommitted = { }
            session.compactSpellingLayout = true
            session.tap(KeyAction.Code("g"))
            session.tap(KeyAction.Code("c"))

            session.selectLayout(KeyboardLayoutKind.QWERTY26)
            assertFalse("a switch always closes the gate", session.compactSpellingLayout)
            assertNull(session.ambiguitySpellings())
            assertEquals("a closed gate never reaches the engine", 0, engine.ambiguityQueries)
        }

    @Test
    fun aFailedSwitchRestoresThePreviousGate() =
        runTest {
            val engine = RecordingEngine()
            val session = InputSession(engine, KeyboardLayoutKind.MERGED14)
            session.onCommitted = { }
            session.compactSpellingLayout = true
            session.tap(KeyAction.Code("g"))
            session.tap(KeyAction.Code("c"))
            var errors = 0
            session.onError = { errors += 1 }
            // The engine refuses the schema activation, so the transition must be rolled back.
            engine.failSchemaSelect = true
            session.selectLayout(KeyboardLayoutKind.QWERTY26)
            assertEquals(KeyboardLayoutKind.MERGED14, session.layout)
            assertTrue("a rejected transition keeps the proof", session.compactSpellingLayout)
            assertEquals(1, errors)
        }
}
