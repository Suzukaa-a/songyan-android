package app.songyan.ime.keyboard.session

import app.songyan.ime.keyboard.display.MarkedComposition
import app.songyan.ime.keyboard.engine.Candidate
import app.songyan.ime.keyboard.engine.EngineBackend
import app.songyan.ime.keyboard.engine.EngineBackendError
import app.songyan.ime.keyboard.engine.EngineBackendKind
import app.songyan.ime.keyboard.engine.EngineSnapshot
import app.songyan.ime.keyboard.engine.KeyboardSchemaDescriptor
import app.songyan.ime.keyboard.engine.TextSelection
import app.songyan.ime.keyboard.layout.KeyAction
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from KeyboardCoreTests.SpellingDemandSwitchTests. The engine models the production contract:
 * the highlighted spelling is decoded when a snapshot is produced (a key or a selection), then cached;
 * `currentSnapshot` maps that cache and never re-evaluates the demand.
 */
class SpellingDemandSwitchTest {
    private class CacheContractEngine : EngineBackend {
        override val kind: EngineBackendKind = EngineBackendKind.DEMO_SIMULATOR
        override val inputUnavailableReason: String? = null
        override val availableSchemas = emptyList<KeyboardSchemaDescriptor>()
        override var spellingDemand: (() -> Boolean)? = null
        override val statusSummary: String get() = "cache-contract"

        var rejectSchemaIDs = emptySet<String>()
        var schemaSelectCount = 0
            private set

        /** Candidate decodes: demand open while composing at production time. */
        var spellingRequestCount = 0
            private set
        private var input = ""
        private var composing = false
        private var cached: EngineSnapshot? = null

        override suspend fun setup(
            sharedDirectory: String,
            userDirectory: String,
        ) = Unit

        override suspend fun validateSchema(schemaID: String) {
            if (schemaID in rejectSchemaIDs) throw EngineBackendError.UnsupportedSchema("stub rejects $schemaID")
        }

        override suspend fun selectSchema(schemaID: String) {
            schemaSelectCount += 1
            input = ""
            composing = false
            cached = produce()
        }

        override suspend fun processKey(keyValue: String): EngineSnapshot {
            when (keyValue) {
                EngineBackend.BACKSPACE_KEY -> {
                    input = input.dropLast(1)
                    composing = input.isNotEmpty()
                }
                EngineBackend.RETURN_KEY -> {
                    input = ""
                    composing = false
                }
                else -> {
                    input += keyValue
                    composing = true
                }
            }
            return produce().also { cached = it }
        }

        override suspend fun selectCandidate(index: Int): EngineSnapshot {
            var state = produce()
            if (composing) state = state.copy(committed = "你")
            input = ""
            composing = false
            cached = state
            return state
        }

        override suspend fun nextPage(): EngineSnapshot = currentSnapshot()

        override suspend fun previousPage(): EngineSnapshot = currentSnapshot()

        override fun currentSnapshot(): EngineSnapshot = (cached ?: produce()).copy(committed = "")

        override suspend fun shutdown() = Unit

        fun replay(keys: String) {
            for (key in keys) {
                input += key
                composing = true
                cached = produce()
            }
        }

        private fun produce(): EngineSnapshot {
            val request = composing && (spellingDemand?.invoke() ?: false)
            if (request) spellingRequestCount += 1
            val preedit = if (composing) input else ""
            return EngineSnapshot(
                schemaID = "stub",
                candidates = if (composing) listOf(Candidate(0, "你")) else emptyList(),
                preedit = preedit,
                rawInput = preedit,
                isComposing = composing,
                highlightedIndex = if (composing) 0 else -1,
                pageSize = 5,
                isLastPage = true,
                highlightedSpelling = if (request) "ni" else "",
                preeditSelStart = 0,
                preeditSelEnd = preedit.toByteArray().size,
                preeditCaretByte = preedit.toByteArray().size,
                preeditSelection = TextSelection(preedit.length, 0),
            )
        }
    }

    private fun marked(session: InputSession) =
        MarkedComposition(session.snapshot, spellingPreedit = session.requiresSpellingDisplay).text

    private suspend fun compose(
        session: InputSession,
        keys: String,
    ) {
        for (key in keys) session.tap(KeyAction.Code(key.toString()))
        assertTrue("composition established: $keys", session.snapshot.isComposing)
    }

    @Test
    fun aFailedSwitchToAReducedLayoutKeepsTheFullLayoutAndRequestsNoSpelling() =
        runTest {
            val engine = CacheContractEngine()
            val session = InputSession(engine, KeyboardLayoutKind.QWERTY26)
            compose(session, "ni")
            assertEquals("a 26-key composition decodes no candidate", 0, engine.spellingRequestCount)
            engine.rejectSchemaIDs =
                setOf(
                    app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
                        .defaultSchemaID(KeyboardLayoutKind.MERGED14)!!,
                )
            val selectsBefore = engine.schemaSelectCount
            var errors = 0
            session.onError = { errors += 1 }
            val commits = mutableListOf<String>()
            session.onCommitted = { commits += it }

            session.selectLayout(KeyboardLayoutKind.MERGED14)

            assertEquals(KeyboardLayoutKind.QWERTY26, session.layout)
            assertFalse(session.requiresSpellingDisplay)
            assertEquals("no schema select on a validation failure", selectsBefore, engine.schemaSelectCount)
            assertEquals("26 stays at zero decodes", 0, engine.spellingRequestCount)
            assertEquals("", session.snapshot.highlightedSpelling)
            assertTrue(commits.isEmpty())
            assertEquals(1, errors)
        }

    @Test
    fun aFailedSwitchToAFullLayoutKeepsTheReducedSpelling() =
        runTest {
            val engine = CacheContractEngine()
            val session = InputSession(engine, KeyboardLayoutKind.MERGED14)
            session.compactSpellingLayout = true
            compose(session, "bu")
            assertEquals("ni", session.snapshot.highlightedSpelling)
            engine.rejectSchemaIDs = setOf("songyan_quanpin")
            val requestsBefore = engine.spellingRequestCount
            val selectsBefore = engine.schemaSelectCount
            val commits = mutableListOf<String>()
            session.onCommitted = { commits += it }

            session.selectLayout(KeyboardLayoutKind.QWERTY26)

            assertEquals(KeyboardLayoutKind.MERGED14, session.layout)
            assertTrue(session.requiresSpellingDisplay)
            assertEquals(selectsBefore, engine.schemaSelectCount)
            assertEquals("a failure reuses the cached spelling", requestsBefore, engine.spellingRequestCount)
            assertEquals("no fallback to the raw code", "ni", session.snapshot.highlightedSpelling)
            assertEquals("ni", marked(session))
            assertTrue(commits.isEmpty())
        }

    @Test
    fun aFailedBoundSwitchPollutesNeitherDirection() =
        runTest {
            val engine = CacheContractEngine()
            val session = InputSession(engine, KeyboardLayoutKind.MERGED14)
            session.compactSpellingLayout = true
            compose(session, "bu")
            engine.rejectSchemaIDs = setOf("missing_schema")
            val requestsBefore = engine.spellingRequestCount
            val selectsBefore = engine.schemaSelectCount

            session.selectLayout(KeyboardLayoutKind.QWERTY26, "missing_schema")

            assertEquals(KeyboardLayoutKind.MERGED14, session.layout)
            assertEquals(selectsBefore, engine.schemaSelectCount)
            assertEquals(requestsBefore, engine.spellingRequestCount)
            assertEquals("ni", session.snapshot.highlightedSpelling)
        }

    @Test
    fun aSuccessfulSwitchMovesTheDemandToTheActualLayout() =
        runTest {
            val engine = CacheContractEngine()
            val session = InputSession(engine, KeyboardLayoutKind.QWERTY26)
            assertFalse(session.requiresSpellingDisplay)

            session.selectLayout(KeyboardLayoutKind.MERGED14)
            assertEquals(KeyboardLayoutKind.MERGED14, session.layout)
            assertTrue(session.requiresSpellingDisplay)
            assertEquals(1, engine.schemaSelectCount)

            session.selectLayout(KeyboardLayoutKind.QWERTY26)
            assertEquals(KeyboardLayoutKind.QWERTY26, session.layout)
            assertFalse(session.requiresSpellingDisplay)
            assertEquals(2, engine.schemaSelectCount)
        }

    /**
     * Reproduces the boot order: install the demand bound to the resume target, replay raw input,
     * then create the session; the read-only first frame must already carry the spelling.
     */
    private suspend fun resume(
        layout: KeyboardLayoutKind,
        keys: String,
        preReplayDemand: Boolean,
    ): Pair<InputSession, CacheContractEngine> {
        val engine = CacheContractEngine()
        if (preReplayDemand) {
            val reduced = layout.requiresSpellingDisplay
            engine.spellingDemand = { reduced }
        }
        engine.replay(keys)
        val session = InputSession(engine, layout)
        session.compactSpellingLayout = layout.requiresSpellingDisplay && preReplayDemand
        session.refresh()
        return session to engine
    }

    @Test
    fun aFourteenKeyResumeKeepsTheSpellingInTheFirstFrame() =
        runTest {
            val (session, _) = resume(KeyboardLayoutKind.MERGED14, "bu", preReplayDemand = true)
            assertEquals("ni", session.snapshot.highlightedSpelling)
            assertEquals("ni", marked(session))
        }

    @Test
    fun aNineKeyResumeKeepsTheSpellingInTheFirstFrame() =
        runTest {
            val (session, _) = resume(KeyboardLayoutKind.PHONE9, "64", preReplayDemand = true)
            assertEquals("ni", session.snapshot.highlightedSpelling)
            assertEquals("ni", marked(session))
        }

    @Test
    fun aTwentySixKeyResumePerformsNoDecode() =
        runTest {
            val (session, engine) = resume(KeyboardLayoutKind.QWERTY26, "nihao", preReplayDemand = true)
            assertEquals(0, engine.spellingRequestCount)
            assertEquals("", session.snapshot.highlightedSpelling)
            assertEquals("a 26-key resume keeps the original preedit", session.snapshot.preedit, marked(session))
        }

    @Test
    fun aResumeWithoutADemandBeforeReplayLosesTheFirstFrameSpelling() =
        runTest {
            val (session, engine) = resume(KeyboardLayoutKind.MERGED14, "bu", preReplayDemand = false)
            assertEquals(0, engine.spellingRequestCount)
            assertEquals("", session.snapshot.highlightedSpelling)
            assertEquals("falls back to the raw code", "bu", marked(session))
        }

    @Test
    fun aFailedSwitchAfterAResumeKeepsTheSpelling() =
        runTest {
            val (session, engine) = resume(KeyboardLayoutKind.MERGED14, "bu", preReplayDemand = true)
            assertEquals("ni", marked(session))
            engine.rejectSchemaIDs = setOf("songyan_quanpin")
            session.selectLayout(KeyboardLayoutKind.QWERTY26)
            assertEquals(KeyboardLayoutKind.MERGED14, session.layout)
            assertEquals("ni", marked(session))
        }
}
