package app.songyan.ime.deploy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeployHandoffTest {
    /** Without the job, a follow-up queued once the dataSync time was used up waited for the next keyboard start. */
    @Test
    fun workLeftWithoutTheForegroundGoesToTheJob() {
        assertTrue(DeployHandoff.needsJob(foreground = false, busy = true))
    }

    @Test
    fun aForegroundServiceOrAnIdleWorkerNeedsNoJob() {
        assertFalse(DeployHandoff.needsJob(foreground = true, busy = true))
        assertFalse(DeployHandoff.needsJob(foreground = false, busy = false))
    }

    @Test
    fun aJobInAFreshProcessFindsWhatThePublishedStateLacks() {
        assertEquals(DeployMode.BUILTIN, DeployHandoff.recoveredWork(ready = false, followUpPending = false))
        assertEquals(DeployMode.BUILTIN, DeployHandoff.recoveredWork(ready = false, followUpPending = true))
        assertEquals(DeployMode.COMPLETE_COMPANIONS, DeployHandoff.recoveredWork(ready = true, followUpPending = true))
        assertNull(DeployHandoff.recoveredWork(ready = true, followUpPending = false))
    }
}
