package app.songyan.ime.settings.state

import org.junit.Assert.assertEquals
import org.junit.Test

class EngineStatusTest {
    @Test
    fun `preparing percent is clamped for display`() {
        assertEquals(0, EngineStatus.Preparing(-5).displayPercent)
        assertEquals(42, EngineStatus.Preparing(42).displayPercent)
        assertEquals(100, EngineStatus.Preparing(130).displayPercent)
    }

    @Test
    fun `a percentage is only claimed when one was reported`() {
        assertEquals(false, EngineStatus.Preparing(0).hasProgress)
        assertEquals(true, EngineStatus.Preparing(1).hasProgress)
    }
}
