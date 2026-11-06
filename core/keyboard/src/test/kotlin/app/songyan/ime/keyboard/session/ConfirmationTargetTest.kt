package app.songyan.ime.keyboard.session

import app.songyan.ime.keyboard.engine.Candidate
import app.songyan.ime.keyboard.engine.EngineBackend
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
 * A reading filter decides what the confirming keys commit: its first match by absolute index,
 * never the engine's highlight, and nothing when the filter leaves no match.
 */
class ConfirmationTargetTest {
    /** Two candidates for one key: 小 highlighted first, 西安 second. */
    private class TwoWordEngine(
        private val schemaID: String = "",
        private val nativeSpace: EngineSnapshot? = null,
    ) : EngineBackend {
        override val kind: EngineBackendKind = EngineBackendKind.DEMO_SIMULATOR
        override val inputUnavailableReason: String? = null
        override val availableSchemas = emptyList<KeyboardSchemaDescriptor>()
        override var spellingDemand: (() -> Boolean)? = null
        var input = ""
        val selected = mutableListOf<Int>()
        val keys = mutableListOf<String>()

        override suspend fun setup(
            sharedDirectory: String,
            userDirectory: String,
        ) = Unit

        override suspend fun selectSchema(schemaID: String) = Unit

        override suspend fun processKey(keyValue: String): EngineSnapshot {
            keys += keyValue
            if (keyValue.length == 1 && keyValue[0].isLetterOrDigit()) input += keyValue
            if (keyValue == EngineBackend.SPACE_KEY) return nativeSpace ?: finish(0)
            return currentSnapshot()
        }

        private fun finish(index: Int): EngineSnapshot {
            selected += index
            val text = listOf("小", "西安")[index]
            input = ""
            return EngineSnapshot(committed = text)
        }

        override suspend fun selectCandidate(index: Int): EngineSnapshot = finish(index)

        override suspend fun selectCandidateAbsolute(index: Int): EngineSnapshot = finish(index)

        override suspend fun nextPage(): EngineSnapshot = currentSnapshot()

        override suspend fun previousPage(): EngineSnapshot = currentSnapshot()

        override fun currentSnapshot(): EngineSnapshot =
            EngineSnapshot(
                schemaID = schemaID,
                candidates = if (input.isEmpty()) emptyList() else listOf(Candidate(0, "小"), Candidate(1, "西安")),
                preedit = input,
                rawInput = input,
                isComposing = input.isNotEmpty(),
                highlightedIndex = if (input.isEmpty()) -1 else 0,
            )

        override suspend fun shutdown() = Unit

        override val statusSummary: String get() = "two"
    }

    private suspend fun composing(): Triple<InputSession, TwoWordEngine, MutableList<String>> {
        val engine = TwoWordEngine()
        val session = InputSession(engine, KeyboardLayoutKind.PHONE9)
        val commits = mutableListOf<String>()
        session.onCommitted = { commits += it }
        session.tap(KeyAction.Code("9"))
        assertTrue(session.snapshot.isComposing)
        return Triple(session, engine, commits)
    }

    @Test
    fun zhuyinSpaceKeepsTheNativeFirstToneBinding() =
        runTest {
            val engine =
                TwoWordEngine(
                    schemaID = "bopomofo_tw",
                    nativeSpace =
                        EngineSnapshot(
                            schemaID = "bopomofo_tw",
                            preedit = "ㄓㄜˉ",
                            rawInput = "5k ",
                            isComposing = true,
                        ),
                )
            val session = InputSession(engine, KeyboardLayoutKind.QWERTY26)
            session.reloadOptions()
            assertEquals(app.songyan.ime.keyboard.engine.InputSchemeFamily.ZHUYIN, session.inputLanguage.family)
            val commits = mutableListOf<String>()
            session.onCommitted = { commits += it }
            session.tap(KeyAction.Code("5"))
            session.tap(KeyAction.Code("k"))
            session.confirmationTarget = { 1 }
            session.tap(KeyAction.Space)
            assertTrue(commits.isEmpty())
            assertEquals("ㄓㄜˉ", session.snapshot.preedit)
            assertEquals("5k ", session.snapshot.rawInput)
        }

    @Test
    fun spaceCommitsTheFilteredFirstMatchNotTheEngineHighlight() =
        runTest {
            val (session, engine, commits) = composing()
            session.confirmationTarget = { 1 }
            session.tap(KeyAction.Space)
            assertEquals(listOf("西安"), commits)
            assertEquals(listOf(1), engine.selected)
            assertFalse("the engine's own space was not used", EngineBackend.SPACE_KEY in engine.keys)
        }

    @Test
    fun aSymbolKeyConfirmsTheSameTargetBeforeItsText() =
        runTest {
            val (session, _, commits) = composing()
            session.confirmationTarget = { 1 }
            session.insertLiteral("，")
            assertEquals(listOf("西安", "，"), commits)
        }

    @Test
    fun aFilterWithNoMatchConfirmsNothing() =
        runTest {
            val (session, engine, commits) = composing()
            session.confirmationTarget = { InputSession.NO_TARGET }
            session.tap(KeyAction.Space)
            assertFalse(session.insertLiteral("，"))
            assertTrue(commits.isEmpty())
            assertTrue(engine.selected.isEmpty())
            assertTrue(session.snapshot.isComposing)
        }

    @Test
    fun withoutATargetTheEngineKeepsItsOwnChoice() =
        runTest {
            val (session, engine, commits) = composing()
            session.confirmationTarget = { null }
            session.tap(KeyAction.Space)
            assertEquals(listOf("小"), commits)
            assertEquals(listOf(0), engine.selected)
        }

    @Test
    fun returnOnAFoldedLayoutConfirmsTheHighlightedCandidateNotTheKeyCodes() =
        runTest {
            val (session, engine, commits) = composing()
            session.numericTransportLayout = true
            session.tap(KeyAction.Enter)
            assertEquals(listOf("小"), commits)
            assertFalse("the raw input was not committed", EngineBackend.RETURN_KEY in engine.keys)
        }

    @Test
    fun returnOnALetterLayoutStaysWithTheEngine() =
        runTest {
            val (session, engine, _) = composing()
            session.tap(KeyAction.Enter)
            assertTrue(EngineBackend.RETURN_KEY in engine.keys)
        }
}
