package app.songyan.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class CandidateRevealTest {
    @Test
    fun theFirstRowStaysDrawnFromTheFirstFrame() {
        // Hiding the whole grid and revealing it again made the row under the finger flash.
        assertEquals(120f, CandidateBarView.revealedHeight(600f, 120f, 0f), 0f)
        assertEquals(360f, CandidateBarView.revealedHeight(600f, 120f, 0.5f), 0f)
        assertEquals(600f, CandidateBarView.revealedHeight(600f, 120f, 1f), 0f)
    }

    @Test
    fun aSpringOvershootNeverDrawsPastTheSlot() {
        assertEquals(600f, CandidateBarView.revealedHeight(600f, 120f, 1.08f), 0f)
        assertEquals(120f, CandidateBarView.revealedHeight(600f, 120f, -0.05f), 0f)
        assertEquals(100f, CandidateBarView.revealedHeight(100f, 120f, 0f), 0f)
    }
}
