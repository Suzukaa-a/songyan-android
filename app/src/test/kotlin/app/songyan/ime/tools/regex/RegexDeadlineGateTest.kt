package app.songyan.ime.tools.regex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * A request ends exactly once: either the answer arrived or the deadline passed. Only the winner may
 * act, so a late answer is dropped and the worker is killed once, never twice.
 */
class RegexDeadlineGateTest {
    @Test
    fun anAnswerThatArrivesFirstIsKept() {
        val gate = RegexDeadlineGate()

        assertTrue(gate.reply())
        assertFalse("the deadline must not claim a request that ended", gate.deadline())
        assertFalse(gate.expired)
    }

    @Test
    fun theDeadlineClaimsTheRequestWhileNoAnswerCame() {
        val gate = RegexDeadlineGate()

        assertTrue(gate.deadline())
        assertTrue("the worker must be killed once the deadline wins", gate.expired)
        assertFalse("an answer arriving after the deadline is dropped", gate.reply())
    }

    @Test
    fun onlyOneOfTwoDeadlineChecksKillsTheWorker() {
        val gate = RegexDeadlineGate()
        val kills = AtomicInteger()

        if (gate.deadline()) kills.incrementAndGet()
        if (gate.deadline()) kills.incrementAndGet()

        assertEquals(1, kills.get())
    }

    @Test
    fun aRaceOfAnAnswerAndADeadlineActsOnce() {
        repeat(200) {
            val gate = RegexDeadlineGate()
            val armed = CountDownLatch(1)
            val both = CountDownLatch(2)
            val actions = AtomicInteger()
            val answer =
                Thread {
                    armed.await()
                    if (gate.reply()) actions.incrementAndGet()
                    both.countDown()
                }
            val deadline =
                Thread {
                    armed.await()
                    if (gate.deadline()) actions.incrementAndGet()
                    both.countDown()
                }
            answer.start()
            deadline.start()
            armed.countDown()
            both.await()
            assertEquals("exactly one side may act", 1, actions.get())
        }
    }
}
