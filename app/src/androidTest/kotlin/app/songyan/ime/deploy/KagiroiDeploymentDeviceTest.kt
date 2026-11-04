package app.songyan.ime.deploy

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.deploy.probe.KagiroiDeployProbeService
import app.songyan.ime.rime.RimeBindings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in because the external dictionaries are large and source compilation can exhaust device memory. */
@RunWith(AndroidJUnit4::class)
class KagiroiDeploymentDeviceTest {
    @Before
    fun asked() {
        assumeTrue("pass -e kagiroiProbe true to measure a deployment", argument(ARG_PROBE) == "true")
    }

    @Test
    fun pathA_sourceYamlDeploy() = measure(KagiroiDeployProbeService.MODE_SOURCE, "source")

    @Test
    fun pathB_prebuiltCompiledDeploy() = measure(KagiroiDeployProbeService.MODE_PREBUILT, "prebuilt")

    private fun measure(
        mode: String,
        directory: String,
    ) {
        val fixtureRoot = File(requireNotNull(argument(ARG_FIXTURE)) { "missing -e " + ARG_FIXTURE + " <directory>" })
        val fixture = File(fixtureRoot, directory)
        assertTrue("fixture directory missing: $fixture", fixture.isDirectory && fixture.canRead())
        if (mode == KagiroiDeployProbeService.MODE_PREBUILT) {
            assertTrue("prebuilt compiled files missing: " + File(fixture, "build"), File(fixture, "build").isDirectory)
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "kagiroi-probe/$mode")
        root.deleteRecursively()
        val startedMs = System.currentTimeMillis()
        ContextCompat.startForegroundService(
            context,
            Intent(context, KagiroiDeployProbeService::class.java)
                .putExtra(KagiroiDeployProbeService.EXTRA_MODE, mode)
                .putExtra(KagiroiDeployProbeService.EXTRA_FIXTURE_ROOT, fixture.absolutePath),
        )
        val report = awaitReport(context, root, startedMs)
        Log.i(TAG, "kagiroi " + mode + ": " + report)
        val stream = Bundle().apply { putString("stream", metric(mode, report) + "\n") }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, stream)
        assertEquals("probe failed: " + report.optString("error"), "done", report.optString("state"))
        assertEquals(mode, report.optString("mode"))
        assertTrue(
            "probe did not run in the :deploy process: " + report.optString("processName"),
            report.optString("processName").endsWith(":deploy"),
        )
        assertTrue("deployment failed: " + report.optString("deployError"), report.optBoolean("deployed"))
        assertEquals(
            "librime " + report.optString("engineVersion") + " deployed",
            RimeBindings.ENGINE_VERSION,
            report.optString("engineVersion"),
        )
        val schemas = report.getJSONArray("schemaIds")
        assertTrue("no kagiroi in $schemas", (0 until schemas.length()).any { schemas.optString(it) == "kagiroi" })
        val top = report.getJSONObject("candidates").getJSONArray("top")
        val texts = (0 until top.length()).map { top.optString(it) }
        assertTrue("nihongo produced no 日本語: $texts", texts.contains("日本語"))
    }

    private fun awaitReport(
        context: Context,
        root: File,
        startedMs: Long,
    ): JSONObject {
        val file = File(root, KagiroiDeployProbeService.REPORT_FILE)
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val text = if (file.isFile) runCatching { file.readText() }.getOrNull() else null
            if (!text.isNullOrBlank()) runCatching { JSONObject(text) }.getOrNull()?.let { return it }
            diedSince(context, startedMs)?.let { throw AssertionError(it) }
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("no probe report within " + TIMEOUT_MS / 60_000 + " minutes: $file")
    }

    /**
     * How the :deploy process died after [startedMs], or null when it is alive or the platform keeps
     * no exit history (API 29 falls back to the timeout). rss and pss are the system's exit-statistic
     * values in KiB, not the probe's VmHWM.
     */
    private fun diedSince(
        context: Context,
        startedMs: Long,
    ): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val exit =
            runCatching { manager.getHistoricalProcessExitReasons(context.packageName, 0, EXIT_RECORDS) }
                .getOrNull()
                ?.firstOrNull { it.timestamp >= startedMs && (it.processName ?: "").endsWith(":deploy") }
                ?: return null
        return ":deploy exited before writing a report: reason=" + exit.reason +
            " status=" + exit.status +
            " process=" + exit.processName +
            " rssKib=" + exit.rss +
            " pssKib=" + exit.pss +
            " timestamp=" + exit.timestamp +
            " elapsedMs=" + (exit.timestamp - startedMs) +
            " (rss/pss are the system's exit statistics in KiB, not VmHWM)"
    }

    private fun metric(
        mode: String,
        report: JSONObject,
    ): String {
        val memory = report.optJSONObject("memory") ?: JSONObject()
        return "kagiroi mode=$mode state=" + report.optString("state") +
            " process=" + report.optString("processName") +
            " pid=" + report.optInt("pid") +
            " processAgeMs=" + report.optLong("processAgeMs") +
            " copyMs=" + report.optLong("copyMs") +
            " deployMs=" + report.optLong("deployMs") +
            " totalMs=" + report.optLong("totalMs") +
            " peakVmHwmBytes=" + memory.optLong("peakVmHwmBytes") +
            " peakPssBytes=" + memory.optLong("peakPssBytes") +
            " sharedBytes=" + report.optLong("sharedBytes") +
            " compiledBytes=" + report.optLong("compiledBytes") +
            " engine=" + report.optString("engineVersion") +
            " error=" + report.optString("error")
    }

    private companion object {
        const val ARG_PROBE = "kagiroiProbe"
        const val ARG_FIXTURE = "fixtureRoot"
        const val TAG = "KagiroiProbe"
        const val POLL_MS = 1000L
        const val TIMEOUT_MS = 45L * 60 * 1000
        const val EXIT_RECORDS = 5

        fun argument(name: String): String? = InstrumentationRegistry.getArguments().getString(name)
    }
}
