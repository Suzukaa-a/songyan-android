package app.songyan.ime.learning

import app.songyan.ime.rime.RimeLearningException
import app.songyan.ime.rime.RimeSnapshotFile
import app.songyan.ime.rime.RimeUserDbText
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/** A file the user picked, or a file found in a picked folder. */
class PickedFile(
    /** Shown to the user as it is. */
    val name: String,
    val open: () -> InputStream,
)

/** A picked folder or one of its entries, as the system's document tree or a plain directory shows it. */
interface PickedNode {
    val name: String
    val isDirectory: Boolean

    fun children(): List<PickedNode>

    fun open(): InputStream
}

/** Why a picked file is not merged. */
enum class SnapshotSkipReason {
    /** Not a Rime user dictionary snapshot, or damaged. */
    NOT_A_SNAPSHOT,

    /** Larger than [RimeUserDbText.MAXIMUM_BYTES]. */
    TOO_LARGE,

    /** A snapshot without a single entry. */
    EMPTY,

    /** No input method of the input scheme in use learns into its dictionary. */
    NOT_INSTALLED,
}

/** A snapshot copied out of the user's file, waiting to be merged. */
class StagedSnapshot(
    /** The picked file (or ZIP entry) it came from. */
    val fileName: String,
    val snapshot: RimeSnapshotFile,
)

class SkippedSnapshot(
    val fileName: String,
    val reason: SnapshotSkipReason,
)

class CollectedSnapshots(
    val staged: List<StagedSnapshot>,
    val skipped: List<SkippedSnapshot>,
)

/**
 * Finds librime `.userdb.txt` snapshots in what the user picked (a single file, a ZIP, or a
 * folder such as another front-end's sync folder) and copies each into a private staging
 * directory, where [RimeUserDbText] reads its header and librime later reads the copy. The
 * user's files are only read.
 *
 * A picked single file is recognized by its content, whatever its name; inside a ZIP or a folder
 * only files named `*.userdb.txt` count, because sync folders also hold installation and
 * configuration files.
 */
object RimeSnapshotCollector {
    const val MAXIMUM_FILES = 64
    const val MAXIMUM_TOTAL_BYTES: Long = 128L * 1024 * 1024

    /** Entries a ZIP or folder walk looks at; a sync folder holds a few dozen. */
    const val MAXIMUM_ENTRIES = 4096

    /** A sync folder is `sync/<installation>/<name>.userdb.txt`; one level more for the folder above it. */
    const val MAXIMUM_DEPTH = 3

    private const val SNAPSHOT_SUFFIX = ".userdb.txt"
    private const val HEAD_BYTES = 64
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    /** Thrown when the pick holds more snapshots or bytes than one import takes; nothing is staged. */
    class TooLarge : Exception("too many or too large snapshots")

    /** The files in a picked folder that may be snapshots, by name, at most [MAXIMUM_DEPTH] levels down. */
    fun filesIn(root: PickedNode): List<PickedFile> {
        val found = ArrayList<PickedFile>()
        var visited = 0

        fun walk(
            node: PickedNode,
            depth: Int,
        ) {
            for (child in node.children().sortedBy { it.name }) {
                if (++visited > MAXIMUM_ENTRIES) throw TooLarge()
                if (child.name.startsWith(".")) continue
                if (child.isDirectory) {
                    if (depth < MAXIMUM_DEPTH) walk(child, depth + 1)
                } else if (isSnapshotName(child.name)) {
                    found += PickedFile(child.name) { child.open() }
                    if (found.size > MAXIMUM_FILES) throw TooLarge()
                }
            }
        }
        walk(root, 1)
        return found
    }

    /**
     * Stages every snapshot in [files] into [staging], which is emptied first. [byContent] says the
     * files were picked one by one (a ZIP or a snapshot of any name); folder entries come from
     * [filesIn] and are read only as snapshots.
     */
    fun collect(
        files: List<PickedFile>,
        staging: File,
        byContent: Boolean,
    ): CollectedSnapshots {
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw IOException("cannot create the staging directory")
        val run = Run(staging)
        for (file in files) {
            BufferedInputStream(file.open()).use { input ->
                val head = peek(input)
                when {
                    byContent && head.startsWith(ZIP_MAGIC) -> run.addZip(file.name, input)
                    else -> run.add(file.name, input, head)
                }
            }
        }
        return CollectedSnapshots(run.staged, run.skipped)
    }

    /**
     * Stages the snapshots inside a scheme package ZIP into [staging], with the folder rules: hidden
     * entries and `__MACOSX` are skipped and the depth is bounded (one level more, for the folder
     * a repository download wraps its files in). Nothing here fails an import: an unreadable
     * archive, or one with too many or too large snapshots, stages nothing.
     */
    fun collectFromPackage(
        archive: File,
        staging: File,
    ): CollectedSnapshots {
        staging.deleteRecursively()
        if (!staging.mkdirs()) return CollectedSnapshots(emptyList(), emptyList())
        val run = Run(staging)
        try {
            archive.inputStream().buffered().use { run.addPackage(it) }
        } catch (_: TooLarge) {
            staging.deleteRecursively()
            return CollectedSnapshots(emptyList(), emptyList())
        } catch (_: IOException) {
            staging.deleteRecursively()
            return CollectedSnapshots(emptyList(), emptyList())
        }
        return CollectedSnapshots(run.staged, run.skipped)
    }

    internal fun isSnapshotName(name: String): Boolean {
        val base = name.substringAfterLast('/')
        return !base.startsWith(".") && base.lowercase(Locale.ROOT).endsWith(SNAPSHOT_SUFFIX)
    }

    private class Run(
        private val staging: File,
    ) {
        val staged = ArrayList<StagedSnapshot>()
        val skipped = ArrayList<SkippedSnapshot>()
        private var total = 0L

        fun addZip(
            zipName: String,
            input: InputStream,
        ) {
            val zip = ZipInputStream(input)
            var entries = 0
            val before = staged.size + skipped.size
            try {
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++entries > MAXIMUM_ENTRIES) throw TooLarge()
                    val name = entry.name
                    if (entry.isDirectory || name.split('/').any { it == "__MACOSX" } || !isSnapshotName(name)) continue
                    val buffered = BufferedInputStream(NonClosing(zip))
                    add(name.substringAfterLast('/'), buffered, peek(buffered))
                }
            } catch (_: IOException) {
                // A damaged or truncated archive; ZipException and EOFException both land here.
                skipped += SkippedSnapshot(zipName, SnapshotSkipReason.NOT_A_SNAPSHOT)
                return
            }
            if (staged.size + skipped.size ==
                before
            ) {
                skipped += SkippedSnapshot(zipName, SnapshotSkipReason.NOT_A_SNAPSHOT)
            }
        }

        fun addPackage(input: InputStream) {
            val zip = ZipInputStream(input)
            var entries = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > MAXIMUM_ENTRIES) throw TooLarge()
                val parts = entry.name.split('/')
                if (entry.isDirectory ||
                    parts.size > MAXIMUM_DEPTH + 1 ||
                    parts.any { it.startsWith(".") || it == "__MACOSX" } ||
                    !isSnapshotName(entry.name)
                ) {
                    continue
                }
                val buffered = BufferedInputStream(NonClosing(zip))
                add(parts.last(), buffered, peek(buffered))
            }
        }

        fun add(
            name: String,
            input: InputStream,
            head: ByteArray,
        ) {
            if (!RimeUserDbText.looksLikeSnapshot(head)) {
                skipped += SkippedSnapshot(name, SnapshotSkipReason.NOT_A_SNAPSHOT)
                return
            }
            if (staged.size >= MAXIMUM_FILES) throw TooLarge()
            val target = File(staging, "incoming-${staged.size + skipped.size}.userdb.txt")
            input.skipFully(RimeUserDbText.bomLength(head).toLong())
            val copied = target.outputStream().use { copyBounded(input, it, RimeUserDbText.MAXIMUM_BYTES) }
            if (copied > RimeUserDbText.MAXIMUM_BYTES) {
                target.delete()
                skipped += SkippedSnapshot(name, SnapshotSkipReason.TOO_LARGE)
                return
            }
            val snapshot =
                try {
                    RimeUserDbText.inspect(target)
                } catch (_: RimeLearningException) {
                    target.delete()
                    skipped += SkippedSnapshot(name, SnapshotSkipReason.NOT_A_SNAPSHOT)
                    return
                }
            if (snapshot.entryCount == 0) {
                target.delete()
                skipped += SkippedSnapshot(name, SnapshotSkipReason.EMPTY)
                return
            }
            // Only what is staged counts: empty or damaged files are dropped, so they must not
            // push a pick of good snapshots over the limit.
            total += copied
            if (total > MAXIMUM_TOTAL_BYTES) throw TooLarge()
            staged += StagedSnapshot(name, snapshot)
        }
    }

    /** Reads the first bytes without consuming them. */
    private fun peek(input: BufferedInputStream): ByteArray {
        input.mark(HEAD_BYTES)
        val head = ByteArray(HEAD_BYTES)
        var read = 0
        while (read < HEAD_BYTES) {
            val n = input.read(head, read, HEAD_BYTES - read)
            if (n < 0) break
            read += n
        }
        input.reset()
        return head.copyOf(read)
    }

    /** Copies at most [limit] + 1 bytes, so a result above [limit] means the source is larger. */
    private fun copyBounded(
        input: InputStream,
        output: OutputStream,
        limit: Long,
    ): Long {
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        while (copied <= limit) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), limit + 1 - copied).toInt())
            if (n < 0) break
            output.write(buffer, 0, n)
            copied += n
        }
        return copied
    }

    private fun InputStream.skipFully(count: Long) {
        var left = count
        while (left > 0) {
            if (read() < 0) return
            left--
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    /** Lets a reader wrap one ZIP entry without closing the whole archive. */
    private class NonClosing(
        private val input: InputStream,
    ) : InputStream() {
        override fun read(): Int = input.read()

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = input.read(b, off, len)

        override fun close() = Unit
    }
}
