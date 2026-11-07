package app.songyan.ime.tools.regex

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.regex.PatternSyntaxException

/**
 * Runs one regular expression step in a process of its own (`:tools`).
 *
 * Kotlin's [Regex] backtracks inside native code and cannot be stopped from within the match, so the
 * caller enforces its deadline by killing this process; the death of the process is what ends the
 * match. Nothing else runs here (no engine, no disk, no network), so a killed worker leaves nothing
 * behind. It also ends itself shortly after the longest deadline a caller may use, so a caller that
 * disappears cannot leave a pattern burning.
 */
class ToolRegexWorkerService : Service() {
    private var thread: HandlerThread? = null
    private var watchdog: ScheduledExecutorService? = null
    private var messenger: Messenger? = null

    override fun onCreate() {
        super.onCreate()
        val worker = HandlerThread("tool-regex-worker").apply { start() }
        thread = worker
        messenger =
            Messenger(
                Handler(worker.looper) { message ->
                    onMessage(message)
                    true
                },
            )
        watchdog =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "tool-regex-watchdog").apply { isDaemon = true }
            }
    }

    override fun onBind(intent: Intent?): IBinder? = messenger?.binder

    override fun onDestroy() {
        // A match backtracks inside native code, so neither quitting the looper nor the self-kill
        // timer above ends it once the caller's binding is gone. Nothing else lives in this process,
        // so ending it is how the match is ended.
        Process.killProcess(Process.myPid())
        watchdog?.shutdownNow()
        thread?.quitSafely()
        super.onDestroy()
    }

    private fun onMessage(message: Message) {
        val replyTo = message.replyTo ?: return
        when (message.what) {
            MSG_HELLO ->
                reply(
                    replyTo,
                    message.arg1,
                    Bundle().apply { putInt(KEY_PID, Process.myPid()) },
                )
            MSG_RUN -> runMatch(message, replyTo)
            else -> Unit
        }
    }

    private fun runMatch(
        message: Message,
        replyTo: Messenger,
    ) {
        val token = message.arg1
        val request = message.data
        val pattern = request?.getString(KEY_PATTERN)
        val input = request?.getString(KEY_INPUT)
        if (pattern == null || input == null) return reply(replyTo, token, failed())
        val replacement = request.getString(KEY_REPLACEMENT)
        val budgetMs = request.getInt(KEY_BUDGET_MS)
        val selfKill =
            watchdog?.schedule(
                { Process.killProcess(Process.myPid()) },
                budgetMs * SELF_KILL_BUDGET_FACTOR + SELF_KILL_SLACK_MS,
                TimeUnit.MILLISECONDS,
            )
        try {
            val regex = Regex(pattern)
            val matches = regex.findAll(input).count()
            val text = replacement?.let { regex.replace(input, it) } ?: input
            val kind = if (replacement == null && matches == 0) KIND_NO_MATCH else KIND_DONE
            reply(
                replyTo,
                token,
                Bundle().apply {
                    putInt(KEY_KIND, kind)
                    putString(KEY_TEXT, text)
                    putInt(KEY_MATCHES, matches)
                },
            )
        } catch (_: PatternSyntaxException) {
            reply(replyTo, token, Bundle().apply { putInt(KEY_KIND, KIND_INVALID) })
        } catch (e: Throwable) {
            Log.w(TAG, "the pattern failed", e)
            reply(replyTo, token, failed())
        } finally {
            selfKill?.cancel(false)
        }
    }

    private fun failed(): Bundle = Bundle().apply { putInt(KEY_KIND, KIND_FAILED) }

    private fun reply(
        replyTo: Messenger,
        token: Int,
        data: Bundle,
    ) {
        // The caller may be gone; its answer has nowhere to go then.
        runCatching {
            replyTo.send(
                Message.obtain(null, MSG_RESULT).apply {
                    arg1 = token
                    this.data = data
                },
            )
        }
    }

    companion object {
        private const val TAG = "ToolRegexWorker"

        internal const val MSG_HELLO: Int = 1
        internal const val MSG_RUN: Int = 2
        internal const val MSG_RESULT: Int = 3

        internal const val KEY_PATTERN: String = "app.songyan.ime.tools.regex.PATTERN"
        internal const val KEY_INPUT: String = "app.songyan.ime.tools.regex.INPUT"
        internal const val KEY_REPLACEMENT: String = "app.songyan.ime.tools.regex.REPLACEMENT"
        internal const val KEY_BUDGET_MS: String = "app.songyan.ime.tools.regex.BUDGET_MS"
        internal const val KEY_KIND: String = "app.songyan.ime.tools.regex.KIND"
        internal const val KEY_TEXT: String = "app.songyan.ime.tools.regex.TEXT"
        internal const val KEY_MATCHES: String = "app.songyan.ime.tools.regex.MATCHES"
        internal const val KEY_PID: String = "app.songyan.ime.tools.regex.PID"

        internal const val KIND_DONE: Int = 1
        internal const val KIND_NO_MATCH: Int = 2
        internal const val KIND_INVALID: Int = 3
        internal const val KIND_FAILED: Int = 4

        private const val SELF_KILL_BUDGET_FACTOR = 5L
        private const val SELF_KILL_SLACK_MS = 1_000L
    }
}
