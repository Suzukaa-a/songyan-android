package app.songyan.ime.deploy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeployWorkerStateTest {
    private val state = DeployWorkerState()

    private fun request(id: String) = DeployRequest(id, DeployMode.REDEPLOY_ACTIVE)

    @Test
    fun aWorkerStartsOnlyWhenNoneRuns() {
        state.queue.offer(request("a"))
        assertTrue(state.started(1))
        state.queue.offer(DeployRequest("b", DeployMode.ACTIVATE_SOURCE, sourceId = "s"))
        assertFalse("the running worker takes it", state.started(2))
        assertEquals("a", state.next()?.request?.requestId)
        assertEquals("b", state.next()?.request?.requestId)
        assertNull(state.next())
        assertEquals(2, state.idleStartId())
    }

    /**
     * A request that arrives after the worker found the queue empty, but before that worker
     * stopped the service, starts a worker of its own; the ending worker must not stop the
     * service under it. It used to, and the system froze the deploy process mid-deployment.
     */
    @Test
    fun aWorkerEndingAsTheNextRequestArrivesLeavesTheServiceRunning() {
        state.queue.offer(request("a"))
        assertTrue(state.started(1))
        state.next()
        assertNull(state.next())

        state.queue.offer(request("b"))
        assertTrue("the new request gets a worker", state.started(2))

        assertNull("the ending worker must not stop the service", state.idleStartId())
        assertEquals("b", state.next()?.request?.requestId)
        assertNull(state.next())
        assertEquals(2, state.idleStartId())
    }

    @Test
    fun aStartWithoutWorkLeavesAnIdleServiceStoppable() {
        assertFalse(state.started(5))
        assertFalse(state.busy)
        assertEquals(5, state.idleStartId())
    }

    /** The deploy job adds work without a start command; the service's newest start id stays the one to stop with. */
    @Test
    fun workFromTheJobStartsAWorkerAndKeepsTheStartId() {
        assertFalse(state.started(3))
        state.queue.offer(request("a"))
        assertTrue(state.woken())
        state.queue.offer(DeployRequest("b", DeployMode.ACTIVATE_SOURCE, sourceId = "s"))
        assertFalse("the running worker takes it", state.woken())
        assertEquals("a", state.next()?.request?.requestId)
        assertEquals("b", state.next()?.request?.requestId)
        assertNull(state.next())
        assertEquals(3, state.idleStartId())
    }

    /** A cancelled worker hands what was queued meanwhile to a new one, and only then. */
    @Test
    fun anInterruptedWorkerIsReplacedOnlyWhenWorkWaits() {
        state.queue.offer(request("a"))
        assertTrue(state.started(1))
        state.next()
        state.queue.offer(DeployRequest("b", DeployMode.ACTIVATE_SOURCE, sourceId = "s"))
        assertFalse("still running until the cancelled worker ends", state.started(2))
        assertTrue(state.interrupted())
        assertTrue(state.busy)
        assertEquals("b", state.next()?.request?.requestId)
        assertNull(state.next())
        assertFalse(state.interrupted())
        assertFalse(state.busy)
    }
}
