package app.songyan.ime.layout

import org.junit.Assert.assertEquals
import org.junit.Test

class WideMiddleGapTest {
    /** The share grows with the width: 10 % at 1000, 8 % at 800, the same numbers as iOS. */
    @Test
    fun theGapGrowsFasterThanTheWidth() {
        assertEquals(100.0, WideMiddleGap.width(1000.0, 10), 1e-9)
        assertEquals(64.0, WideMiddleGap.width(800.0, 10), 1e-9)
        assertEquals(144.0, WideMiddleGap.width(1200.0, 10), 1e-9)
        assertEquals(0.0, WideMiddleGap.width(1000.0, 0), 1e-9)
        assertEquals(200.0, WideMiddleGap.width(1000.0, 99), 1e-9)
    }
}
