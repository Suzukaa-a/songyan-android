package app.songyan.ime.tools.regex

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The real worker process behind [ToolRegex]: a pathological pattern ends as a deadline with a dead
 * worker, the main thread keeps running, and the caps refuse an oversized text instead of the
 * deadline claiming it.
 */
@RunWith(AndroidJUnit4::class)
class RegexDeadlineDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val main = Handler(Looper.getMainLooper())

    @Test
    fun anOrdinaryPatternUsesTheStandardReplacementSemantics() {
        assertEquals(RegexRun.Done("c-t", 1), run("a+", "caaat", "-"))
        assertEquals(RegexRun.Done("host:me", 1), run("(\\w+)@(\\w+)", "me@host", "\$2:\$1"))
        assertEquals("every match counts", RegexRun.Done("bbb", 3), run("a", "aaa", "b"))
    }

    @Test
    fun anEmptyTextAndAMatchOnlyCallAreNotErrors() {
        assertEquals(RegexRun.Done("", 0), run("a+", "", "-"))
        assertEquals("a match only call reports nothing matched", RegexRun.NoMatch, run("a+", "", null))
        assertEquals(RegexRun.Done("aaa", 3), run("a", "aaa", null))
    }

    @Test
    fun anInvalidPatternIsReportedAsInvalid() {
        assertEquals(RegexRun.PatternInvalid, run("(", "a", "-"))
    }

    @Test(timeout = 20_000)
    fun aTextOverTheLimitIsRefusedWithoutStartingTheWorker() {
        val binds = ToolRegex.statistics().binds

        assertEquals(RegexRun.TooLong(RegexField.INPUT), run("a", "a".repeat(1001), "-"))
        assertEquals("a refused text must not start the worker", binds, ToolRegex.statistics().binds)
    }

    @Test(timeout = 20_000)
    fun aTextAtTheRaisedBoundIsReallyMatched() {
        val text = "a".repeat(6000)

        assertEquals(RegexRun.Done(text, 1), run("^a{6000}\$", text, null, limits = RegexLimits(input = 6000)))
    }

    @Test(timeout = 20_000)
    fun aPathologicalPatternTimesOutInUnderTwoHundredMillisecondsAndItsWorkerDies() {
        assertTrue("a warm worker is needed to measure the match", run("a+", "a", "-") is RegexRun.Done)
        val heartbeat = MainHeartbeat(main).start()
        val started = SystemClock.elapsedRealtime()
        val result = run("(a+)+\$", "a".repeat(5000) + "b", "-", limits = RegexLimits(input = 6000))
        val elapsed = SystemClock.elapsedRealtime() - started
        heartbeat.stop()
        val timedOut = result as? RegexRun.TimedOut ?: throw AssertionError("expected a deadline, got $result")
        Log.i(
            TAG,
            "deadline elapsed=$elapsed ms mainMaxGap=${heartbeat.maxGapMs} ms beats=${heartbeat.beats}",
        )

        assertTrue("the deadline must return in under 200 ms, took $elapsed ms", elapsed < 200)
        assertNotEquals(
            "the match must run in the private worker, not in this process",
            Process.myPid(),
            timedOut.workerPid,
        )
        assertTrue("the worker process must be gone: /${timedOut.workerPid}", awaitProcessGone(timedOut.workerPid))
        assertTrue("no worker may be left burning: ${toolProcesses()}", awaitNoToolProcess())
        assertTrue("the main thread was blocked for ${heartbeat.maxGapMs} ms", heartbeat.maxGapMs < 100)
        assertTrue("the heartbeat did not sample the wait", heartbeat.beats > 2)
    }

    @Test(timeout = 30_000)
    fun aRequestAfterATimeoutStartsAFreshWorker() {
        assertTrue(run("a+", "a", "-") is RegexRun.Done)
        val died = run("(a+)+\$", "a".repeat(5000) + "b", "-", limits = RegexLimits(input = 6000))
        val timedOut = died as? RegexRun.TimedOut ?: throw AssertionError("expected a deadline, got $died")

        assertEquals("the worker is replaced, not reused", RegexRun.Done("c-t", 1), run("a+", "caaat", "-"))
        val fresh = ToolRegex.workerPidForTest()
        assertNotNull("a successful call must have a live worker", fresh)
        assertNotEquals("the killed worker must not come back", timedOut.workerPid, fresh)
        assertNotEquals("the worker is another process", Process.myPid(), fresh)
    }

    @Test(timeout = 20_000)
    fun theWorkerProcessStartIsMeasuredApartFromTheMatch() {
        assertEquals(RegexRun.Done("c-t", 1), run("a+", "caaat", "-"))
        val started = SystemClock.elapsedRealtime()
        assertEquals(RegexRun.Done("c-t", 1), run("a+", "caaat", "-"))
        val warm = SystemClock.elapsedRealtime() - started
        val statistics = ToolRegex.statistics()
        Log.i(TAG, "cold bind=${statistics.coldBindMs} ms warm call=$warm ms binds=${statistics.binds}")

        assertTrue("the first bind of this process was measured", statistics.coldBindMs > 0)
        assertTrue("a warm call must not pay for a process start", warm < statistics.coldBindMs)
    }

    /**
     * A caller that disappears while the pattern is still running must not leave it burning. This
     * test binds the worker itself, so the client's own deadline, which kills, never runs here.
     */
    @Test(timeout = 30_000)
    fun aWorkerWhoseBindingIsGoneMidMatchEndsItself() {
        val replies = HandlerThread("regex-raw-replies").apply { start() }
        val connected = CountDownLatch(1)
        val hello = CountDownLatch(1)
        val result = CountDownLatch(1)
        val died = CountDownLatch(1)
        val workerPid = AtomicInteger()
        val replyMessenger =
            Messenger(
                Handler(replies.looper) { message ->
                    val data = message.data
                    when {
                        data?.containsKey(ToolRegexWorkerService.KEY_PID) == true -> {
                            workerPid.set(data.getInt(ToolRegexWorkerService.KEY_PID))
                            hello.countDown()
                        }
                        message.what == ToolRegexWorkerService.MSG_RESULT -> result.countDown()
                    }
                    true
                },
            )
        val recipient = IBinder.DeathRecipient { died.countDown() }
        val service = arrayOfNulls<IBinder>(1)
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName,
                    binder: IBinder,
                ) {
                    runCatching { binder.linkToDeath(recipient, 0) }
                    service[0] = binder
                    connected.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName) = died.countDown()

                override fun onBindingDied(name: ComponentName) = died.countDown()
            }
        val bound = AtomicBoolean()
        main2 {
            bound.set(
                context.bindService(
                    Intent(context, ToolRegexWorkerService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE,
                ),
            )
        }
        assertTrue("the worker service could not be bound", bound.get())
        assertTrue("the worker service never connected", connected.await(5, TimeUnit.SECONDS))
        val messenger = Messenger(requireNotNull(service[0]))
        messenger.send(Message.obtain(null, ToolRegexWorkerService.MSG_HELLO).apply { replyTo = replyMessenger })
        assertTrue("the worker never named itself", hello.await(5, TimeUnit.SECONDS))
        assertNotEquals("the worker is another process", Process.myPid(), workerPid.get())

        messenger.send(
            Message.obtain(null, ToolRegexWorkerService.MSG_RUN).apply {
                arg1 = 7
                data =
                    Bundle().apply {
                        putString(ToolRegexWorkerService.KEY_PATTERN, "(a+)+\$")
                        putString(ToolRegexWorkerService.KEY_INPUT, "a".repeat(5000) + "b")
                        putString(ToolRegexWorkerService.KEY_REPLACEMENT, "-")
                        putInt(ToolRegexWorkerService.KEY_BUDGET_MS, 60_000)
                    }
                replyTo = replyMessenger
            },
        )
        assertFalse("the match must still be running, it answered", result.await(300, TimeUnit.MILLISECONDS))

        main2 { context.unbindService(connection) }

        assertTrue("the worker must end itself once its binding is gone", died.await(5, TimeUnit.SECONDS))
        assertTrue("the worker process must be gone", awaitProcessGone(workerPid.get()))
        assertTrue("no worker may be left burning: ${toolProcesses()}", awaitNoToolProcess())
        replies.quitSafely()
    }

    private fun main2(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun run(
        pattern: String,
        input: String,
        replacement: String?,
        budgetMs: Int = ToolRegex.DEFAULT_BUDGET_MS,
        limits: RegexLimits = ToolRegex.DEFAULT_LIMITS,
    ): RegexRun =
        runBlocking {
            ToolRegex.run(context, pattern, input, replacement, budgetMs, limits)
        }

    private fun awaitProcessGone(pid: Int): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 2_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!File("/proc/$pid").exists()) return true
            Thread.sleep(20)
        }
        return false
    }

    /** A worker left behind would still be listed; a process the system tears down takes a moment. */
    private fun awaitNoToolProcess(): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 2_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (toolProcesses().isEmpty()) return true
            Thread.sleep(50)
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun toolProcesses(): List<String> =
        context
            .getSystemService(ActivityManager::class.java)
            ?.runningAppProcesses
            ?.map { it.processName }
            ?.filter { it.endsWith(":tools") }
            .orEmpty()

    /** Samples the main thread while the caller waits elsewhere; a frozen main thread shows as a gap. */
    private class MainHeartbeat(
        private val main: Handler,
    ) : Runnable {
        private var last = SystemClock.elapsedRealtime()
        private var running = false
        var beats = 0
            private set
        var maxGapMs = 0L
            private set

        fun start(): MainHeartbeat {
            running = true
            last = SystemClock.elapsedRealtime()
            main.postDelayed(this, INTERVAL_MS)
            return this
        }

        fun stop() {
            running = false
            main.removeCallbacks(this)
        }

        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            val gap = now - last
            last = now
            beats++
            if (gap > maxGapMs) maxGapMs = gap
            main.postDelayed(this, INTERVAL_MS)
        }

        private companion object {
            const val INTERVAL_MS = 16L
        }
    }

    private companion object {
        const val TAG = "RegexDeadlineTest"
    }
}
