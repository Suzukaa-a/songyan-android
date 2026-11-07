package app.songyan.ime.tools.regex

import java.util.concurrent.atomic.AtomicReference

/**
 * Decides once whether a run ended by its answer or by its deadline. Only the winner may act, so a
 * late answer is dropped and the worker is killed exactly once.
 */
internal class RegexDeadlineGate {
    private val end = AtomicReference<End?>(null)

    /** True when this answer ends the run; an answer after the deadline returns false. */
    fun reply(): Boolean = end.compareAndSet(null, End.ANSWER)

    /** True when this deadline ends the run; a deadline after an answer must not kill anything. */
    fun deadline(): Boolean = end.compareAndSet(null, End.DEADLINE)

    val expired: Boolean
        get() = end.get() == End.DEADLINE

    private enum class End { ANSWER, DEADLINE }
}
