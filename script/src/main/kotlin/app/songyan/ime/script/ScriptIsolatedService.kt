package app.songyan.ime.script

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/** Hosts the script engine in an isolated process (`android:isolatedProcess="true"`, no
 * permissions, no app storage access). The engine refuses to load anywhere else. Case files
 * arrive over Binder as descriptors; each placeholder @fdN in the command line becomes a
 * /proc/self/fd/<n> path in this process before the shared CLI runs.
 *
 * An isolated process may not bind services, so the caller passes its transport binder as an
 * argument and the C host callback fetches through it. Recorded audio for `@input audio` arrives
 * the same way the case files do: as an open descriptor the proxy received from the host. */
class ScriptIsolatedService : Service() {
    private val executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "songyan-script-case")
        }

    /** Runs get their own thread, so a header parse never waits behind a minutes-long run. */
    private val runExecutor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "songyan-script-run")
        }

    /** Set by [ScriptIsolatedProcess.cancelRun]; read on the run thread, which alone calls C. */
    @Volatile private var cancelRequested = false

    @Volatile private var waitingFetch: TransportLatch? = null

    override fun onBind(intent: Intent): IBinder = binder

    private val binder: ScriptIsolatedProcess.Stub =
        object : ScriptIsolatedProcess.Stub() {
            override fun runScript(
                source: ByteArray,
                settingsJson: ByteArray?,
                text: ByteArray?,
                audio: ParcelFileDescriptor?,
                declaredHosts: MutableList<String>,
                settingUrls: MutableList<String>,
                transport: ScriptTransport,
                callback: ScriptIsolatedCallback,
            ) {
                cancelRequested = false
                runExecutor.execute {
                    try {
                        /* The descriptor is this process's to close: read the recorded audio out
                         * once, before the engine starts, so a long run holds no caller resource. */
                        val audioBytes =
                            audio?.let { descriptor ->
                                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
                            }
                        var handle = 0L
                        val host =
                            ScriptRunHost(declaredHosts, settingUrls) { url, method, headers, body, timeoutMillis ->
                                val latch = TransportLatch()
                                waitingFetch = latch
                                if (!cancelRequested) {
                                    val bodyFd = body?.let(::bodyDescriptor)
                                    try {
                                        transport.fetch(url, method, headers, bodyFd, timeoutMillis, latch)
                                    } finally {
                                        bodyFd?.close()
                                    }
                                }
                                // A little past the run's own deadline, so the engine reports it.
                                val answered = cancelRequested || latch.await(timeoutMillis + FETCH_SLACK_MS)
                                waitingFetch = null
                                if (cancelRequested) {
                                    // This is the run thread, the only one that touches the engine.
                                    ScriptNative.cancelRun(handle)
                                    ScriptRunHost.FetchReply(accepted = false, error = "cancelled")
                                } else if (!answered) {
                                    ScriptRunHost.FetchReply(accepted = false, error = "the transport did not answer")
                                } else {
                                    val reply = latch.reply
                                    ScriptRunHost.FetchReply(
                                        reply.accepted,
                                        reply.status,
                                        reply.body,
                                        reply.contentType,
                                        reply.finalUrl,
                                        reply.headersJson,
                                        reply.error,
                                    )
                                }
                            }
                        ScriptRunHost.install(host)
                        handle =
                            ScriptNative.createRun(
                                source,
                                settingsJson,
                                text,
                                audioBytes,
                                ScriptLimits.SOURCE_BYTES.toInt(),
                                ScriptLimits.HEAP_BYTES.toInt(),
                                ScriptLimits.DURATION_MS.toInt(),
                                ScriptLimits.COMPUTE_MS.toInt(),
                                ScriptLimits.REQUEST_COUNT.toInt(),
                                ScriptLimits.RESPONSE_BYTES.toInt(),
                                ScriptLimits.INPUT_TEXT_BYTES.toInt(),
                            )
                        if (handle == 0L) {
                            callback.finished(-1, "", "the run was refused")
                            return@execute
                        }
                        /* 0 RUNNING, 1 PENDING, 2 DONE, 3 FAILED, 4 CANCELLED, 5 INVALID. The fetch
                         * transport is synchronous inside stepRun, so PENDING never surfaces here;
                         * stepping until a terminal state is all the loop does. */
                        var step: Int
                        while (true) {
                            if (cancelRequested) ScriptNative.cancelRun(handle)
                            step = ScriptNative.stepRun(handle)
                            if (step >= 2) break
                        }
                        val report = ScriptNative.takeResultJson(handle, step)
                        ScriptNative.destroyRun(handle)
                        ScriptRunHost.clear()
                        val exit =
                            if (step == 2) {
                                0
                            } else if (step == 4) {
                                125
                            } else {
                                1
                            }
                        callback.finished(
                            exit,
                            String(report ?: ByteArray(0), Charsets.UTF_8),
                            "",
                        )
                    } catch (error: Throwable) {
                        ScriptRunHost.clear()
                        callback.finished(-1, "", error.message ?: error.javaClass.simpleName)
                    }
                }
            }

            override fun parseHeader(
                source: ByteArray,
                callback: ScriptIsolatedCallback,
            ) {
                executor.execute {
                    try {
                        val report = ScriptNative.parseHeader(source)
                        if (report == null) {
                            callback.finished(-1, "", "the header parse ran out of memory")
                        } else {
                            callback.finished(0, String(report, StandardCharsets.UTF_8), "")
                        }
                    } catch (error: Throwable) {
                        callback.finished(-1, "", error.toString())
                    }
                }
            }

            override fun cancelRun() {
                cancelRequested = true
                waitingFetch?.cancel()
            }

            override fun run(
                arguments: MutableList<String>,
                files: MutableList<ParcelFileDescriptor>,
                callback: ScriptIsolatedCallback,
            ) {
                executor.execute {
                    if (arguments.firstOrNull() == "probe") {
                        val report = StringBuilder()
                        try {
                            val fd = Os.memfd_create("probe", 0)
                            Os.write(fd, "x".toByteArray(), 0, 1)
                            report.append("memfdCreate=ok")
                            val pathOpen: Result<Int> =
                                runCatching {
                                    java.io
                                        .File("/proc/self/fd/" + fd)
                                        .inputStream()
                                        .use { it.read() }
                                }
                            report.append(" ownMemfdPathOpen=").append(pathOpen.isSuccess)
                            Os.close(fd)
                        } catch (error: Throwable) {
                            report.append("memfdCreate=fail:").append(error.message)
                        }
                        val apkOpen: Result<Int> =
                            runCatching {
                                java.io
                                    .File(this@ScriptIsolatedService.packageCodePath)
                                    .inputStream()
                                    .use { it.read() }
                            }
                        report.append(" apkOpen=").append(apkOpen.isSuccess)
                        callback.finished(0, report.toString(), "")
                        return@execute
                    }
                    // Materialize each descriptor as a stable fd in this process and substitute the
                    // placeholders. Closing happens after the CLI returned.
                    val owned = mutableListOf<ParcelFileDescriptor>()
                    try {
                        val resolved =
                            arguments.map { argument ->
                                val match = Regex("^@fd(\\d+)$").find(argument) ?: return@map argument
                                val index = match.groupValues[1].toInt()
                                val incoming = files[index]
                                val bytes = FileInputStream(incoming.fileDescriptor).use { it.readBytes() }
                                val fd = Os.memfd_create("script-case-" + index, 0)
                                Os.write(fd, bytes, 0, bytes.size)
                                Os.lseek(
                                    fd,
                                    0,
                                    OsConstants.SEEK_SET,
                                )
                                val pfd = ParcelFileDescriptor.dup(fd)
                                Os.close(fd)
                                owned.add(pfd)
                                "/proc/self/fd/" + pfd.fd
                            }
                        val result = ScriptNative.runCli(resolved.toTypedArray())

                        fun bytes(at: Int): ByteArray = result[at] as ByteArray
                        callback.finished(
                            (result[0] as Number).toInt(),
                            String(bytes(1), StandardCharsets.UTF_8),
                            String(bytes(2), StandardCharsets.UTF_8),
                        )
                    } catch (error: Throwable) {
                        callback.finished(-1, "", error.message ?: error.javaClass.simpleName)
                    } finally {
                        owned.forEach { it.close() }
                    }
                }
            }
        }

    /** An anonymous in-memory file holding a request body; the receiver reads it from the start. */
    private fun bodyDescriptor(bytes: ByteArray): ParcelFileDescriptor {
        val fd = Os.memfd_create("script-body", 0)
        try {
            var offset = 0
            while (offset < bytes.size) offset += Os.write(fd, bytes, offset, bytes.size - offset)
            Os.lseek(fd, 0, OsConstants.SEEK_SET)
            return ParcelFileDescriptor.dup(fd)
        } finally {
            Os.close(fd)
        }
    }

    private companion object {
        const val FETCH_SLACK_MS = 2_000L
    }

    override fun onDestroy() {
        executor.shutdownNow()
        runExecutor.shutdownNow()
        super.onDestroy()
    }
}
