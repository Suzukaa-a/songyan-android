package app.songyan.ime.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRunTest {
    private val textTool = ToolDescriptor("polish", "润色", setOf(ToolCapability.TEXT))
    private val textCapabilities = CapabilityTable(setOf(ToolCapability.TEXT))
    private val testLimits =
        ToolLimits(
            maxInputUnits = 100,
            timeoutMillis = 100,
            undoWindowMillis = null,
            maxResults = 2,
        )
    private var now = 0L

    private fun begin(
        tool: ToolDescriptor = textTool,
        capabilities: CapabilityTable = textCapabilities,
        editor: EditorSnapshot = EditorSnapshot("doc", EditorPrivacy.NORMAL, beforeCursor = "hello", cursorIndex = 5),
        limits: ToolLimits = testLimits,
        session: ToolSession = ToolSession("session"),
    ): ToolRun = ToolRun.begin(tool, capabilities, editor, limits, session, clock = { now })

    @Test
    fun aMissingHostCapabilityIsReportedWithTheMissingSet() {
        val run =
            begin(
                tool = ToolDescriptor("speech", "转写", setOf(ToolCapability.AUDIO)),
                editor = EditorSnapshot("doc", EditorPrivacy.NORMAL),
            )
        assertEquals(ToolPhase.FAILED, run.phase)
        assertEquals(
            ToolFailure.Unavailable(UnavailableReason.MISSING_CAPABILITIES, setOf(ToolCapability.AUDIO)),
            run.failure,
        )
        assertNull(run.source)
    }

    @Test
    fun capabilitiesAreComparedAgainstTheHostNotTheTool() {
        val run = begin(capabilities = CapabilityTable(setOf()))
        assertEquals(ToolPhase.FAILED, run.phase)
        assertEquals(
            ToolFailure.Unavailable(UnavailableReason.MISSING_CAPABILITIES, setOf(ToolCapability.TEXT)),
            run.failure,
        )
    }

    @Test
    fun aToolWithoutRequirementsRunsOnAnEmptyCapabilityTable() {
        val run = begin(tool = ToolDescriptor("plain", "Plain", emptySet()), capabilities = CapabilityTable(setOf()))
        assertEquals(ToolPhase.RUNNING, run.phase)
        assertEquals("hello", run.source?.text)
    }

    @Test
    fun aPasswordFieldFailsBeforeAnyTextIsRead() {
        val run =
            begin(
                editor =
                    EditorSnapshot(
                        "doc",
                        EditorPrivacy.PASSWORD,
                        selection = "secret",
                        selectionRange = TextRange(0, 6),
                        cursorIndex = 6,
                    ),
            )
        assertEquals(ToolPhase.FAILED, run.phase)
        assertEquals(ToolFailure.Unavailable(UnavailableReason.SECURE_ENTRY), run.failure)
        assertNull(run.source)
    }

    @Test
    fun anEditorWithoutTextFailsAsNoText() {
        val run = begin(editor = EditorSnapshot("doc", EditorPrivacy.NORMAL))
        assertEquals(ToolFailure.Unavailable(UnavailableReason.NO_TEXT), run.failure)
    }

    @Test
    fun theTimeoutUsesTheInjectedClockAndAsksTheHostToCancel() {
        val run = begin()
        now = 99
        assertNull(run.tick())
        assertEquals(ToolPhase.RUNNING, run.phase)

        now = 100
        assertEquals(CancelRun(run.runId), run.tick())
        assertEquals(ToolPhase.FAILED, run.phase)
        assertEquals(ToolFailure.TimedOut, run.failure)
        assertNull(run.tick())
    }

    @Test
    fun aToolsOwnTimeoutReplacesTheHostLimit() {
        val run = begin(tool = textTool.copy(timeoutMillis = 300_000))
        assertEquals(300_000, run.timeoutMillis)
        now = 299_999
        assertNull(run.tick())
        assertTrue(run.complete(run.runId, listOf("slow but in time")))
        assertEquals(ToolPhase.PREVIEWING, run.phase)
    }

    @Test
    fun aLateResultIsRejectedByTheDeadlineEvenWithoutATick() {
        val run = begin()
        now = 100
        assertFalse(run.complete(run.runId, listOf("late")))
        assertEquals(ToolPhase.FAILED, run.phase)
        assertEquals(ToolFailure.TimedOut, run.failure)
        assertNull(run.preview)
    }

    @Test
    fun aLateErrorIsAlsoReportedAsATimeoutWithoutATick() {
        val run = begin()
        now = 100
        assertFalse(run.fail(run.runId, "late error"))
        assertEquals(ToolFailure.TimedOut, run.failure)
    }

    @Test
    fun aResultArrivingAfterTheTimeoutCannotEnterThePreview() {
        val run = begin()
        now = 100
        assertEquals(CancelRun(run.runId), run.tick())
        assertFalse(run.complete(run.runId, listOf("late")))
        assertNull(run.preview)
        assertEquals(ToolPhase.FAILED, run.phase)
    }

    @Test
    fun cancellingAsksTheHostToCancelTheCurrentRunOnly() {
        val run = begin()
        assertEquals(CancelRun(run.runId), run.cancel())
        assertEquals(ToolPhase.FAILED, run.phase)
        assertEquals(ToolFailure.Cancelled, run.failure)
        assertNull(run.cancel())
    }

    @Test
    fun aResultFromAnOldSessionCannotEnterANewSession() {
        val previousSession = ToolSession("session-a")
        val previous = begin(session = previousSession)
        assertTrue(previous.complete(previous.runId, listOf("first")))
        assertEquals(ToolPhase.PREVIEWING, previous.phase)

        val next = begin(session = ToolSession("session-b"))
        // The old session's late callback carries an id this session never issued.
        assertFalse(next.complete(previous.runId, listOf("stale")))
        assertNull(next.preview)
        assertTrue(next.complete(next.runId, listOf("fresh")))
        assertEquals(listOf("fresh"), next.preview?.results)
        assertNotEquals(previous.runId, next.runId)
    }

    @Test
    fun twoRunsInTheSameSessionNeverShareAnIdentity() {
        val session = ToolSession("session")
        val first = begin(session = session)
        assertTrue(first.complete(first.runId, listOf("first")))

        val second = begin(session = session)
        assertFalse("first run's result", second.complete(first.runId, listOf("stale")))
        assertTrue(second.complete(second.runId, listOf("second")))
        assertNotEquals(first.runId, second.runId)
    }

    @Test
    fun aResultFromAnOldRunCannotEnterTheRetriedPreview() {
        val run = begin()
        val firstId = run.runId
        assertTrue(run.complete(firstId, listOf("first")))
        val retriedId = requireNotNull(run.retry())
        assertNotEquals(firstId, retriedId)
        assertEquals(ToolPhase.RUNNING, run.phase)

        assertFalse("old run result", run.complete(firstId, listOf("stale")))
        assertTrue(run.complete(retriedId, listOf("second")))
        assertEquals(listOf("second"), run.preview?.results)
        assertFalse("old run error", run.fail(firstId, "stale"))
        assertEquals(ToolPhase.PREVIEWING, run.phase)
    }

    @Test
    fun anEmptyResultListFailsAsNoResult() {
        val run = begin()
        assertFalse(run.complete(run.runId, emptyList()))
        assertEquals(ToolFailure.NoResult, run.failure)
        assertEquals(ToolPhase.FAILED, run.phase)
    }

    @Test
    fun anEmptyStringIsALegalResult() {
        val run = begin()
        assertTrue(run.complete(run.runId, listOf("")))
        assertEquals(listOf(""), run.preview?.results)
        assertEquals("", run.copy())
    }

    @Test
    fun aToolErrorKeepsTheToolsOwnMessageAndDropsStaleOnes() {
        val run = begin()
        assertTrue(run.complete(run.runId, listOf("ok")))
        assertFalse(run.fail(run.runId, "too late"))
        assertEquals(ToolPhase.PREVIEWING, run.phase)

        val retried = requireNotNull(run.retry())
        assertTrue(run.fail(retried, "network down"))
        assertEquals(ToolFailure.ToolError("network down"), run.failure)
    }

    @Test
    fun resultsAreTrimmedToTheInjectedLimit() {
        val run = begin()
        assertTrue(run.complete(run.runId, listOf("a", "b", "c")))
        assertEquals(listOf("a", "b"), run.preview?.results)
        assertEquals("a", run.copy())
    }

    @Test
    fun previewSelectionStaysInBounds() {
        val run = begin()
        assertTrue(run.complete(run.runId, listOf("a", "b")))
        assertTrue(run.selectResult(1))
        assertEquals("b", run.selectedResult())
        assertFalse(run.selectResult(2))
        assertEquals("b", run.selectedResult())
    }

    @Test
    fun copyIsOnlyPossibleInPreview() {
        val run = begin()
        assertNull(run.copy())
        assertTrue(run.complete(run.runId, listOf("a")))
        assertEquals("a", run.copy())
    }

    @Test
    fun closingARunningRunCancelsTheRealTask() {
        val run = begin()
        assertEquals(DismissOutcome.Cancelled(CancelRun(run.runId)), run.dismiss())
        assertEquals(ToolPhase.IDLE, run.phase)
        assertNull(run.cancel())
        assertNull(run.preview)
    }

    @Test
    fun closingAPreviewJustDismissesIt() {
        val run = begin()
        assertTrue(run.complete(run.runId, listOf("a")))
        assertEquals(DismissOutcome.Dismissed, run.dismiss())
        assertEquals(ToolPhase.IDLE, run.phase)
        assertNull(run.preview)
        assertEquals(DismissOutcome.Unavailable, run.dismiss())
    }

    @Test
    fun closingAFailedRunDismissesIt() {
        val run = begin()
        assertTrue(run.fail(run.runId, "boom"))
        assertEquals(DismissOutcome.Dismissed, run.dismiss())
        assertEquals(ToolPhase.IDLE, run.phase)
    }

    @Test
    fun closingWhileTheHostOwesAWriteIsRefused() {
        val run = begin()
        assertTrue(run.complete(run.runId, listOf("A")))
        assertTrue(run.insert("doc", cursorIndex = 5) is WriteStart.Requested)
        assertEquals(ToolPhase.WRITING, run.phase)
        assertEquals(DismissOutcome.Unavailable, run.dismiss())
        assertEquals(ToolPhase.WRITING, run.phase)
    }

    @Test
    fun aFailedRunCanBeRetriedWithAFreshIdentity() {
        val run = begin()
        val firstId = run.runId
        assertTrue(run.fail(firstId, "boom"))
        assertEquals(
            ToolPhase.RUNNING,
            run.phase.let {
                run.retry()
                run.phase
            },
        )
        assertNotEquals(firstId, run.runId)
        assertNull(run.failure)
    }
}
