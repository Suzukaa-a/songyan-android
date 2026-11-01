package app.songyan.ime.ime.palette

import org.junit.Assert.assertEquals
import org.junit.Test

class TonalPaletteTest {
    @Test
    fun everyAnchorToneReturnsItsOwnAnchor() {
        for (tone in TonalPalette.TONES) {
            assertEquals(grey(tone), greys.tone(tone))
        }
    }

    @Test
    fun intermediateTonesInterpolateBetweenTheirNeighbours() {
        val expected = Argb.blend(grey(99), grey(95), 0.25)
        assertEquals(expected, greys.tone(98))
    }

    @Test
    fun outOfRangeTonesClampToTheEndpoints() {
        assertEquals(grey(100), greys.tone(120))
        assertEquals(grey(0), greys.tone(-5))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aPaletteNeedsAllThirteenAnchors() {
        TonalPalette(listOf(0xFF000000L))
    }

    /** A palette whose anchors equal their own tone, so interpolated values are predictable. */
    private val greys = TonalPalette(TonalPalette.TONES.map(::grey))

    private fun grey(tone: Int): Long {
        val channel = tone / 100.0
        return Argb.of(channel, channel, channel)
    }
}
