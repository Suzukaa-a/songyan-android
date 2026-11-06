package app.songyan.ime.deploy

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.storage.AppStorage
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Fixture, snapshot and disk-measurement plumbing for [KagiroiInstallDeviceTest].
 *
 * The package under test is a ZIP the test machine prepared from a local, nico-free compile of the
 * pinned kagiroi source; it is pushed into this app's cache. Everything here only reads that
 * package, copies the app's own data aside and measures it: no production path is replaced, and
 * nothing is written into the app's storage except by the real `:deploy` request the test sends.
 */
internal class KagiroiInstallFixture(
    private val context: Context,
    private val storage: AppStorage,
) {
    /** The kagiroi package staged for one import request, with what its ZIP carries. */
    class Staged(
        val name: String,
        val facts: Facts,
    )

    /**
     * What the fixture ZIP carries, read without extracting it. [prebuiltFiles] are the entries
     * under a top-level `build/` directory: that is how a locally compiled deployment hands its
     * tables to the real source install.
     */
    class Facts(
        val entries: Int,
        val expandedBytes: Long,
        val compressedBytes: Long,
        val defaultConfig: Boolean,
        val schemaFiles: List<String>,
        val dictionaryFiles: List<String>,
        val prebuiltFiles: List<Pair<String, Long>>,
        val largestEntry: String,
    ) {
        val prebuiltBytes: Long get() = prebuiltFiles.sumOf { it.second }

        fun oneLine(): String =
            "entries=" + entries +
                " expandedBytes=" + expandedBytes +
                " compressedBytes=" + compressedBytes +
                " defaultYaml=" + defaultConfig +
                " schemas=" + schemaFiles +
                " dictionaries=" + dictionaryFiles +
                " prebuiltFiles=" + prebuiltFiles.size +
                " prebuiltBytes=" + prebuiltBytes +
                " largest=" + largestEntry
    }

    /** What one generation of the app needs to run: its resources and its compiled build. */
    class DiskReport(
        val files: Int,
        val logicalBytes: Long,
        val uniqueBytes: Long,
        val uniqueInodes: Int,
        val productBytes: Long,
        val buildBytes: Long,
        val sourceTreeBytes: Long,
        val generations: Int,
    ) {
        /** Bytes several paths point at through one device/inode: sharing, not deletion. */
        val sharedBytes: Long get() = logicalBytes - uniqueBytes

        /** Against one whole deployment product, which is what a real install keeps per generation. */
        fun productRatio(): Double = uniqueBytes.toDouble() / productBytes

        /** Against the compiled build alone, the denominator J0.2 used for its publication probe. */
        fun buildRatio(): Double = uniqueBytes.toDouble() / buildBytes

        fun oneLine(): String =
            "files=" + files +
                " logicalBytes=" + logicalBytes +
                " uniqueBytes=" + uniqueBytes +
                " sharedBytes=" + sharedBytes +
                " uniqueInodes=" + uniqueInodes +
                " productBytes=" + productBytes +
                " buildBytes=" + buildBytes +
                " sourceTreeBytes=" + sourceTreeBytes +
                " generations=" + generations +
                " productRatio=" + productRatio() +
                " buildRatio=" + buildRatio()
    }

    /** The IME, keyboard schema and app language choices as the test found them. */
    class DeviceState(
        val active: String?,
        val keyboardSchema: String?,
        val defaultIme: String?,
        val enabledImes: String?,
        val locales: String,
    )

    fun argument(name: String): String? = InstrumentationRegistry.getArguments().getString(name)

    /** Scratch root of this test inside the app cache; the report and the data snapshot live here. */
    fun cacheRoot(): File = File(context.cacheDir, ROOT).also { it.mkdirs() }

    /**
     * The package the caller pushed into this app's cache: a ZIP or a directory. It must be inside
     * the cache so the test proves it installs from a locally prepared artifact rather than from
     * data made up somewhere else.
     */
    fun requireFixture(): File {
        val raw =
            argument(ARG_FIXTURE)
                ?: throw AssertionError(
                    "no local prebuilt kagiroi package was handed to this test; pass -e " + ARG_FIXTURE +
                        " <zip-or-directory under " + context.cacheDir + ">",
                )
        val file = File(raw)
        if (!file.exists()) {
            throw AssertionError(
                "the kagiroi fixture does not exist: " + raw +
                    "; push the locally compiled package into " + context.cacheDir + " first",
            )
        }
        val cache = canonical(context.cacheDir)
        val resolved = canonical(file)
        if (!resolved.path.startsWith(cache.path + File.separator)) {
            throw AssertionError(
                "the kagiroi fixture must live inside this app's cache (" + cache.path +
                    ") so the real import reads it: " + resolved.path,
            )
        }
        if (file.isDirectory) return file
        if (!file.isFile || !isZip(file)) {
            throw AssertionError("the kagiroi fixture is neither a ZIP archive nor a directory: " + file)
        }
        return file
    }

    /** Stages the fixture as the ZIP one import request reads, under the request's incoming directory. */
    fun stage(incoming: File): Staged {
        val source = requireFixture()
        if (!incoming.mkdirs() && !incoming.isDirectory) throw AssertionError("cannot create " + incoming)
        val archive = File(incoming, STAGED_NAME)
        if (source.isDirectory) {
            zipTree(source, archive)
        } else {
            try {
                source.inputStream().use { input ->
                    FileOutputStream(archive).use { output -> input.copyTo(output, BUFFER_BYTES) }
                }
            } catch (error: IOException) {
                archive.delete()
                throw error
            }
        }
        return try {
            Staged(STAGED_NAME, inspect(archive))
        } catch (error: Throwable) {
            archive.delete()
            throw error
        }
    }

    /**
     * Counts the app's own data by device and inode: two paths that are hard links report one
     * device/inode, so their bytes are counted once. This measures file sharing only; it is not a
     * claim about copy-on-write block cloning, which Android does not expose here.
     */
    fun measure(generation: File): DiskReport {
        val files =
            storage.rimeRoot
                .walkTopDown()
                .filter { it.isFile }
                .toList()
        var logical = 0L
        var unique = 0L
        var uniqueInodes = 0
        val seen = HashSet<Pair<Long, Long>>()
        for (file in files) {
            val length = file.length()
            logical += length
            val stat =
                try {
                    Os.stat(file.path)
                } catch (error: Exception) {
                    throw AssertionError("cannot stat " + file + " for the inode measurement: " + error.message)
                }
            if (seen.add(stat.st_dev to stat.st_ino)) {
                unique += length
                uniqueInodes++
            }
        }
        val build = File(generation, BUILD)
        return DiskReport(
            files = files.size,
            logicalBytes = logical,
            uniqueBytes = unique,
            uniqueInodes = uniqueInodes,
            productBytes = sumLengths(generation),
            buildBytes = sumLengths(build),
            sourceTreeBytes = if (storage.sourcesDir.isDirectory) sumLengths(storage.sourcesDir) else 0L,
            generations =
                storage.versionsDir
                    .listFiles()
                    .orEmpty()
                    .count { it.isDirectory && !it.name.startsWith(".") },
        )
    }

    /** A `:deploy` exit since [startedAtElapsedMs], or null when there is no matching exit record. */
    fun deployExitedSince(startedAtElapsedMs: Long): String? {
        // Exit timestamps use wall time; request timings use the monotonic clock.
        val startedAtMs = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - startedAtElapsedMs)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val exit =
            runCatching { manager.getHistoricalProcessExitReasons(context.packageName, 0, EXIT_RECORDS) }
                .getOrNull()
                ?.firstOrNull { it.timestamp >= startedAtMs && (it.processName ?: "").endsWith(":deploy") }
                ?: return null
        return ":deploy exited before reporting a status: reason=" + exit.reason +
            " status=" + exit.status +
            " rssKib=" + exit.rss +
            " pssKib=" + exit.pss +
            " (system exit statistics in KiB, not the process's own peak)"
    }

    /** The IME, keyboard and language state the test must leave exactly as it found it. */
    fun deviceState(): DeviceState =
        DeviceState(
            active = storage.activeFile.takeIf { it.isFile }?.readText(),
            keyboardSchema = storage.keyboardSchemaFile.takeIf { it.isFile }?.readText(),
            defaultIme = secure(Settings.Secure.DEFAULT_INPUT_METHOD),
            enabledImes = secure(Settings.Secure.ENABLED_INPUT_METHODS),
            locales =
                context.resources.configuration.locales
                    .toLanguageTags(),
        )

    /** Writes the run's measurements where the test machine can pull them back. */
    fun writeReport(report: JSONObject): File {
        val file = File(cacheRoot(), REPORT_FILE)
        file.writeText(report.toString(2), Charsets.UTF_8)
        return file
    }

    private fun secure(name: String): String? =
        runCatching { Settings.Secure.getString(context.contentResolver, name) }.getOrNull()

    private fun inspect(archive: File): Facts {
        ZipFile(archive).use { zip ->
            val entries =
                zip
                    .entries()
                    .asSequence()
                    .filter { !it.isDirectory }
                    .toList()
            if (entries.isEmpty()) throw AssertionError("the kagiroi fixture carries no files: " + archive)
            val prefix = wrapperPrefix(entries.map { it.name })
            val pairs = entries.map { it.name.removePrefix(prefix).removePrefix("/") to it.size.coerceAtLeast(0L) }
            val root = pairs.filter { !it.first.contains('/') }
            return Facts(
                entries = entries.size,
                expandedBytes = pairs.sumOf { it.second },
                compressedBytes = archive.length(),
                defaultConfig = root.any { it.first == DEFAULT_CONFIG },
                schemaFiles = root.map { it.first }.filter { it.endsWith(SCHEMA_SUFFIX) }.sorted(),
                dictionaryFiles = root.map { it.first }.filter { it.endsWith(DICTIONARY_SUFFIX) }.sorted(),
                prebuiltFiles =
                    pairs
                        .filter { it.first.startsWith(BUILD + "/") }
                        .map { it.first.removePrefix(BUILD + "/") to it.second }
                        .filter { (name, _) -> name.isNotEmpty() }
                        .sortedBy { it.first },
                largestEntry = pairs.maxByOrNull { it.second }?.first ?: "-",
            )
        }
    }

    /** The single enclosing directory a GitHub-style ZIP wraps everything in, or an empty string. */
    private fun wrapperPrefix(names: List<String>): String {
        val roots = names.map { it.substringBefore('/') }.toSet()
        val root = roots.singleOrNull() ?: return ""
        return if (names.all { it.contains('/') }) root else ""
    }

    /** Writes [source]'s files as a ZIP whose names are the paths under it, in a stable order. */
    private fun zipTree(
        source: File,
        target: File,
    ) {
        val files =
            source
                .walkTopDown()
                .filter { it.isFile }
                .sortedBy { it.path }
                .toList()
        if (files.isEmpty()) throw AssertionError("the kagiroi fixture directory carries no files: " + source)
        BufferedOutputStream(FileOutputStream(target), BUFFER_BYTES).use { buffer ->
            ZipOutputStream(buffer).use { zip ->
                for (file in files) {
                    val name = file.relativeTo(source).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().use { input -> input.copyTo(zip, BUFFER_BYTES) }
                    zip.closeEntry()
                }
            }
        }
    }

    private fun isZip(file: File): Boolean =
        try {
            file.inputStream().use { input ->
                val magic = ByteArray(2)
                input.read(magic) == 2 && magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte()
            }
        } catch (_: IOException) {
            false
        }

    private fun canonical(file: File): File = runCatching { file.canonicalFile }.getOrDefault(file)

    private fun sumLengths(tree: File): Long =
        if (tree.isDirectory) tree.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L

    companion object {
        /** Instrumentation argument: the pushed ZIP or directory of the locally compiled package. */
        const val ARG_FIXTURE = "kagiroiFixture"

        /** Instrumentation argument: the input method to type with, when the primary one takes no romaji. */
        const val ARG_SCHEMA = "kagiroiSchema"

        /** Instrumentation argument: the input methods to advertise when the package ships no default.yaml. */
        const val ARG_SCHEMAS = "kagiroiSchemas"

        /** Opt-in gate; Root runs this test explicitly, so the default run measures nothing. */
        const val ARG_OPT_IN = "kagiroiInstall"

        const val ROOT = "j1-install-test"
        const val SNAPSHOT = "snapshot"
        const val REPORT_FILE = "report.json"

        /** Name of the ZIP inside the request's incoming directory; the deploy request names it. */
        private const val STAGED_NAME = "package.zip"
        private const val BUILD = "build"
        private const val DEFAULT_CONFIG = "default.yaml"
        private const val SCHEMA_SUFFIX = ".schema.yaml"
        private const val DICTIONARY_SUFFIX = ".dict.yaml"
        private const val BUFFER_BYTES = 1024 * 1024
        private const val EXIT_RECORDS = 10
    }
}

/**
 * The app's own `rime/` tree as it was before the test, put back afterwards.
 *
 * The install and the republish have to run through the real `:deploy` process, which always uses
 * the app's own storage: a test root beside it would no longer be the path under test. So the tree
 * is copied aside first and moved back after, which restores the stored sources, the active
 * generation, the feature choices and the learning data together, without clearing the app.
 */
internal class KagiroiInstallSnapshot(
    private val storage: AppStorage,
    private val root: File,
) {
    private val tree = File(root, TREE)
    private val after = File(root, AFTER)
    private var captured = false
    private var existed = false

    fun capture() {
        root.mkdirs()
        deleteGuarded(tree)
        deleteGuarded(after)
        val rime = storage.rimeRoot
        existed = rime.isDirectory
        withDeployLock {
            if (existed) copyTree(rime, tree)
        }
        captured = true
    }

    /** Puts the captured tree back; filesystem failures leave the test copy available. */
    fun restore(): String {
        if (!captured) return "no snapshot was taken"
        val restored = withDeployLock { restoreLocked() }
        deleteGuarded(after)
        return restored
    }

    private fun restoreLocked(): String {
        val rime = storage.rimeRoot
        if (rime.exists() && !rime.renameTo(after)) {
            throw AssertionError("cannot set the test's app data aside before restoring: " + rime)
        }
        if (!existed) return "removed the rime/ tree the test created; there was none before"
        if (!tree.renameTo(rime)) {
            copyTree(tree, rime)
            deleteGuarded(tree)
        }
        return "restored " + countFiles(rime) + " files of the app's own rime/ tree"
    }

    /**
     * Runs [block] holding the deploy lock the `:deploy` process takes, so no deployment can be
     * writing the tree while it is copied or swapped. A lock that is never released fails instead of
     * touching the data behind a running deployment.
     */
    private fun <T> withDeployLock(block: () -> T): T {
        storage.rimeRoot.mkdirs()
        FileChannel
            .open(
                storage.deployLockFile.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            ).use { channel ->
                val lock = lockOrFail(channel)
                try {
                    return block()
                } finally {
                    lock.release()
                }
            }
    }

    private fun lockOrFail(channel: FileChannel): FileLock {
        val deadline = SystemClock.elapsedRealtime() + LOCK_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val lock = runCatching { channel.tryLock() }.getOrNull()
            if (lock != null) return lock
            Thread.sleep(LOCK_POLL_MS)
        }
        throw AssertionError(
            "the deploy lock was held for " + LOCK_TIMEOUT_MS + " ms; refusing to touch the app data",
        )
    }

    private fun countFiles(directory: File): Int = directory.walkTopDown().count { it.isFile }

    private fun copyTree(
        source: File,
        target: File,
    ) {
        if (!target.mkdirs() && !target.isDirectory) throw AssertionError("cannot create " + target)
        for (child in source.listFiles().orEmpty()) {
            val destination = File(target, child.name)
            if (child.isDirectory) {
                copyTree(child, destination)
            } else {
                FileOutputStream(destination).use { output ->
                    child.inputStream().use { input -> input.copyTo(output, BUFFER_BYTES) }
                }
            }
        }
    }

    /** Removes a directory this test owns; refuses any path outside its own scratch root. */
    private fun deleteGuarded(target: File) {
        if (!target.exists()) return
        val scratch = runCatching { root.canonicalFile }.getOrDefault(root)
        val resolved = runCatching { target.canonicalFile }.getOrDefault(target)
        if (!resolved.path.startsWith(scratch.path + File.separator)) {
            throw AssertionError("refusing to delete outside the test's own scratch: " + resolved.path)
        }
        if (!target.deleteRecursively()) throw AssertionError("cannot remove " + target)
    }

    private companion object {
        const val TREE = "rime"
        const val AFTER = "after"
        const val BUFFER_BYTES = 1024 * 1024
        const val LOCK_TIMEOUT_MS = 10L * 60 * 1000
        const val LOCK_POLL_MS = 250L
    }
}
