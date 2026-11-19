package app.songyan.ime.keyboard.session

import app.songyan.ime.keyboard.engine.Candidate
import app.songyan.ime.keyboard.engine.EngineBackend
import app.songyan.ime.keyboard.engine.EngineBackendError
import app.songyan.ime.keyboard.engine.EngineBackendKind
import app.songyan.ime.keyboard.engine.EngineSnapshot
import app.songyan.ime.keyboard.engine.KeyboardSchemaDescriptor
import app.songyan.ime.keyboard.engine.RawKeyResult
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputSessionRawKeyTest {
    private class RawEngine(
        private val supportsRaw: Boolean = true,
    ) : EngineBackend {
        override val kind: EngineBackendKind = EngineBackendKind.DEMO_SIMULATOR
        override val inputUnavailableReason: String? = null
        override val availableSchemas = emptyList<KeyboardSchemaDescriptor>()
        override var spellingDemand: (() -> Boolean)? = null
        override val statusSummary: String get() = "raw"

        var next: RawKeyResult? = null
        var failure: EngineBackendError? = null
        var current = EngineSnapshot()

        override suspend fun setup(
            sharedDirectory: String,
            userDirectory: String,
        ) = Unit

        override suspend fun selectSchema(schemaID: String) = Unit

        override suspend fun processKey(keyValue: String): EngineSnapshot = current

        override suspend fun processRawKey(
            keycode: Int,
            modifiers: Int,
        ): RawKeyResult? {
            if (!supportsRaw) return null
            failure?.let { throw it }
            val result = next ?: return null
            current = result.snapshot.copy(committed = "")
            return result
        }

        override suspend fun selectCandidate(index: Int): EngineSnapshot = current

        override suspend fun nextPage(): EngineSnapshot = current

        override suspend fun previousPage(): EngineSnapshot = current

        override fun currentSnapshot(): EngineSnapshot = current

        override suspend fun shutdown() = Unit
    }

    private fun composing(text: String) =
        EngineSnapshot(
            candidates = listOf(Candidate(0, text)),
            preedit = text,
            rawInput = text,
            isComposing = true,
        )

    private fun session(engine: EngineBackend) = InputSession(engine, KeyboardLayoutKind.QWERTY26)

    @Test
    fun consumedKeyAppliesTheSnapshotAndDeliversItsCommit() =
        runTest {
            val engine = RawEngine()
            val session = session(engine)
            val commits = mutableListOf<String>()
            var updates = 0
            session.onCommitted = { commits += it }
            session.onUpdate = { updates++ }
            engine.next = RawKeyResult(true, EngineSnapshot(committed = "你"))
            assertEquals(true, session.rawKey(0x20, 0))
            assertEquals(listOf("你"), commits)
            assertEquals(1, updates)
        }

    @Test
    fun anUnchangedReleaseDoesNotUpdate() =
        runTest {
            val engine = RawEngine()
            val session = session(engine)
            engine.next = RawKeyResult(true, composing("ni"))
            session.rawKey(0x69, 0)
            var updates = 0
            session.onUpdate = { updates++ }
            engine.next = RawKeyResult(false, composing("ni"))
            assertEquals(false, session.rawKey(0x69, 1 shl 30))
            assertEquals(0, updates)
        }

    @Test
    fun aBackendWithoutRawKeysReturnsNull() =
        runTest {
            val session = session(RawEngine(supportsRaw = false))
            assertNull(session.rawKey(0x61, 0))
        }

    @Test
    fun aBackendErrorIsReportedAndKeepsTheComposition() =
        runTest {
            val engine = RawEngine()
            val session = session(engine)
            engine.next = RawKeyResult(true, composing("ni"))
            session.rawKey(0x69, 0)
            val errors = mutableListOf<Throwable>()
            session.onError = { errors += it }
            engine.failure = EngineBackendError.OperationFailed("boom")
            assertTrue(session.rawKey(0x68, 0) == true)
            assertEquals(1, errors.size)
            assertTrue(session.snapshot.isComposing)
        }

    @Test
    fun aRetiredSessionTakesNothing() =
        runTest {
            val engine = RawEngine()
            val session = session(engine)
            engine.next = RawKeyResult(true, composing("ni"))
            session.retire()
            assertNull(session.rawKey(0x69, 0))
            assertFalse(session.snapshot.isComposing)
        }
}
