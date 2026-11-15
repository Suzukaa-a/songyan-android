package app.songyan.ime.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ImePerfTest {
    @Before
    fun closeAnyOpenKey() {
        ImePerf.keyDown()
        ImePerf.keyDispatchStart()
        ImePerf.keyDispatched()
    }

    @Test
    fun aKeyWithoutFollowUpEndsWhenItsDispatchEnds() {
        ImePerf.keyDown()
        ImePerf.keyDispatchStart()
        assertTrue(ImePerf.keyOpen)
        ImePerf.keyDispatched()
        assertFalse(ImePerf.keyOpen)
    }

    @Test
    fun aKeyEndsWithTheLastFollowUpItQueued() {
        ImePerf.keyDown()
        ImePerf.keyDispatchStart()
        val first = ImePerf.keyFollowUpQueued()
        val second = ImePerf.keyFollowUpQueued()
        ImePerf.keyDispatched()
        assertTrue("the rail has not rendered yet", ImePerf.keyOpen)
        ImePerf.keyFollowUpDone(first)
        assertTrue(ImePerf.keyOpen)
        ImePerf.keyFollowUpDone(second)
        assertFalse(ImePerf.keyOpen)
    }

    @Test
    fun workQueuedOutsideADispatchIsNotTheKeys() {
        ImePerf.keyDown()
        assertTrue(ImePerf.keyFollowUpQueued() < 0)
        ImePerf.keyDispatchStart()
        ImePerf.keyDispatched()
        assertFalse(ImePerf.keyOpen)
    }

    @Test
    fun aNewerPressEndsTheKeyStillWaitingAndIgnoresItsLateFollowUp() {
        ImePerf.keyDown()
        ImePerf.keyDispatchStart()
        val stale = ImePerf.keyFollowUpQueued()
        ImePerf.keyDispatched()
        ImePerf.keyDown()
        assertTrue(ImePerf.keyOpen)
        ImePerf.keyDispatchStart()
        val current = ImePerf.keyFollowUpQueued()
        ImePerf.keyDispatched()
        ImePerf.keyFollowUpDone(stale)
        assertTrue("the old follow-up does not close the newer key", ImePerf.keyOpen)
        ImePerf.keyFollowUpDone(current)
        assertFalse(ImePerf.keyOpen)
    }
}
