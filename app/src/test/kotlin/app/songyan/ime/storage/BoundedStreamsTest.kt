package app.songyan.ime.storage

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class BoundedStreamsTest {
    @Test
    fun aShortStreamIsReadWhole() {
        assertEquals(5, ByteArrayInputStream(ByteArray(5)).readAtMost(100).size)
    }

    @Test
    fun aLongStreamStopsAtTheLimit() {
        assertEquals(100, ByteArrayInputStream(ByteArray(100_000)).readAtMost(100).size)
    }

    @Test
    fun oneByteOverTheBudgetIsEnoughToTellItIsOversize() {
        val budget = 20_000
        assertEquals(budget + 1, ByteArrayInputStream(ByteArray(budget * 5)).readAtMost(budget + 1).size)
        assertEquals(budget, ByteArrayInputStream(ByteArray(budget)).readAtMost(budget + 1).size)
    }

    @Test
    fun anEmptyStreamIsEmpty() {
        assertEquals(0, ByteArrayInputStream(ByteArray(0)).readAtMost(10).size)
    }
}
