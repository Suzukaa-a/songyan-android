package app.songyan.ime.script

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A callback that parks the calling thread until the isolated service answers. */
internal class CallbackLatch : ScriptIsolatedCallback.Stub() {
    private val latch = CountDownLatch(1)

    var exitCode: Int = -1
        private set

    var stdout: String = ""
        private set

    var stderr: String = ""
        private set

    /** False when the wait ended without an answer (timeout or [abandon]). */
    @Volatile var answered: Boolean = false
        private set

    override fun finished(
        exitCode: Int,
        stdout: String,
        stderr: String,
    ) {
        this.exitCode = exitCode
        this.stdout = stdout
        this.stderr = stderr
        answered = true
        latch.countDown()
    }

    fun await(timeoutMillis: Long = DEFAULT_WAIT_MILLIS): Boolean = latch.await(timeoutMillis, TimeUnit.MILLISECONDS)

    /** Ends the wait without an answer: the isolated process died. [await] then reports without
     *  [answered] set. */
    fun abandon() {
        latch.countDown()
    }

    companion object {
        /** Header parses and case runs; a script run passes its own duration instead. */
        const val DEFAULT_WAIT_MILLIS = 60_000L
    }
}
