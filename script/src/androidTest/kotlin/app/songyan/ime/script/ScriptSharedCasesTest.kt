package app.songyan.ime.script

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs the shared script cases (the ones Tools/Script/tests/run.sh and run-execution.sh run on
 * macOS) inside the isolated process on this device. The instrumentation process owns the case
 * assets; the isolated process has no storage access, so each case file travels over Binder as a
 * memfd and the CLI reads it through a /proc/self/fd path. The canonical JSON the CLI prints is
 * compared byte for byte with expected.json.
 *
 * The CLI's own argument and limit injection errors (exit 64 checks in run.sh) stay on the macOS
 * harness: they exercise command-line handling, not the engine contract.
 */
@RunWith(AndroidJUnit4::class)
class ScriptSharedCasesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.context

    private fun readAsset(path: String): ByteArray =
        instrumentation.context.assets
            .open(path)
            .use { it.readBytes() }

    private fun example(name: String): ByteArray = readAsset("script/examples/$name.js")

    private fun memfdOf(
        bytes: ByteArray,
        name: String,
    ): ParcelFileDescriptor {
        val fd = Os.memfd_create(name, 0)
        Os.write(fd, bytes, 0, bytes.size)
        Os.lseek(fd, 0, OsConstants.SEEK_SET)
        val pfd = ParcelFileDescriptor.dup(fd)
        Os.close(fd)
        return pfd
    }

    private class CaseOutcome(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    private fun runInIsolatedProcess(
        arguments: List<String>,
        files: List<ParcelFileDescriptor>,
    ): CaseOutcome {
        val latch = CountDownLatch(1)
        var outcome = CaseOutcome(-1, "", "")
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName,
                    service: IBinder,
                ) {
                    val remote = ScriptIsolatedProcess.Stub.asInterface(service)
                    remote.run(
                        arguments,
                        files,
                        object : ScriptIsolatedCallback.Stub() {
                            override fun finished(
                                exitCode: Int,
                                stdout: String,
                                stderr: String,
                            ) {
                                outcome = CaseOutcome(exitCode, stdout, stderr)
                                latch.countDown()
                            }
                        },
                    )
                }

                override fun onServiceDisconnected(name: ComponentName) = Unit
            }
        val intent = Intent().setComponent(ComponentName(context, "app.songyan.ime.script.ScriptIsolatedService"))
        check(context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) { "cannot bind the isolated service" }
        try {
            check(latch.await(120, TimeUnit.SECONDS)) { "the isolated service did not answer" }
        } finally {
            context.unbindService(connection)
        }
        return outcome
    }

    /** A contract case: header --script <script> --capabilities <caps>, golden JSON diff. */
    private fun runContractCase(case: String) {
        val capabilities =
            runCatching {
                readAsset("script-cases/$case/capabilities.txt").toString(StandardCharsets.UTF_8).trim()
            }.getOrDefault("text")
        val script = runCatching { readAsset("script-cases/$case/script.js") }.getOrElse { example(case) }
        val expected = readAsset("script-cases/$case/expected.json").toString(StandardCharsets.UTF_8)
        val scriptFd = memfdOf(script, "$case-script.js")
        val outcome =
            try {
                runInIsolatedProcess(
                    listOf("header", "--script", "@fd0", "--capabilities", capabilities),
                    listOf(scriptFd),
                )
            } finally {
                scriptFd.close()
            }
        assertEquals("$case exit", ScriptCaseRun.exitCodeFor(expected), outcome.exitCode)
        assertEquals("$case stdout", expected, outcome.stdout)
    }

    @Test
    fun probeIsolatedProcessCapabilities() {
        val outcome = runInIsolatedProcess(listOf("probe"), emptyList())
        assertEquals(0, outcome.exitCode)
        // The capability matrix travels as the test's assertion message so it shows in the report.
        // The isolated process may create memfds and read its own APK, but SELinux denies
        // reopening a tmpfs file through /proc/self/fd; that is why the CLI path goes through the
        // library's fopen interposer instead. A policy change flips these flags and this test.
        val report = outcome.stdout
        assertEquals("memfdCreate=ok", true, report.contains("memfdCreate=ok"))
        assertEquals("ownMemfdPathOpen=false", true, report.contains("ownMemfdPathOpen=false"))
        assertEquals("apkOpen=true", true, report.contains("apkOpen=true"))
    }

    @Test
    fun contractCasesMatchGoldenJson() {
        val cases =
            context.assets
                .list("script-cases")!!
                .filter { it != "cases" }
                .sorted()
        assertEquals("the sync set has 30 contract cases", 30, cases.size)
        cases.forEach { case -> runContractCase(case) }
    }

    /** An execution case: build the full run command from the case directory (the same rules as
     *  run-execution.sh) and diff the golden JSON. */
    private fun runExecutionCase(case: String) {
        val base = "script-execution/cases/"

        fun file(name: String): ByteArray? = runCatching { readAsset(base + case + "/" + name) }.getOrNull()
        val golden = file("expected.json")!!.toString(StandardCharsets.UTF_8)
        val files = mutableListOf<ParcelFileDescriptor>()
        var next = 0

        fun put(
            bytes: ByteArray,
            label: String,
        ): String {
            files.add(memfdOf(bytes, case + "-" + label))
            val token = "@fd" + next
            next += 1
            return token
        }
        val arguments = mutableListOf("run")
        val scriptList = file("scripts.txt")?.toString(StandardCharsets.UTF_8)
        if (scriptList != null) {
            scriptList.lineSequence().filter { it.isNotBlank() }.forEach { relative ->
                arguments += listOf("--script", put(file(relative.trimEnd()) ?: error("missing $relative"), "script"))
            }
        } else {
            val script = file("script.js") ?: example(case)
            arguments += listOf("--script", put(script, "script.js"))
        }
        file("input.txt")?.let { arguments += listOf("--text-file", put(it, "input.txt")) }
        file("settings.json")?.let { arguments += listOf("--settings-file", put(it, "settings.json")) }
        file("fetch.txt")?.let { arguments += listOf("--fetch", put(it, "fetch.txt")) }
        file("logLimit.txt")?.let { arguments += listOf("--log-limit", it.toString(StandardCharsets.UTF_8).trim()) }
        // cancel.txt is the --cancel-pending flag; its content is not read.
        if (file("cancel.txt") != null) arguments += "--cancel-pending"
        val capabilities =
            file("capabilities.txt")?.toString(StandardCharsets.UTF_8)?.trim() ?: "text"
        arguments += listOf("--capabilities", capabilities)
        val overrides = mutableMapOf<String, String>()
        file("limits.txt")?.toString(StandardCharsets.UTF_8)?.lineSequence()?.forEach { line ->
            val key = line.substringBefore('=').trim()
            if (key.isNotEmpty() && !key.startsWith("#")) overrides[key] = line.substringAfter('=').trim()
        }
        arguments +=
            listOf(
                "--source-bytes",
                (overrides["sourceBytes"] ?: ScriptCaseRun.DEFAULT_SOURCE_BYTES.toString()),
                "--heap-bytes",
                (overrides["heapBytes"] ?: ScriptCaseRun.DEFAULT_HEAP_BYTES.toString()),
                "--duration-ms",
                (overrides["durationMs"] ?: ScriptCaseRun.DEFAULT_DURATION_MS.toString()),
                "--request-count",
                (overrides["requestCount"] ?: ScriptCaseRun.DEFAULT_REQUEST_COUNT.toString()),
                "--response-bytes",
                (overrides["responseBytes"] ?: ScriptCaseRun.DEFAULT_RESPONSE_BYTES.toString()),
                "--input-text-bytes",
                (overrides["inputTextBytes"] ?: ScriptCaseRun.DEFAULT_INPUT_TEXT_BYTES.toString()),
            )
        val outcome =
            try {
                runInIsolatedProcess(arguments, files)
            } finally {
                files.forEach { it.close() }
            }
        assertEquals(case + " exit", ScriptCaseRun.exitCodeFor(golden), outcome.exitCode)
        assertEquals(case + " stdout", golden, outcome.stdout)
        if (case == "unicode-passthrough") {
            // The golden carries a 4-byte UTF-8 emoji; the byte[] transport must decode it intact
            // (a modified-UTF-8 JNI string would corrupt it), which the byte-exact diff above
            // already proves - this assertion states it for the reader.
            assertTrue(outcome.stdout.contains("🙂"))
        }
    }

    @Test
    fun executionCasesMatchGoldenJson() {
        val cases = context.assets.list("script-execution/cases")!!.sorted()
        assertEquals("the sync set has 54 execution cases", 54, cases.size)
        cases.forEach { case -> runExecutionCase(case) }
    }
}
