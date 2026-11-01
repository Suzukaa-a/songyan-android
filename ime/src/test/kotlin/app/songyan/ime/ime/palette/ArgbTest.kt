package app.songyan.ime.ime.palette

import org.junit.Assert.assertEquals
import org.junit.Test

class ArgbTest {
    @Test
    fun blendReturnsTheEndpointsAtTheExtremes() {
        val from = 0xFF102030L
        val to = 0xFFA0B0C0L
        assertEquals(from, Argb.blend(from, to, 0.0))
        assertEquals(to, Argb.blend(from, to, 1.0))
    }

    @Test
    fun blendInterpolatesEveryChannel() {
        assertEquals(0xFF808080L, Argb.blend(Argb.BLACK, Argb.WHITE, 0.5))
    }

    @Test
    fun contrastMatchesWcagForTheExtremes() {
        assertEquals(21.0, Argb.contrast(Argb.BLACK, Argb.WHITE), 0.01)
        assertEquals(1.0, Argb.contrast(Argb.WHITE, Argb.WHITE), 0.01)
    }

    @Test
    fun readableOnKeepsAColourThatAlreadyReads() {
        assertEquals(0xFF191D1AL, Argb.readableOn(0xFF191D1AL, 0xFFFFFFFFL, 0xFFECEFEAL))
    }

    @Test
    fun readableOnRepairsALightForegroundOnALightSurface() {
        assertEquals(Argb.BLACK, Argb.readableOn(0xFFBBBBBBL, 0xFFFFFFFFL))
    }

    @Test
    fun readableOnRepairsADarkForegroundOnADarkSurface() {
        assertEquals(Argb.WHITE, Argb.readableOn(0xFF333333L, 0xFF111111L))
    }

    @Test
    fun readableOnChecksEverySurfaceItIsGiven() {
        // Reads on the white keycap (4.7:1) but not on the slightly dimmer panel (4.1:1).
        assertEquals(Argb.BLACK, Argb.readableOn(0xFF737373L, 0xFFFFFFFFL, 0xFFEFEFEFL))
    }
}
