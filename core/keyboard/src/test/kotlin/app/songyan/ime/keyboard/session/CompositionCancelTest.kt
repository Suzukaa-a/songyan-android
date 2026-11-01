package app.songyan.ime.keyboard.session

import app.songyan.ime.keyboard.engine.Candidate
import app.songyan.ime.keyboard.engine.EngineBackend
import app.songyan.ime.keyboard.engine.EngineBackendError
import app.songyan.ime.keyboard.engine.EngineBackendKind
import app.songyan.ime.keyboard.engine.EngineSnapshot
import app.songyan.ime.keyboard.engine.KeyboardSchemaDescriptor
import app.songyan.ime.keyboard.layout.KeyAction
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from KeyboardCoreTests.CompositionCancelTests: "clear / cancel" during an active
 * composition must not rely on a synthetic Esc, and a failed cancel must never look like success.
 */
class CompositionCancelTest {
    /**
     * Model stub: processKey("Esc") clears only when there is no previous segment; the dedicated
     * cancelComposition() always clears unconditionally.
     */
    private class PreviousSegmentEngine : EngineBackend {
        override val kind: EngineBackendKind = EngineBackendKind.DEMO_SIMULATOR
        override val inputUnavailableReason: String? = null
        override val availableSchemas = emptyList<KeyboardSchemaDescriptor>()
        override var spellingDemand: (() -> Boolean)? = null

        var dedicatedClearUsed = false
        var dedicatedClearCommit = ""
        private var input = ""
        private var previousSegment = false

        override suspend fun setup(
            sharedDirectory: String,
            userDirectory: String,
        ) = Unit

        override suspend fun selectSchema(schemaID: String) = Unit

        override suspend fun processKey(keyValue: String): EngineSnapshot {
            when (keyValue) {
                EngineBackend.ESC_KEY -> if (previousSegment) previousSegment = false else input = ""
                EngineBackend.BACKSPACE_KEY -> {
                    if (input.isNotEmpty()) input = input.dropLast(1)
                    if (input.isEmpty()) previousSegment = false
                }
                else -> input += keyValue
            }
            return currentSnapshot()
        }

        /** Partial commit: one candidate is committed and the remaining syllables keep composing. */
        override suspend fun selectCandidate(index: Int): EngineSnapshot {
            previousSegment = true
            return currentSnapshot().copy(committed = "早")
        }

        override suspend fun cancelComposition(): EngineSnapshot {
            dedicatedClearUsed = true
            input = ""
            previousSegment = false
            return currentSnapshot().copy(committed = dedicatedClearCommit)
        }

        override suspend fun nextPage(): EngineSnapshot = currentSnapshot()

        override suspend fun previousPage(): EngineSnapshot = currentSnapshot()

        override fun currentSnapshot(): EngineSnapshot =
            EngineSnapshot(
                candidates = if (input.isEmpty()) emptyList() else listOf(Candidate(0, input)),
                preedit = input,
                rawInput = input,
                isComposing = input.isNotEmpty(),
            )

        override suspend fun shutdown() = Unit

        override val statusSummary: String get() = "previous-segment"
    }

    /** A stub that only has the default Esc fallback. */
    private class EscFallbackEngine : EngineBackend {
        override val kind: EngineBackendKind = EngineBackendKind.DEMO_SIMULATOR
        override val inputUnavailableReason: String? = null
        override val availableSchemas = emptyList<KeyboardSchemaDescriptor>()
        override var spellingDemand: (() -> Boolean)? = null

        var escRejected = false
        private var input = ""

        override suspend fun setup(
            sharedDirectory: String,
            userDirectory: String,
        ) = Unit

        override suspend fun selectSchema(schemaID: String) = Unit

        override suspend fun processKey(keyValue: String): EngineSnapshot {
            if (keyValue == EngineBackend.ESC_KEY) {
                if (escRejected) throw EngineBackendError.OperationFailed("esc rejected")
                input = ""
            } else {
                input += keyValue
            }
            return currentSnapshot()
        }

        override suspend fun selectCandidate(index: Int): EngineSnapshot = currentSnapshot()

        override suspend fun nextPage(): EngineSnapshot = currentSnapshot()

        override suspend fun previousPage(): EngineSnapshot = currentSnapshot()

        override fun currentSnapshot(): EngineSnapshot =
            EngineSnapshot(
                candidates = if (input.isEmpty()) emptyList() else listOf(Candidate(0, input)),
                preedit = input,
                rawInput = input,
                isComposing = input.isNotEmpty(),
            )

        override suspend fun shutdown() = Unit

        override val statusSummary: String get() = "esc-fallback"
    }

    /** An engine whose cancel can separately fail after clearing, or keep composing. */
    private class FailableCancelEngine : EngineBackend {
        enum class Outcome { SUCCESS, READ_FAILS_AFTER_CLEAR, SESSION_INVALID, STILL_COMPOSING }

        override val kind: EngineBackendKind = EngineBackendKind.DEMO_SIMULATOR
        override var inputUnavailableReason: String? = null
        override val availableSchemas = emptyList<KeyboardSchemaDescriptor>()
        override var spellingDemand: (() -> Boolean)? = null
        override val statusSummary: String get() = "failable-cancel"

        var outcome = Outcome.SUCCESS
        var clearsPerformed = 0
        private var input = ""

        override suspend fun setup(
            sharedDirectory: String,
            userDirectory: String,
        ) = Unit

        override suspend fun selectSchema(schemaID: String) = Unit

        override suspend fun processKey(keyValue: String): EngineSnapshot {
            input += keyValue
            return currentSnapshot()
        }

        override suspend fun selectCandidate(index: Int): EngineSnapshot = currentSnapshot()

        override suspend fun nextPage(): EngineSnapshot = currentSnapshot()

        override suspend fun previousPage(): EngineSnapshot = currentSnapshot()

        override fun currentSnapshot(): EngineSnapshot =
            EngineSnapshot(
                candidates = if (input.isEmpty()) emptyList() else listOf(Candidate(0, input)),
                preedit = input,
                rawInput = input,
                isComposing = input.isNotEmpty(),
            )

        override suspend fun shutdown() = Unit

        override suspend fun cancelComposition(): EngineSnapshot =
            when (outcome) {
                Outcome.SUCCESS -> {
                    inputUnavailableReason = null
                    input = ""
                    clearsPerformed += 1
                    currentSnapshot()
                }
                // The clear ran but the post-state read failed: must throw, never return empty.
                Outcome.READ_FAILS_AFTER_CLEAR -> {
                    input = ""
                    clearsPerformed += 1
                    throw EngineBackendError.OperationFailed("post-clear state read failed")
                }
                Outcome.SESSION_INVALID -> throw EngineBackendError.EngineNotStarted()
                Outcome.STILL_COMPOSING -> currentSnapshot()
            }
    }

    @Test
    fun discardAfterPartialCommitClearsThroughTheDedicatedCapability() =
        runTest {
            val engine = PreviousSegmentEngine()
            val session = InputSession(engine, KeyboardLayoutKind.QWERTY26)
            val commits = mutableListOf<String>()
            var errors = 0
            session.onCommitted = { commits += it }
            session.onError = { errors += 1 }

            session.tap(KeyAction.Code("z"))
            session.tap(KeyAction.Code("c"))
            assertTrue(session.snapshot.isComposing)

            session.selectCandidate(0)
            assertEquals(listOf("早"), commits)
            assertTrue(session.snapshot.isComposing)
            assertTrue(engine.currentSnapshot().isComposing)

            assertTrue(session.discardComposition())
            assertTrue(engine.dedicatedClearUsed)
            assertFalse(session.snapshot.isComposing)
            assertTrue(session.snapshot.candidates.isEmpty())
            assertEquals("cancel must never commit", listOf("早"), commits)
            assertEquals(0, errors)
        }

    @Test
    fun aCommitAttachedToTheDedicatedCancelIsSuppressed() =
        runTest {
            val engine = PreviousSegmentEngine()
            engine.dedicatedClearCommit = "must not reach the document"
            val session = InputSession(engine, KeyboardLayoutKind.QWERTY26)
            val commits = mutableListOf<String>()
            session.onCommitted = { commits += it }

            session.tap(KeyAction.Code("n"))
            assertTrue(session.discardComposition())
            assertFalse(session.snapshot.isComposing)
            assertTrue("a commit attached to a cancel must be swallowed", commits.isEmpty())
        }

    @Test
    fun escFallbackSuccessAndFailureAreReportedTruthfully() =
        runTest {
            val engine = EscFallbackEngine()
            val session = InputSession(engine, KeyboardLayoutKind.QWERTY26)
            var errors = 0
            session.onError = { errors += 1 }

            session.tap(KeyAction.Code("n"))
            engine.escRejected = true
            assertFalse(session.discardComposition())
            assertTrue("a failed cancel keeps the real composition", session.snapshot.isComposing)
            assertEquals(1, errors)

            engine.escRejected = false
            assertTrue(session.discardComposition())
            assertFalse(session.snapshot.isComposing)
        }

    @Test
    fun readFailureAfterClearKeepsTheLastTrustedComposition() =
        runTest {
            val engine = FailableCancelEngine()
            val session = InputSession(engine, KeyboardLayoutKind.QWERTY26)
            val commits = mutableListOf<String>()
            var errors = 0
            session.onCommitted = { commits += it }
            session.onError = { errors += 1 }

            session.tap(KeyAction.Code("w"))
            session.tap(KeyAction.Code("o"))
            val trusted = session.snapshot
            val revision = session.compositionRevision

            engine.outcome = FailableCancelEngine.Outcome.READ_FAILS_AFTER_CLEAR
            assertFalse(session.discardComposition())
            assertEquals(1, engine.clearsPerformed)
            assertEquals("no fake empty snapshot may be applied", trusted, session.snapshot)
            assertTrue(session.snapshot.isComposing)
            assertEquals("a failure path must not bump the generation", revision, session.compositionRevision)
            assertTrue(commits.isEmpty())
            assertEquals(1, errors)
        }

    @Test
    fun invalidSessionCancelFailsWithoutApplying() =
        runTest {
            val engine = FailableCancelEngine()
            val session = InputSession(engine, KeyboardLayoutKind.QWERTY26)
            var errors = 0
            session.onError = { errors += 1 }

            session.tap(KeyAction.Code("n"))
            val trusted = session.snapshot
            engine.outcome = FailableCancelEngine.Outcome.SESSION_INVALID
            assertFalse(session.discardComposition())
            assertEquals(trusted, session.snapshot)
            assertEquals(1, errors)
        }

    @Test
    fun cancelReturningStillComposingAppliesTheRealStateAndReturnsFalse() =
        runTest {
            val engine = FailableCancelEngine()
            val session = InputSession(engine, KeyboardLayoutKind.QWERTY26)
            var errors = 0
            session.onError = { errors += 1 }

            session.tap(KeyAction.Code("n"))
            session.tap(KeyAction.Code("i"))
            engine.outcome = FailableCancelEngine.Outcome.STILL_COMPOSING
            assertFalse(session.discardComposition())
            assertTrue("the genuine remaining composition stays visible", session.snapshot.isComposing)
            assertEquals("ni", session.snapshot.rawInput)
            assertEquals(1, errors)
        }

    @Test
    fun idleUnavailableCancellationReachesTheEngineAndCanRetry() =
        runTest {
            val engine = FailableCancelEngine()
            val session = InputSession(engine, KeyboardLayoutKind.QWERTY26)
            val document = StringBuilder("existing text")
            var errors = 0
            session.onCommitted = { document.append(it) }
            session.onError = { errors += 1 }
            val trusted = session.snapshot
            val revision = session.compositionRevision

            engine.inputUnavailableReason = "unread first key"
            engine.outcome = FailableCancelEngine.Outcome.READ_FAILS_AFTER_CLEAR
            assertFalse(session.discardComposition())
            assertEquals(1, engine.clearsPerformed)
            assertEquals(trusted, session.snapshot)
            assertEquals(revision, session.compositionRevision)
            assertEquals("existing text", document.toString())

            engine.outcome = FailableCancelEngine.Outcome.SUCCESS
            assertTrue(session.discardComposition())
            assertEquals(2, engine.clearsPerformed)
            assertEquals(null, session.inputUnavailableReason)
            assertTrue(session.compositionRevision > revision)
            assertEquals("existing text", document.toString())
            assertEquals(1, errors)
        }

    @Test
    fun normalPartialCompositionCancelSucceeds() =
        runTest {
            val engine = FailableCancelEngine()
            val session = InputSession(engine, KeyboardLayoutKind.QWERTY26)
            val commits = mutableListOf<String>()
            var errors = 0
            session.onCommitted = { commits += it }
            session.onError = { errors += 1 }

            session.tap(KeyAction.Code("z"))
            session.tap(KeyAction.Code("c"))
            val before = session.compositionRevision
            assertTrue(session.discardComposition())
            assertFalse(session.snapshot.isComposing)
            assertTrue(session.snapshot.candidates.isEmpty())
            assertEquals(1, engine.clearsPerformed)
            assertTrue(session.compositionRevision > before)
            assertTrue(commits.isEmpty())
            assertEquals(0, errors)
        }
}
