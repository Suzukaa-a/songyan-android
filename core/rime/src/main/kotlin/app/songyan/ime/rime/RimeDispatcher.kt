package app.songyan.ime.rime

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The one thread that may touch librime, shared by every engine of the process.
 *
 * librime keeps process-global state and the C bridge requires every `rb_*` call to be serialized,
 * so all engine work is confined to a single named thread that lives as long as the process. One
 * thread for the process (not one per engine) means a call that outlives its caller's timeout
 * still finishes before anything else touches librime, and whatever it produced can be cleaned up
 * there instead of being stranded on a thread nobody serves any more.
 */
class RimeDispatcher private constructor(
    private val thread: HandlerThread,
) {
    private val handler = Handler(thread.looper)
    private val dispatcher: CoroutineDispatcher = handler.asCoroutineDispatcher("rime-engine")

    /**
     * Runs [block] on the engine thread, suspending the caller until it returns.
     *
     * The call is not cancellable once started: an engine call that commits text or changes the
     * session must complete and be reported, or the caller would lose a commit librime already
     * made. A cancelled caller still gets the result; it is the caller's job to deliver it.
     */
    suspend fun <T> onEngine(block: () -> T): T = onEngine(onLost = null, block = block)

    /**
     * [onEngine] for calls whose result must reach someone: when the caller was cancelled while
     * the block ran, the result is discarded on resumption, so [onLost] receives it instead (on
     * the caller's thread) and can hand it to the next caller.
     */
    suspend fun <T> onEngine(
        onLost: ((T) -> Unit)?,
        block: () -> T,
    ): T {
        val produced = arrayOfNulls<Any?>(1)
        var ran = false
        try {
            return withContext(NonCancellable + dispatcher) {
                block().also {
                    produced[0] = it
                    ran = true
                }
            }
        } catch (cancelled: CancellationException) {
            @Suppress("UNCHECKED_CAST")
            if (ran) onLost?.invoke(produced[0] as T)
            throw cancelled
        }
    }

    /** Queues [block] on the engine thread without waiting for it. */
    fun post(block: () -> Unit) {
        handler.post(block)
    }

    /**
     * Runs [block] on the engine thread and blocks the caller, for the few places that cannot
     * suspend (`close()`, engine creation).
     *
     * The wait is bounded so a wedged engine surfaces as an error instead of hanging the caller
     * forever. When the wait gives up, the block still runs to completion on the engine thread,
     * and [onAbandoned] receives its result there, so a handle created after the caller left is
     * destroyed instead of keeping the process-wide single-engine slot taken. A call that already
     * runs on the engine thread executes inline, which keeps re-entrant teardown from deadlocking.
     */
    fun <T> onEngineBlocking(
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        onAbandoned: ((T) -> Unit)? = null,
        block: () -> T,
    ): Result<T> {
        if (Thread.currentThread() === thread) return runCatching(block)
        val latch = CountDownLatch(1)
        val abandoned = AtomicBoolean(false)
        val cleaned = AtomicBoolean(false)
        var outcome: Result<T>? = null

        fun cleanUp() {
            if (!cleaned.compareAndSet(false, true)) return
            outcome?.onSuccess { value -> onAbandoned?.let { runCatching { it(value) } } }
        }
        val posted =
            handler.post {
                outcome = runCatching(block)
                latch.countDown()
                if (abandoned.get()) cleanUp()
            }
        if (!posted) {
            return Result.failure(
                RimeException(RimeException.Kind.OPERATION_FAILED, "engine thread is shutting down"),
            )
        }
        if (!latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            abandoned.set(true)
            // The block may have finished between the timeout and the flag; then nobody else will
            // clean up, so do it here (on the engine thread, behind the block).
            if (latch.count == 0L) {
                handler.post { cleanUp() }
            }
            return Result.failure(
                RimeException(
                    RimeException.Kind.OPERATION_FAILED,
                    "engine call timed out after ${timeoutMillis}ms",
                ),
            )
        }
        return outcome ?: Result.failure(
            RimeException(RimeException.Kind.OPERATION_FAILED, "engine call produced no result"),
        )
    }

    internal companion object {
        /** Long enough for opening a prebuilt engine on a slow device. */
        const val DEFAULT_TIMEOUT_MILLIS = 240_000L

        /** A full deployment of a large imported scheme compiles for minutes on a slow device. */
        const val DEPLOY_TIMEOUT_MILLIS = 30L * 60 * 1000

        private val shared: RimeDispatcher by lazy {
            val thread = HandlerThread("songyan-rime-engine", Process.THREAD_PRIORITY_DISPLAY)
            thread.start()
            RimeDispatcher(thread)
        }

        /** The process-wide engine thread. */
        fun shared(): RimeDispatcher = shared
    }
}
