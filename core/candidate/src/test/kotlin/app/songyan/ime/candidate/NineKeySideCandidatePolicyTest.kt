package app.songyan.ime.candidate

import app.songyan.ime.layout.WideMiddleGap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NineKeySideCandidatePolicyTest {
    @Test
    fun leftIsTheDefaultAndMirroringSwapsSides() {
        assertEquals(NineKeyCandidateSide.LEFT, NineKeyCandidateSide.resolve(null))
        assertEquals(NineKeyCandidateSide.OFF, NineKeyCandidateSide.resolve("off"))
        assertEquals(NineKeyCandidateSide.RIGHT, NineKeyCandidateSide.LEFT.mirrored)
        assertEquals(NineKeyCandidateSide.OFF, NineKeyCandidateSide.OFF.mirrored)
    }

    /** The same numbers as the iOS keyboard. */
    @Test
    fun theGapComesOutOfTheKeysAndTheCandidatesSitOppositeAtNinetyPercent() {
        val phone = NineKeySideCandidatePolicy.plan(790.0, 444.0, NineKeyCandidateSide.LEFT)!!
        assertEquals(WideMiddleGap.width(790.0, 10), phone.stripWidth, 1e-9)
        assertEquals((790.0 - phone.stripWidth) / 1.9, phone.keypadWidth, 1e-9)
        assertEquals(phone.keypadWidth * 0.9, phone.candidateWidth, 1e-9)
        assertEquals(KeyboardContentDock.RIGHT, phone.keypadDock)
        val tablet = NineKeySideCandidatePolicy.plan(1200.0, 444.0, NineKeyCandidateSide.RIGHT)!!
        assertEquals(444.0, tablet.keypadWidth, 1e-9)
        assertEquals(KeyboardContentDock.LEFT, tablet.keypadDock)
        assertTrue(tablet.stripWidth > phone.stripWidth)
    }

    @Test
    fun tooLittleRoomOrOffLeavesTheSideBlank() {
        assertNull(NineKeySideCandidatePolicy.plan(570.0, 444.0, NineKeyCandidateSide.LEFT))
        assertNotNull(NineKeySideCandidatePolicy.plan(574.0, 444.0, NineKeyCandidateSide.LEFT))
        assertNull(NineKeySideCandidatePolicy.plan(1000.0, 444.0, NineKeyCandidateSide.OFF))
    }
}
