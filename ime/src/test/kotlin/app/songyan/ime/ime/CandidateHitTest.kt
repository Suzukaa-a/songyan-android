package app.songyan.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class CandidateHitTest {
    private val cells = listOf(listOf(0f, 0f, 40f, 48f), listOf(45f, 0f, 90f, 48f))

    @Test
    fun aTapInTheGapPicksTheNearerCandidate() {
        assertEquals(0, CandidateHit.nearest(cells, 42f, 20f, 5f))
        assertEquals(1, CandidateHit.nearest(cells, 43.5f, 20f, 5f))
    }

    @Test
    fun aTapAwayFromEveryCandidateOrOffTheRowPicksNone() {
        assertEquals(-1, CandidateHit.nearest(cells, 120f, 20f, 5f))
        assertEquals(-1, CandidateHit.nearest(cells, 42f, 60f, 5f))
    }
}
