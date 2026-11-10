package app.songyan.ime.deploy.probe

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.songyan.ime.R
import app.songyan.ime.rime.RimeBindings
import app.songyan.ime.rime.RimeDeployment
import app.songyan.ime.rime.RimeEngine
import app.songyan.ime.rime.RimeKey
import app.songyan.ime.storage.AppStorage
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** Keeps deployment measurements out of the IME and serialized with product deployments. */
class KagiroiDeployProbeService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val mode = intent?.getStringExtra(EXTRA_MODE)
        val fixture = intent?.getStringExtra(EXTRA_FIXTURE_ROOT)?.let(::File)
        // Android requires startForeground after every startForegroundService, even for a rejected one.
        val foregrounded = foreground(mode)
        if (!running.compareAndSet(false, true)) return START_NOT_STICKY
        worker.execute {
            val root = File(cacheDir, "kagiroi-probe/" + (mode ?: "invalid"))
            val report =
                if (!foregrounded || mode == null || fixture == null) {
                    failure(mode, if (foregrounded) "missing probe arguments" else "no foreground service")
                } else {
                    measure(mode, fixture, root)
                }
            write(root, report)
            finish(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    /** A probe that cannot stay in the foreground would be killed mid-deployment; fail, never pretend. */
    private fun foreground(mode: String?): Boolean =
        try {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW),
            )
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                NotificationCompat
                    .Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText(mode ?: "-")
                    .setOngoing(true)
                    .build(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                },
            )
            true
        } catch (error: Exception) {
            Log.w(TAG, "foreground start refused: " + (error.message ?: error.javaClass.simpleName))
            false
        }

    private fun finish(startId: Int) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        running.set(false)
        stopSelf(startId)
    }

    private fun measure(
        mode: String,
        fixture: File,
        root: File,
    ): JSONObject {
        val report =
            JSONObject()
                .put("mode", mode)
                .put("pid", Process.myPid())
                .put("processName", processName())
                .put("processAgeMs", SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
        val peaks = MemoryPeaks().also { it.start() }
        val startedAt = SystemClock.elapsedRealtime()
        try {
            deploy(mode, fixture, root, report)
            report.put("state", "done")
        } catch (error: Throwable) {
            report.put("state", "failed").put("error", error.message ?: error.javaClass.simpleName)
        } finally {
            peaks.stop()
            report.put("memory", peaks.json()).put("totalMs", SystemClock.elapsedRealtime() - startedAt)
        }
        return report
    }

    private fun deploy(
        mode: String,
        fixture: File,
        root: File,
        report: JSONObject,
    ) {
        val shared = File(root, "shared")
        val work = File(root, "work")
        val build = File(work, BUILD)
        shared.deleteRecursively()
        work.deleteRecursively()
        val storage = AppStorage(application.filesDir)
        storage.ensureBaseDirectories()
        FileChannel
            .open(storage.deployLockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            .use { channel ->
                val lock =
                    try {
                        channel.tryLock()
                            ?: throw IllegalStateException("deploy lock is held by another process")
                    } catch (_: OverlappingFileLockException) {
                        throw IllegalStateException("deploy lock is held by this process")
                    }
                lock.use {
                    val copyStart = SystemClock.elapsedRealtime()
                    var files = 0
                    var bytes = 0L
                    if (mode == MODE_SOURCE) {
                        val copied = copy(fixture, shared, emptySet())
                        files = copied.first
                        bytes = copied.second
                    } else {
                        val source = copy(fixture, shared, setOf(BUILD))
                        val bins = copy(File(fixture, BUILD), build, emptySet())
                        files = source.first + bins.first
                        bytes = source.second + bins.second
                    }
                    report.put("copyMs", SystemClock.elapsedRealtime() - copyStart)
                    report.put("copiedFiles", files).put("copiedBytes", bytes)
                    // A prebuilt run must not compile these; their size and time say whether it did.
                    val before = if (mode == MODE_SOURCE) emptyMap() else stamps(build)
                    val deployStart = SystemClock.elapsedRealtime()
                    val result =
                        runBlocking {
                            RimeDeployment.deploy(
                                sharedDataDir = shared,
                                userDataDir = work,
                                appName = RimeBindings.APP_NAME,
                                appVersion = APP_VERSION,
                            )
                        }
                    report.put("deployMs", SystemClock.elapsedRealtime() - deployStart)
                    report.put("deployed", result.success)
                    report.put("engineVersion", result.engineVersion)
                    report.put("expectedEngineVersion", RimeBindings.ENGINE_VERSION)
                    report.put("schemaIds", JSONArray(result.schemaIds))
                    report.put("deployError", result.lastError ?: "")
                    if (before.isNotEmpty()) {
                        val after = stamps(build)
                        report.put(
                            "prebuiltBins",
                            JSONArray(
                                before.entries.sortedBy { it.key }.map { (name, old) ->
                                    JSONObject()
                                        .put("name", name)
                                        .put("sizeBefore", old.first)
                                        .put("mtimeBefore", old.second)
                                        .put("sizeAfter", after[name]?.first ?: -1L)
                                        .put("mtimeAfter", after[name]?.second ?: -1L)
                                },
                            ),
                        )
                    }
                    report.put("sharedBytes", sizeOf(shared))
                    report.put("compiledBytes", sizeOf(build))
                    report.put("workBytes", sizeOf(work))
                    if (result.success) {
                        report.put("candidates", candidates(shared, File(root, "engine-user"), result.compiledDir))
                    }
                }
            }
    }

    private fun candidates(
        shared: File,
        engineUser: File,
        compiledDir: File,
    ): JSONObject =
        JSONObject().apply {
            val openStart = SystemClock.elapsedRealtime()
            runBlocking {
                val engine =
                    RimeEngine.createPrebuilt(
                        sharedDataDir = shared,
                        userDataDir = engineUser,
                        appName = RimeBindings.APP_NAME,
                        appVersion = APP_VERSION,
                        compiledDir = compiledDir,
                    )
                try {
                    put("openMs", SystemClock.elapsedRealtime() - openStart)
                    put("engineVersion", engine.version())
                    val selected = engine.selectSchema(SCHEMA_ID)
                    put("schemaSelected", selected)
                    if (selected) {
                        val typingStart = SystemClock.elapsedRealtime()
                        for (char in INPUT) engine.processKey(RimeKey(requireNotNull(RimeKey.baseKeycode(char))))
                        put("typingMs", SystemClock.elapsedRealtime() - typingStart)
                        val snapshot = engine.readSnapshot()
                        put("preedit", snapshot.preedit)
                        put("top", JSONArray(snapshot.candidates.take(CANDIDATE_COUNT).map { it.text }))
                    }
                } finally {
                    engine.close()
                }
            }
        }

    private fun copy(
        source: File,
        target: File,
        skipTop: Set<String>,
    ): Pair<Int, Long> {
        if (!source.isDirectory) throw IllegalStateException("fixture directory missing: " + source.absolutePath)
        var files = 0
        var bytes = 0L
        val pending = ArrayDeque<Pair<File, File>>()
        pending.addLast(source to target)
        while (pending.isNotEmpty()) {
            val (from, to) = pending.removeFirst()
            to.mkdirs()
            for (child in from.listFiles().orEmpty().sortedBy { it.name }) {
                if (from == source && child.name in skipTop) continue
                if (child.isDirectory) {
                    pending.addLast(child to File(to, child.name))
                } else {
                    child.copyTo(File(to, child.name), overwrite = true)
                    files++
                    bytes += child.length()
                }
            }
        }
        return files to bytes
    }

    private fun stamps(directory: File): Map<String, Pair<Long, Long>> =
        directory
            .listFiles()
            .orEmpty()
            .filter { it.isFile }
            .associate { it.name to (it.length() to it.lastModified()) }

    private fun sizeOf(directory: File): Long = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private class MemoryPeaks {
        private val active = AtomicBoolean(false)
        private val vmHwm = AtomicLong(0L)
        private val pss = AtomicLong(0L)
        private var sampler: Thread? = null

        fun start() {
            if (!active.compareAndSet(false, true)) return
            sampler =
                thread(name = "kagiroi-probe-memory", isDaemon = true) {
                    while (active.get()) {
                        procValue("/proc/self/status", "VmHWM:")?.let { if (it > vmHwm.get()) vmHwm.set(it) }
                        procValue("/proc/self/smaps_rollup", "Pss:")?.let { if (it > pss.get()) pss.set(it) }
                        try {
                            Thread.sleep(SAMPLE_MS)
                        } catch (_: InterruptedException) {
                            return@thread
                        }
                    }
                }
        }

        fun stop() {
            active.set(false)
            sampler?.interrupt()
        }

        fun json(): JSONObject = JSONObject().put("peakVmHwmBytes", vmHwm.get()).put("peakPssBytes", pss.get())

        private fun procValue(
            path: String,
            key: String,
        ): Long? =
            try {
                File(path).useLines { lines ->
                    lines.firstNotNullOfOrNull { line ->
                        line
                            .takeIf { it.startsWith(key) }
                            ?.removePrefix(key)
                            ?.trim()
                            ?.substringBefore(' ')
                            ?.toLongOrNull()
                            ?.times(1024L)
                    }
                }
            } catch (_: Exception) {
                null
            }
    }

    private fun processName(): String =
        try {
            String(File("/proc/self/cmdline").readBytes(), Charsets.UTF_8).substringBefore('\u0000')
        } catch (_: Exception) {
            packageName
        }

    private fun failure(
        mode: String?,
        error: String,
    ): JSONObject = JSONObject().put("state", "failed").put("mode", mode ?: "").put("error", error)

    private fun write(
        root: File,
        report: JSONObject,
    ) {
        try {
            root.mkdirs()
            val temp = File(root, REPORT_FILE + ".tmp")
            temp.writeText(report.toString())
            val target = File(root, REPORT_FILE)
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
            Log.i(TAG, "report " + target.absolutePath + ": " + report)
        } catch (error: Exception) {
            Log.e(TAG, "cannot write the probe report", error)
        }
    }

    companion object {
        const val MODE_SOURCE = "A"
        const val MODE_PREBUILT = "B"
        const val EXTRA_FIXTURE_ROOT = "fixtureRoot"
        const val EXTRA_MODE = "mode"

        const val REPORT_FILE = "report.json"

        private const val TAG = "KagiroiProbe"
        private const val CHANNEL_ID = "kagiroi-probe"
        private const val NOTIFICATION_ID = 0x4A30
        private const val BUILD = "build"
        private const val SCHEMA_ID = "kagiroi"
        private const val INPUT = "nihongo"
        private const val CANDIDATE_COUNT = 10
        private const val APP_VERSION = "deployment-probe"
        private const val SAMPLE_MS = 100L
    }
}
