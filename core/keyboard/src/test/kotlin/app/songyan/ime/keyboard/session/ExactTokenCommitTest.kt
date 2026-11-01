package app.songyan.ime.keyboard.session

import app.songyan.ime.keyboard.engine.Candidate
import app.songyan.ime.keyboard.engine.EngineBackend
import app.songyan.ime.keyboard.engine.EngineBackendKind
import app.songyan.ime.keyboard.engine.EngineSnapshot
import app.songyan.ime.keyboard.engine.KeyboardSchemaDescriptor
import app.songyan.ime.keyboard.layout.CompactFold
import app.songyan.ime.keyboard.layout.KeyAction
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from KeyboardCoreTests.ExactTokenCommitTests: return without candidates commits the
 * engine's raw input, and when the running schema's alphabet takes the layout's exact tokens the
 * engine read each token as its letter, so the host gets the letter. Any other commit stays as is.
 */
class ExactTokenCommitTest {
    /**
     * Offers 你 for a leading ni and nothing else. Return commits the confirmed text and then the
     * raw input after it, like librime's express_editor, unless the test overrides it.
     */
    private class RawReturnEngine : EngineBackend {
        override val kind: EngineBackendKind = EngineBackendKind.DEMO_SIMULATOR
        override val inputUnavailableReason: String? = null
        override val availableSchemas = emptyList<KeyboardSchemaDescriptor>()
        override var spellingDemand: (() -> Boolean)? = null

        var alphabet: String? = null
        var asciiMode = false
        var returnCommit: ((String) -> String)? = null

        private var input = ""
        private var confirmed = ""
        private var consumed = 0

        override suspend fun setup(
            sharedDirectory: String,
            userDirectory: String,
        ) = Unit

        override suspend fun selectSchema(schemaID: String) = Unit

        override suspend fun spellerAlphabet(schemaID: String): String? = if (schemaID == "adapter") alphabet else null

        override suspend fun processKey(keyValue: String): EngineSnapshot {
            if (keyValue == EngineBackend.RETURN_KEY) {
                val commit = confirmed + input.drop(consumed)
                input = ""
                confirmed = ""
                consumed = 0
                val rewritten = returnCommit?.invoke(commit) ?: commit
                return EngineSnapshot(schemaID = "adapter", committed = rewritten)
            }
            if (keyValue == EngineBackend.ESC_KEY) {
                input = ""
                confirmed = ""
                consumed = 0
            } else {
                input += keyValue
            }
            return currentSnapshot()
        }

        override suspend fun selectCandidate(index: Int): EngineSnapshot {
            if (confirmed.isEmpty() && input.startsWith("ni")) {
                confirmed = "你"
                consumed = 2
            }
            return currentSnapshot()
        }

        override suspend fun nextPage(): EngineSnapshot = currentSnapshot()

        override suspend fun previousPage(): EngineSnapshot = currentSnapshot()

        override fun currentSnapshot(): EngineSnapshot {
            val preedit = confirmed + input.drop(consumed)
            val candidates =
                if (confirmed.isEmpty() && input.startsWith("ni")) {
                    listOf(Candidate(0, "你"))
                } else {
                    emptyList()
                }
            return EngineSnapshot(
                schemaID = "adapter",
                candidates = candidates,
                preedit = preedit,
                rawInput = input,
                isComposing = input.isNotEmpty(),
                isAsciiMode = asciiMode,
                preeditSelStart = confirmed.toByteArray(Charsets.UTF_8).size,
                preeditSelEnd = preedit.toByteArray(Charsets.UTF_8).size,
            )
        }

        override suspend fun shutdown() = Unit

        override val statusSummary: String get() = "raw"
    }

    private val adapterAlphabet = "!#%*+<>@^_abcdefghijklmnopqrstuvwxyz|~"

    private suspend fun confirm(
        keys: List<String>,
        layout: KeyboardLayoutKind = KeyboardLayoutKind.MERGED14,
        alphabet: String? = adapterAlphabet,
        asciiMode: Boolean = false,
        selectingFirst: Boolean = false,
        returnCommit: ((String) -> String)? = null,
    ): List<String> {
        val engine = RawReturnEngine()
        engine.alphabet = alphabet
        engine.asciiMode = asciiMode
        engine.returnCommit = returnCommit
        val session = InputSession(engine, layout)
        val commits = mutableListOf<String>()
        session.onCommitted = { commits += it }
        keys.forEach { session.tap(KeyAction.Code(it)) }
        if (selectingFirst) session.selectCandidate(0)
        assertTrue("the composition has codes but no candidates", session.snapshot.isComposing)
        assertTrue(session.snapshot.candidates.isEmpty())
        session.commitComposition()
        assertEquals(false, session.snapshot.isComposing)
        return commits
    }

    private fun token(
        letter: Char,
        fold: CompactFold = CompactFold.MERGED14,
    ): String = requireNotNull(fold.exactTokens[letter]).toString()

    @Test
    fun exactPicksCommitAsLetters() =
        runTest {
            val q = token('q')
            val d = token('d')
            assertEquals(listOf("q"), confirm(listOf(q)))
            assertEquals(listOf("qwd"), confirm(listOf(q, "w", d)))
            assertEquals(
                listOf("sa"),
                confirm(listOf(token('s', CompactFold.MERGED18), "a"), layout = KeyboardLayoutKind.MERGED18),
            )
        }

    @Test
    fun everyExactTokenSpellsItsLetter() =
        runTest {
            val q = token('q')
            assertEquals(listOf("qq"), confirm(listOf(q, q)))
        }

    @Test
    fun schemaDefinedReturnCommitIsUnchanged() =
        runTest {
            val q = token('q')
            assertEquals(listOf("done" + q), confirm(listOf("a", q)) { "done" + q })
        }

    @Test
    fun asciiModeCommitsUnchanged() =
        runTest {
            val q = token('q')
            assertEquals(listOf("a" + q), confirm(listOf("a", q), asciiMode = true))
        }

    @Test
    fun schemasWithoutExactTokensCommitUnchanged() =
        runTest {
            val q = token('q')
            assertEquals(listOf("a" + q), confirm(listOf("a", q), alphabet = "abcdefghijklmnopqrstuvwxyz"))
            assertEquals(listOf("a" + q), confirm(listOf("a", q), alphabet = null))
            assertEquals(listOf("q!"), confirm(listOf("q", "!"), layout = KeyboardLayoutKind.QWERTY26))
        }

    /** Rime commits the selected 你 and then the raw token after it. */
    @Test
    fun partialSelectionSpellsOnlyTheRawTail() =
        runTest {
            val q = token('q')
            // The selected 你 is committed as the engine made it; the raw tail is spelled back.
            assertEquals(listOf("你q"), confirm(listOf("n", "i", q), selectingFirst = true))
            assertEquals(listOf("你aq"), confirm(listOf("n", "i", "a", q), selectingFirst = true))
        }

    @Test
    fun partialSelectionWithAnotherPrefixIsUnchanged() =
        runTest {
            val q = token('q')
            // A schema-defined commit that no longer matches the confirmed+raw shape is left alone.
            assertEquals(
                listOf("您" + q),
                confirm(listOf("n", "i", q), selectingFirst = true) { "您" + it.drop(1) },
            )
            assertEquals(
                listOf("你" + q + "!"),
                confirm(listOf("n", "i", q), selectingFirst = true) { it + "!" },
            )
        }
}
