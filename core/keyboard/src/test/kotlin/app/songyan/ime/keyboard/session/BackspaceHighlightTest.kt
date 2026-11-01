package app.songyan.ime.keyboard.session

import app.songyan.ime.keyboard.StubEngine
import app.songyan.ime.keyboard.engine.Candidate
import app.songyan.ime.keyboard.engine.EngineBackend
import app.songyan.ime.keyboard.engine.EngineSnapshot
import app.songyan.ime.keyboard.layout.KeyAction
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Taking back a partial choice must not leave the earlier choice highlighted. */
class BackspaceHighlightTest {
    /** An engine that, like librime, reopens the previous segment with its old choice highlighted. */
    private class ReopeningEngine : StubEngine() {
        val keys = mutableListOf<String>()
        private var highlight = 0

        override suspend fun processKey(keyValue: String): EngineSnapshot {
            keys += keyValue
            when (keyValue) {
                EngineBackend.BACKSPACE_KEY -> highlight = 2
                EngineBackend.HOME_KEY -> highlight = 0
                else -> highlight = 0
            }
            current =
                EngineSnapshot(
                    candidates = listOf(Candidate(0, "你好"), Candidate(1, "你好吗"), Candidate(2, "你")),
                    preedit = "ni hao",
                    rawInput = "nihao",
                    isComposing = true,
                    highlightedIndex = highlight,
                )
            return current
        }
    }

    @Test
    fun theFirstCandidateIsHighlightedAgainAfterBackspace() =
        runTest {
            val engine = ReopeningEngine()
            val session = InputSession(engine, KeyboardLayoutKind.QWERTY26)
            session.tap(KeyAction.Code("n"))
            session.tap(KeyAction.Backspace)
            assertEquals(0, session.snapshot.highlightedIndex)
            assertEquals(listOf("n", EngineBackend.BACKSPACE_KEY, EngineBackend.HOME_KEY), engine.keys)
        }
}
