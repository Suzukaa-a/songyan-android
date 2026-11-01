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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from KeyboardCoreTests.ReadCandidateRevisionTests: read-only cross-page browsing must not
 * count as an input change (no revision bump, no snapshot change), a failed read must propagate
 * instead of collapsing into an empty page, and page-local slot keys stay stable across pages.
 */
class ReadCandidatePageTest {
    private class ReadBrowseEngine : EngineBackend {
        override val kind: EngineBackendKind = EngineBackendKind.DEMO_SIMULATOR
        override val inputUnavailableReason: String? = null
        override val availableSchemas = emptyList<KeyboardSchemaDescriptor>()
        override var spellingDemand: (() -> Boolean)? = null
        override val statusSummary: String get() = "read-browse"

        var readOnlyEnabled = true
        override val supportsReadOnlyBrowsing: Boolean get() = readOnlyEnabled
        var currentSchemaID = "keyboard_3d7821f984693864ae91_14"
        var readFailure: EngineBackendError? = null
        var readCount = 0

        private var input = ""
        private var composing = false
        private var cached = EngineSnapshot()

        override suspend fun setup(
            sharedDirectory: String,
            userDirectory: String,
        ) = Unit

        override suspend fun selectSchema(schemaID: String) {
            currentSchemaID = schemaID
            input = ""
            composing = false
            cached = produce()
        }

        override suspend fun processKey(keyValue: String): EngineSnapshot {
            when (keyValue) {
                EngineBackend.BACKSPACE_KEY -> if (input.isNotEmpty()) input = input.dropLast(1)
                EngineBackend.RETURN_KEY -> input = ""
                else -> input += keyValue
            }
            composing = input.isNotEmpty()
            cached = produce()
            return cached
        }

        override suspend fun selectCandidate(index: Int): EngineSnapshot {
            input = ""
            composing = false
            val committed = produce().copy(committed = "好")
            cached = committed.copy(committed = "")
            return committed
        }

        override suspend fun nextPage(): EngineSnapshot = currentSnapshot()

        override suspend fun previousPage(): EngineSnapshot = currentSnapshot()

        override fun currentSnapshot(): EngineSnapshot = cached.copy(committed = "")

        override suspend fun shutdown() = Unit

        override suspend fun readCandidates(
            fromIndex: Int,
            count: Int,
        ): List<Candidate> {
            readCount += 1
            readFailure?.let { throw it }
            val pageSize = SLOT_KEYS.size
            return (0 until count).map {
                val slot = (fromIndex + it) % pageSize
                Candidate(
                    index = it,
                    text = "p" + (fromIndex + it),
                    selectKey = SLOT_KEYS[slot],
                    selectLabel = SLOT_LABELS[slot],
                )
            }
        }

        private fun produce(): EngineSnapshot =
            EngineSnapshot(
                schemaID = currentSchemaID,
                candidates = if (composing) listOf(Candidate(0, "你")) else emptyList(),
                preedit = input,
                rawInput = input,
                isComposing = composing,
                highlightedIndex = if (composing) 0 else -1,
                pageSize = 5,
                isLastPage = false,
            ).copy(
                preeditSelStart = 0,
                preeditSelEnd = input.toByteArray(Charsets.UTF_8).size,
                preeditCaretByte = input.toByteArray(Charsets.UTF_8).size,
                preeditSelection =
                    app.songyan.ime.keyboard.engine
                        .TextSelection(input.length, 0),
            )

        companion object {
            val SLOT_KEYS = listOf("a", "s", "d", "f", "g")
            val SLOT_LABELS = listOf("甲", "乙", "丙", "丁", "戊")
        }
    }

    @Test
    fun aReadOnlyPageDoesNotAdvanceTheRevisionOrTheSnapshot() =
        runTest {
            val engine = ReadBrowseEngine()
            val session = InputSession(engine, KeyboardLayoutKind.MERGED14)
            session.tap(KeyAction.Code("b"))
            session.tap(KeyAction.Code("u"))
            assertTrue("the composition is established", session.snapshot.isComposing)
            val revision = session.compositionRevision
            val snapshot = session.snapshot

            val page = session.readCandidatePage(1)
            assertEquals(1, engine.readCount)
            assertEquals(
                "a read-only fetch takes a slice without changing the page/highlight",
                listOf("p5", "p6", "p7", "p8", "p9"),
                page.map { it.text },
            )
            assertEquals("read-only browsing is not an input change", revision, session.compositionRevision)
            assertEquals("read-only browsing must not change the snapshot", snapshot, session.snapshot)
        }

    @Test
    fun withoutTheCapabilityThePageIsEmptyAndStateIsUntouched() =
        runTest {
            val engine = ReadBrowseEngine()
            engine.readOnlyEnabled = false
            val session = InputSession(engine, KeyboardLayoutKind.MERGED14)
            session.tap(KeyAction.Code("b"))
            val revision = session.compositionRevision
            assertEquals(emptyList<Candidate>(), session.readCandidatePage(0))
            assertEquals(0, engine.readCount)
            assertEquals(revision, session.compositionRevision)
        }

    @Test
    fun anInvalidPageIndexIsAnEmptyResultNotAQuery() =
        runTest {
            val engine = ReadBrowseEngine()
            val session = InputSession(engine, KeyboardLayoutKind.MERGED14)
            session.tap(KeyAction.Code("b"))
            assertEquals(emptyList<Candidate>(), session.readCandidatePage(-1))
            assertEquals(0, engine.readCount)
        }

    @Test
    fun eachPageKeepsTheSchemaSlotKeysAndLabels() =
        runTest {
            val engine = ReadBrowseEngine()
            val session = InputSession(engine, KeyboardLayoutKind.MERGED14)
            session.tap(KeyAction.Code("b"))
            val page0 = session.readCandidatePage(0)
            val page1 = session.readCandidatePage(1)
            assertEquals(ReadBrowseEngine.SLOT_KEYS, page0.map { it.selectKey })
            assertEquals(ReadBrowseEngine.SLOT_LABELS, page0.map { it.selectLabel })
            assertEquals(
                "page-local slot key is stable across pages",
                ReadBrowseEngine.SLOT_KEYS,
                page1.map { it.selectKey },
            )
            assertEquals(ReadBrowseEngine.SLOT_LABELS, page1.map { it.selectLabel })
        }

    @Test
    fun aReadFailurePropagatesAndDoesNotChangeState() =
        runTest {
            val engine = ReadBrowseEngine()
            engine.readFailure = EngineBackendError.OperationFailed("engine down")
            val session = InputSession(engine, KeyboardLayoutKind.MERGED14)
            session.tap(KeyAction.Code("b"))
            val revision = session.compositionRevision
            var thrown = false
            try {
                session.readCandidatePage(1)
            } catch (error: EngineBackendError) {
                thrown = true
            }
            assertTrue("a failed read must not collapse into EOF", thrown)
            assertEquals(revision, session.compositionRevision)
        }
}
