package app.songyan.ime.tools.regex

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Runs one regular expression step in the private `:tools` process and keeps the caller's deadline.
 *
 * Kotlin's [Regex] backtracks inside native code and cannot be stopped from within the match, so the
 * deadline is enforced by killing that process: [RegexRun.TimedOut] is only reported after the
 * worker's death was observed, never while it may still be running. One request at a time; the
 * first call of the process pays for starting the worker and says so in [statistics].
 */
object ToolRegex {
    const val DEFAULT_BUDGET_MS: Int = 100
    val DEFAULT_LIMITS: RegexLimits = RegexLimits()

    private const val TAG = "ToolRegex"
    private const val HANDSHAKE_TIMEOUT_MS = 5_000L
    private const val EXIT_ACKNOWLEDGEMENT_MS = 1_000L

    private val mutex = Mutex()
    private val tokens = AtomicInteger()

    private val replyThread: HandlerThread by lazy {
        HandlerThread("tool-regex-replies").apply { start() }
    }
    private val replyMessenger: Messenger by lazy {
        Messenger(
            Handler(replyThread.looper) { message ->
                onReply(message)
                true
            },
        )
    }

    @Volatile private var pending: Pending? = null
    private var workerInUse: Worker? = null
    private var binds = 0
    private var coldBindMs = 0L
    private var lastBindMs = 0L

    /** Runs [pattern] over [input] in the worker; [replacement] null asks whether anything matches. */
    suspend fun run(
        context: Context,
        pattern: String,
        input: String,
        replacement: String? = null,
        budgetMs: Int = DEFAULT_BUDGET_MS,
        limits: RegexLimits = DEFAULT_LIMITS,
    ): RegexRun {
        limits.rejectionOf(pattern, input, replacement)?.let { return it }
        require(budgetMs > 0) { "a deadline needs a positive budget" }
        val appContext = context.applicationContext
        return mutex.withLock {
            val worker = liveWorker(appContext) ?: return@withLock RegexRun.WorkerFailed
            runOnce(appContext, worker, pattern, input, replacement, budgetMs)
        }
    }

    /** How the worker was started in this process; the cold start is not part of a match's budget. */
    internal fun statistics(): RegexStatistics = RegexStatistics(binds, coldBindMs, lastBindMs)

    /** The live worker's pid, for tests that need to tell a replaced worker from a reused one. */
    internal fun workerPidForTest(): Int? = workerInUse?.takeIf { !it.died.isCompleted }?.pid

    private suspend fun liveWorker(appContext: Context): Worker? {
        workerInUse?.let { current ->
            if (!current.died.isCompleted) return current
            workerInUse = null
            current.release()
        }
        val startedAt = SystemClock.elapsedRealtime()
        val worker = connect(appContext) ?: return null
        try {
            if (!handshake(worker)) {
                worker.release()
                return null
            }
        } catch (e: CancellationException) {
            // A cancelled caller must not leave the binding behind.
            worker.release()
            throw e
        }
        val took = SystemClock.elapsedRealtime() - startedAt
        binds++
        if (binds == 1) coldBindMs = took else lastBindMs = took
        workerInUse = worker
        return worker
    }

    private suspend fun connect(appContext: Context): Worker? =
        suspendCancellableCoroutine<Worker?> { continuation ->
            val died = CompletableDeferred<Unit>()
            val recipient = IBinder.DeathRecipient { died.complete(Unit) }
            val connection =
                object : ServiceConnection {
                    override fun onServiceConnected(
                        name: ComponentName,
                        service: IBinder,
                    ) {
                        runCatching { service.linkToDeath(recipient, 0) }
                        if (continuation.isActive) {
                            continuation.resume(Worker(appContext, this, Messenger(service), died))
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName) {
                        died.complete(Unit)
                    }

                    override fun onBindingDied(name: ComponentName) {
                        died.complete(Unit)
                    }
                }
            val bound =
                runCatching {
                    appContext.bindService(
                        Intent(appContext, ToolRegexWorkerService::class.java),
                        connection,
                        Context.BIND_AUTO_CREATE,
                    )
                }.getOrDefault(false)
            if (!bound) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { runCatching { appContext.unbindService(connection) } }
        }

    private suspend fun handshake(worker: Worker): Boolean {
        val token = tokens.incrementAndGet()
        val reply = CompletableDeferred<Bundle>()
        pending = Pending(token, RegexDeadlineGate(), reply)
        return try {
            if (!send(worker, ToolRegexWorkerService.MSG_HELLO, Bundle.EMPTY, token)) return false
            val answer = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) { reply.await() } ?: return false
            worker.pid = answer.getInt(ToolRegexWorkerService.KEY_PID)
            worker.pid > 0
        } finally {
            pending = null
        }
    }

    private suspend fun runOnce(
        appContext: Context,
        worker: Worker,
        pattern: String,
        input: String,
        replacement: String?,
        budgetMs: Int,
    ): RegexRun {
        val gate = RegexDeadlineGate()
        val token = tokens.incrementAndGet()
        val reply = CompletableDeferred<Bundle>()
        val request =
            Bundle().apply {
                putString(ToolRegexWorkerService.KEY_PATTERN, pattern)
                putString(ToolRegexWorkerService.KEY_INPUT, input)
                putString(ToolRegexWorkerService.KEY_REPLACEMENT, replacement)
                putInt(ToolRegexWorkerService.KEY_BUDGET_MS, budgetMs)
            }
        val startedAt = SystemClock.elapsedRealtime()
        pending = Pending(token, gate, reply)
        try {
            if (!send(worker, ToolRegexWorkerService.MSG_RUN, request, token)) {
                workerInUse = null
                worker.release()
                return RegexRun.WorkerFailed
            }
            val answer = withTimeoutOrNull(budgetMs.toLong()) { reply.await() }
            if (answer != null) return answer.toRun()
            if (!gate.deadline()) {
                // The answer won the race as the timeout fired; it is already on its way.
                if (worker.died.isCompleted) {
                    workerInUse = null
                    worker.release()
                    return RegexRun.WorkerFailed
                }
                return reply.await().toRun()
            }
            if (worker.died.isCompleted) {
                // Died on its own before the deadline: nothing was killed and nothing is burning.
                workerInUse = null
                worker.release()
                return RegexRun.WorkerFailed
            }
            return abandon(worker, startedAt)
        } catch (e: CancellationException) {
            // A cancelled caller must not leave the pattern burning in the worker.
            withContext(NonCancellable) { abandon(worker, startedAt) }
            throw e
        } finally {
            pending = null
        }
    }

    /**
     * Kills the worker and waits for its death. The deadline is only reported once that death was
     * observed; without that acknowledgement the match may still be running, so the answer is a
     * failure and never a claim that it ended.
     */
    private suspend fun abandon(
        worker: Worker,
        startedAt: Long,
    ): RegexRun {
        val pid = worker.pid
        val signalled = runCatching { Process.killProcess(pid) }.isSuccess
        if (!signalled) Log.w(TAG, "could not signal the regex worker " + pid)
        val exited = withTimeoutOrNull(EXIT_ACKNOWLEDGEMENT_MS) { worker.died.await() } != null
        workerInUse = null
        worker.release()
        if (!exited) {
            Log.w(TAG, "the regex worker " + pid + " did not confirm its death")
            return RegexRun.WorkerFailed
        }
        return RegexRun.TimedOut(pid, SystemClock.elapsedRealtime() - startedAt)
    }

    private fun send(
        worker: Worker,
        what: Int,
        data: Bundle,
        token: Int,
    ): Boolean =
        runCatching {
            worker.messenger.send(
                Message.obtain(null, what).apply {
                    this.data = data
                    arg1 = token
                    replyTo = replyMessenger
                },
            )
        }.isSuccess

    private fun onReply(message: Message) {
        val current = pending ?: return
        if (message.arg1 != current.token) return
        if (current.gate.reply()) current.reply.complete(message.data ?: Bundle.EMPTY)
    }

    private fun Bundle.toRun(): RegexRun =
        when (getInt(ToolRegexWorkerService.KEY_KIND)) {
            ToolRegexWorkerService.KIND_DONE ->
                RegexRun.Done(
                    getString(ToolRegexWorkerService.KEY_TEXT).orEmpty(),
                    getInt(ToolRegexWorkerService.KEY_MATCHES),
                )
            ToolRegexWorkerService.KIND_NO_MATCH -> RegexRun.NoMatch
            ToolRegexWorkerService.KIND_INVALID -> RegexRun.PatternInvalid
            else -> RegexRun.WorkerFailed
        }

    private class Pending(
        val token: Int,
        val gate: RegexDeadlineGate,
        val reply: CompletableDeferred<Bundle>,
    )

    private class Worker(
        private val appContext: Context,
        private val connection: ServiceConnection,
        val messenger: Messenger,
        val died: CompletableDeferred<Unit>,
    ) {
        var pid: Int = 0
        private var released = false

        fun release() {
            if (released) return
            released = true
            runCatching { appContext.unbindService(connection) }
        }
    }
}

/** How the worker was started and kept; the cold start is reported apart from any match budget. */
internal data class RegexStatistics(
    val binds: Int,
    val coldBindMs: Long,
    val lastBindMs: Long,
)
