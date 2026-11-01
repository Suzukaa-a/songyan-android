package app.songyan.ime.keyboard

import app.songyan.ime.keyboard.engine.EngineSnapshot
import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import app.songyan.ime.keyboard.layout.KeyAction
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.keyboard.session.InputMode
import app.songyan.ime.keyboard.session.InputSession
import app.songyan.ime.keyboard.support.ShiftState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from KeyboardCoreTests.KeyboardCoreTests / InputSessionModeTests and PredictionSessionTests. */
class InputSessionTest {
    private class Harness {
        val engine = StubEngine()
        val session = InputSession(engine, KeyboardLayoutKind.QWERTY26)
        val commits = mutableListOf<String>()
        var errors = 0
        var updates = 0

        init {
            session.onCommitted = { commits += it }
            session.onError = { errors += 1 }
            session.onUpdate = { updates += 1 }
        }

        suspend fun type(text: String) = text.forEach { session.tap(KeyAction.Code(it.toString())) }
    }

    @Test
    fun compositionAndCandidates() =
        runTest {
            val h = Harness()
            h.type("nihao")
            assertTrue(h.session.snapshot.isComposing)
            assertEquals("nihao", h.session.snapshot.rawInput)
            assertTrue(
                h.session.snapshot.candidates
                    .any { it.text == "你好" },
            )

            h.session.selectCandidate(0)
            assertEquals(listOf("你好"), h.commits)
            assertFalse(h.session.snapshot.isComposing)
        }

    @Test
    fun spaceCommitsFirstCandidate() =
        runTest {
            val h = Harness()
            h.type("zhongguo")
            h.session.tap(KeyAction.Space)
            assertEquals(listOf("中国"), h.commits)
            assertFalse(h.session.snapshot.isComposing)
        }

    @Test
    fun spaceWithoutCompositionInsertsSpace() =
        runTest {
            // librime does not consume an idle space; the backend contract (RimeEngineBackend,
            // covered against the real engine in EngineReplayTest) commits it as a literal.
            val h = Harness()
            h.session.tap(KeyAction.Space)
            assertEquals(listOf(" "), h.commits)
        }

    @Test
    fun cancellationIsNeverReportedAsAnEngineError() =
        runTest {
            val engine =
                object : StubEngine() {
                    override suspend fun processKey(keyValue: String): EngineSnapshot =
                        throw CancellationException("keyboard scope cancelled")
                }
            val session = InputSession(engine, KeyboardLayoutKind.QWERTY26)
            var errors = 0
            session.onError = { errors += 1 }
            val thrown = runCatching { session.tap(KeyAction.Code("n")) }.exceptionOrNull()
            assertTrue("cancellation must reach the caller, got $thrown", thrown is CancellationException)
            assertEquals(0, errors)
        }

    @Test
    fun enterWithoutCompositionRequestsNewline() =
        runTest {
            val h = Harness()
            h.session.tap(KeyAction.Enter)
            assertEquals(listOf("\n"), h.commits)
        }

    @Test
    fun backspaceRemovesCodeChar() =
        runTest {
            val h = Harness()
            h.type("nihao")
            h.session.tap(KeyAction.Backspace)
            assertEquals("niha", h.session.snapshot.rawInput)
        }

    @Test
    fun layoutSwitchResetsComposition() =
        runTest {
            val h = Harness()
            h.session.onDispatchCommit = { true }
            h.type("nih")
            assertTrue(h.session.snapshot.isComposing)
            h.session.selectLayout(KeyboardLayoutKind.MERGED14)
            assertEquals(KeyboardLayoutKind.MERGED14, h.session.layout)
            assertFalse(h.session.snapshot.isComposing)
        }

    @Test
    fun insertLiteralWhenIdle() =
        runTest {
            val h = Harness()
            assertTrue(h.session.insertLiteral("，"))
            assertEquals(listOf("，"), h.commits)
            assertFalse(h.session.snapshot.isComposing)
        }

    @Test
    fun insertLiteralCommitsCompositionFirst() =
        runTest {
            val h = Harness()
            h.type("nihao")
            assertTrue(h.session.snapshot.isComposing)
            h.session.insertLiteral("，")
            // The composition confirms 你好 first, then the symbol is inserted: nothing typed is lost.
            assertEquals(listOf("你好", "，"), h.commits)
            assertFalse(h.session.snapshot.isComposing)
        }

    @Test
    fun literalKeyActionRoutesToInsertLiteral() =
        runTest {
            val h = Harness()
            h.type("nihao")
            h.session.tap(KeyAction.Literal("1"))
            assertEquals(listOf("你好", "1"), h.commits)
        }

    @Test
    fun englishModeCommitsThenLettersInsertLiterally() =
        runTest {
            val h = Harness()
            h.type("nihao")
            h.session.setMode(InputMode.ENGLISH)
            assertEquals(InputMode.ENGLISH, h.session.mode)
            assertEquals(listOf("你好"), h.commits)
            assertFalse(h.session.snapshot.isComposing)
            assertTrue(
                h.session.snapshot.candidates
                    .isEmpty(),
            )

            h.session.tap(KeyAction.Code("a"))
            h.session.tap(KeyAction.Space)
            h.session.tap(KeyAction.Enter)
            assertEquals(listOf("你好", "a", " ", "\n"), h.commits)
        }

    @Test
    fun englishBackToChineseComposesAgain() =
        runTest {
            val h = Harness()
            h.session.setMode(InputMode.ENGLISH)
            h.session.tap(KeyAction.Code("x"))
            h.session.setMode(InputMode.CHINESE)
            assertEquals(InputMode.CHINESE, h.session.mode)
            h.type("nihao")
            assertTrue(h.session.snapshot.isComposing)
            assertTrue(
                h.session.snapshot.candidates
                    .any { it.text == "你好" },
            )
            assertEquals(listOf("x"), h.commits)
        }

    @Test
    fun backspaceInEnglishModeGoesToHost() =
        runTest {
            val h = Harness()
            h.session.setMode(InputMode.ENGLISH)
            h.session.tap(KeyAction.Backspace)
            assertTrue("an idle English backspace must not reach the engine", h.commits.isEmpty())
        }

    // MARK: Shift tri-state (direct English input)

    @Test
    fun shiftOnceUppercasesTheNextLetterOnly() =
        runTest {
            val h = Harness()
            h.session.setMode(InputMode.ENGLISH)
            h.session.tapShift()
            assertTrue(h.session.shiftState.isActive)
            assertTrue(h.session.shiftState.isTemporary)

            h.session.tap(KeyAction.Code("a"))
            assertEquals(listOf("A"), h.commits)
            assertFalse("once is consumed by a letter", h.session.shiftState.isActive)

            h.session.tap(KeyAction.Code("b"))
            assertEquals(listOf("A", "b"), h.commits)
        }

    @Test
    fun shiftLockedKeepsUppercasingAndDigitsDoNotConsumeOnce() =
        runTest {
            val h = Harness()
            h.session.setMode(InputMode.ENGLISH)
            h.session.tapShift()
            h.session.tap(KeyAction.Literal("1"))
            assertTrue("a literal must not consume the one-shot shift", h.session.shiftState.isActive)

            h.session.doubleTapShift()
            assertEquals(app.songyan.ime.keyboard.support.ShiftState.LOCKED, h.session.shiftState)
            h.session.tap(KeyAction.Code("a"))
            h.session.tap(KeyAction.Code("b"))
            assertEquals(listOf("1", "A", "B"), h.commits)
        }

    @Test
    fun modeSwitchResetsShift() =
        runTest {
            val h = Harness()
            h.session.setMode(InputMode.ENGLISH)
            h.session.tapShift()
            h.session.setMode(InputMode.CHINESE)
            assertFalse(h.session.shiftState.isActive)
        }

    @Test
    fun chineseShiftHandsTheCapitalToTheEngineWhenNothingIsComposed() =
        runTest {
            val keys = mutableListOf<String>()
            val engine =
                object : StubEngine() {
                    override suspend fun processKey(keyValue: String): EngineSnapshot {
                        keys += keyValue
                        return super.processKey(keyValue)
                    }
                }
            val session = InputSession(engine, KeyboardLayoutKind.QWERTY26)
            session.tapShift()
            assertTrue("Chinese mode arms Shift too", session.shiftState.isTemporary)
            // The schema decides what a capital means (rime-ice reads `V` as the start of its
            // symbol input); the keyboard never types it past the engine.
            session.tap(KeyAction.Code("v"))
            assertEquals(listOf("V"), keys)
            assertFalse("once is consumed by the letter", session.shiftState.isActive)
        }

    @Test
    fun chineseShiftWhileComposingHandsTheCapitalToTheEngine() =
        runTest {
            val keys = mutableListOf<String>()
            val engine =
                object : StubEngine() {
                    override suspend fun processKey(keyValue: String): EngineSnapshot {
                        keys += keyValue
                        return super.processKey(keyValue)
                    }
                }
            val session = InputSession(engine, KeyboardLayoutKind.QWERTY26)
            session.tap(KeyAction.Code("n"))
            session.tapShift()
            session.tap(KeyAction.Code("b"))
            assertEquals(listOf("n", "B"), keys)
            assertFalse(session.shiftState.isActive)
        }

    @Test
    fun chineseShiftTapAgainReleases() =
        runTest {
            val h = Harness()
            h.session.tapShift()
            h.session.tapShift()
            assertFalse(h.session.shiftState.isActive)
        }

    @Test
    fun chineseCapsLockSendsEveryLetterAsACapitalUntilReleased() =
        runTest {
            val keys = mutableListOf<String>()
            val engine =
                object : StubEngine() {
                    override suspend fun processKey(keyValue: String): EngineSnapshot {
                        keys += keyValue
                        return super.processKey(keyValue)
                    }
                }
            val session = InputSession(engine, KeyboardLayoutKind.QWERTY26)
            session.doubleTapShift()
            assertEquals(ShiftState.LOCKED, session.shiftState)
            for (letter in "abc") session.tap(KeyAction.Code(letter.toString()))
            assertEquals(listOf("A", "B", "C"), keys)
            assertEquals("a capital does not release the lock", ShiftState.LOCKED, session.shiftState)
            session.tapShift()
            session.tap(KeyAction.Code("d"))
            assertEquals(listOf("A", "B", "C", "d"), keys)
        }

    // MARK: Composition revision

    @Test
    fun everyAppliedStateBumpsCompositionRevision() =
        runTest {
            val h = Harness()
            val before = h.session.compositionRevision
            h.type("ni")
            assertTrue(h.session.compositionRevision > before)
            val afterTyping = h.session.compositionRevision
            h.session.refresh()
            assertTrue("a refresh publishes a new engine state", h.session.compositionRevision > afterTyping)
        }

    @Test
    fun refreshNeverReplaysACachedCommit() =
        runTest {
            val h = Harness()
            h.type("nihao")
            h.session.tap(KeyAction.Space)
            assertEquals(listOf("你好"), h.commits)
            // The backend's cached snapshot may still carry the commit of the last operation.
            h.session.refresh()
            assertEquals("a read must not replay a commit", listOf("你好"), h.commits)
        }

    // MARK: Confirm / discard

    @Test
    fun discardCompositionClearsTheEngineComposition() =
        runTest {
            val h = Harness()
            h.type("ni")
            assertTrue(h.session.snapshot.isComposing)
            assertTrue(h.session.discardComposition())
            assertFalse(h.session.snapshot.isComposing)
            assertTrue("a cancel never commits", h.commits.isEmpty())
        }

    @Test
    fun discardClearsAStaleEngineCompositionInAFreshSession() =
        runTest {
            val engine = StubEngine()
            val first = InputSession(engine, KeyboardLayoutKind.QWERTY26)
            first.onCommitted = { }
            "ni".forEach { first.tap(KeyAction.Code(it.toString())) }
            assertTrue(engine.currentSnapshot().isComposing)

            // A new editor starts a new session: it has not observed the composition the engine owns.
            val second = InputSession(engine, KeyboardLayoutKind.QWERTY26)
            assertFalse(second.snapshot.isComposing)
            assertTrue(second.discardComposition())
            assertFalse("the stale engine composition must be cleared", engine.currentSnapshot().isComposing)
        }

    // MARK: Prediction

    @Test
    fun predictionIsSelectableAndChains() =
        runTest {
            val engine = StubEngine()
            engine.predictionsAfterCommit = mapOf("你好" to listOf("好", "们"))
            val h = Harness2(engine)
            h.type("nihao")
            h.session.tap(KeyAction.Space)
            assertEquals(listOf("你好"), h.commits)
            assertTrue(h.session.snapshot.isPrediction)
            assertFalse("a prediction is never marked text", h.session.snapshot.isComposing)
            assertEquals("", h.session.snapshot.preedit)

            assertEquals(
                InputSession.CandidateConfirmationResult.COMPLETED,
                h.session.confirmCandidateAbsolute(0),
            )
            assertEquals(listOf("你好", "好"), h.commits)
        }

    @Test
    fun confirmOnlyDismissesAndNeverPicksAPrediction() =
        runTest {
            val engine = StubEngine()
            engine.predictionsAfterCommit = mapOf("你好" to listOf("好"))
            val h = Harness2(engine)
            h.type("nihao")
            h.session.tap(KeyAction.Space)
            assertTrue(h.session.commitComposition())
            assertEquals("confirming must not commit a prediction", listOf("你好"), h.commits)
            assertFalse(h.session.snapshot.isPrediction)
            assertEquals(1, engine.escapes)
        }

    @Test
    fun predictionSurvivesTheSwitchToEnglish() =
        runTest {
            val engine = StubEngine()
            engine.predictionsAfterCommit = mapOf("你好" to listOf("好", "们"))
            val h = Harness2(engine)
            h.type("nihao")
            h.session.tap(KeyAction.Space)
            h.session.setMode(InputMode.ENGLISH)
            assertTrue("predictions survive the switch", h.session.snapshot.isPrediction)
            assertEquals(0, engine.escapes)
            val slots =
                app.songyan.ime.keyboard.support.ABCSuggestionSlots
                    .enginePredictions(h.session.snapshot, 3)
            assertEquals(listOf("好", "们"), slots.map { it.second })
        }

    // MARK: Privacy

    @Test
    fun disablingLearningDismissesPredictionAndReportsUnsupported() =
        runTest {
            val engine = StubEngine()
            engine.predictionsAfterCommit = mapOf("你好" to listOf("好"))
            val h = Harness2(engine)
            h.type("nihao")
            h.session.tap(KeyAction.Space)
            assertTrue(h.session.snapshot.isPrediction)

            // The stub engine has no real learning gate, so the session must not claim protection.
            assertEquals(
                InputSession.LearningProtection.UNSUPPORTED,
                h.session.setLearningEnabled(false),
            )
            assertFalse(h.session.learningEnabled)
            assertFalse("no learning-derived candidate may remain visible", h.session.snapshot.isPrediction)
            assertTrue(
                h.session.snapshot.candidates
                    .isEmpty(),
            )
        }

    @Test
    fun disablingLearningStillAllowsCommits() =
        runTest {
            val h = Harness()
            h.session.setLearningEnabled(false)
            h.type("nihao")
            h.session.tap(KeyAction.Space)
            assertEquals(listOf("你好"), h.commits)
        }

    @Test
    fun unconfiguredEngineStillStartsAndReports() =
        runTest {
            val h = Harness()
            assertEquals(BuiltinTemplateSchemas.QUANPIN, h.session.selectedSchemaID)
            assertNull(h.session.inputUnavailableReason)
            h.session.setMode(InputMode.CHINESE)
            assertTrue(
                h.session.snapshot.candidates
                    .isEmpty(),
            )
        }

    private inner class Harness2(
        val stub: StubEngine,
    ) {
        val session = InputSession(stub, KeyboardLayoutKind.QWERTY26)
        val commits = mutableListOf<String>()

        init {
            session.onCommitted = { commits += it }
        }

        suspend fun type(text: String) = text.forEach { session.tap(KeyAction.Code(it.toString())) }
    }
}
