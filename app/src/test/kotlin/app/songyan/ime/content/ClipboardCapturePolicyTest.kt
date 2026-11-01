package app.songyan.ime.content

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardCapturePolicyTest {
    private val policy = ClipboardCapturePolicy()

    private fun observation(
        sensitive: Boolean = false,
        textMimeType: Boolean = true,
        timestamp: Long = 1L,
        available: Boolean = true,
    ) = ClipboardObservation(
        available = available,
        textMimeType = textMimeType,
        sensitive = sensitive,
        timestamp = timestamp,
    )

    @Test
    fun historyOffSkipsBeforeAnythingElse() {
        policy.setCaptureAllowed(true)
        assertEquals(ClipboardDecision.DISABLED, policy.decide(observation(sensitive = true)))
    }

    @Test
    fun hiddenKeyboardSkips() {
        policy.setHistoryEnabled(true)
        assertEquals(ClipboardDecision.NOT_ALLOWED, policy.decide(observation()))
    }

    @Test
    fun enabledAndVisibleReads() {
        policy.setHistoryEnabled(true)
        policy.setCaptureAllowed(true)
        assertEquals(ClipboardDecision.READ, policy.decide(observation()))
    }

    @Test
    fun sensitiveClipIsNeverRead() {
        policy.setHistoryEnabled(true)
        policy.setCaptureAllowed(true)
        assertEquals(ClipboardDecision.SENSITIVE, policy.decide(observation(sensitive = true)))
    }

    @Test
    fun nonTextClipIsSkipped() {
        policy.setHistoryEnabled(true)
        policy.setCaptureAllowed(true)
        assertEquals(ClipboardDecision.NOT_TEXT, policy.decide(observation(textMimeType = false)))
    }

    @Test
    fun emptyClipboardIsSkipped() {
        policy.setHistoryEnabled(true)
        policy.setCaptureAllowed(true)
        assertEquals(ClipboardDecision.UNAVAILABLE, policy.decide(observation(available = false)))
    }

    @Test
    fun handledClipVersionIsSkipped() {
        policy.setHistoryEnabled(true)
        policy.setCaptureAllowed(true)
        val first = observation(timestamp = 7L)
        assertEquals(ClipboardDecision.READ, policy.decide(first))
        policy.markHandled(first)
        assertEquals(ClipboardDecision.ALREADY_HANDLED, policy.decide(observation(timestamp = 7L)))
        assertEquals(ClipboardDecision.READ, policy.decide(observation(timestamp = 8L)))
    }

    @Test
    fun aClipTakenBeforeTheKeyboardHidIsNotTakenAgainWhenItShows() {
        policy.setHistoryEnabled(true)
        policy.setCaptureAllowed(true)
        policy.markHandled(observation(timestamp = 7L))
        policy.setCaptureAllowed(false)
        policy.setCaptureAllowed(true)
        assertEquals(ClipboardDecision.ALREADY_HANDLED, policy.decide(observation(timestamp = 7L)))
        // A clip copied while the keyboard was hidden is new.
        assertEquals(ClipboardDecision.READ, policy.decide(observation(timestamp = 8L)))
    }

    @Test
    fun aClipAnEarlierProcessTookIsNotTakenAgain() {
        policy.setHistoryEnabled(true)
        policy.setCaptureAllowed(true)
        policy.restoreHandled(7L)
        assertEquals(ClipboardDecision.ALREADY_HANDLED, policy.decide(observation(timestamp = 7L)))
        policy.markHandled(observation(timestamp = 9L))
        policy.restoreHandled(7L)
        assertEquals(ClipboardDecision.ALREADY_HANDLED, policy.decide(observation(timestamp = 9L)))
    }

    @Test
    fun turningHistoryOffForgetsTheHandledClip() {
        policy.setHistoryEnabled(true)
        policy.setCaptureAllowed(true)
        val first = observation(timestamp = 7L)
        policy.markHandled(first)
        policy.setHistoryEnabled(false)
        policy.setHistoryEnabled(true)
        assertEquals(ClipboardDecision.READ, policy.decide(observation(timestamp = 7L)))
    }
}
