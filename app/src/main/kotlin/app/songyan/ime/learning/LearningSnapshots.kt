package app.songyan.ime.learning

import app.songyan.ime.content.LearningBackupArchive
import app.songyan.ime.content.LearningBackupException
import app.songyan.ime.storage.AppStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * Automatic, rotating copies of the learning data in app-private storage, so the userdb is never
 * the only copy (a corrupt or wiped userdb can be restored from the last few days).
 *
 * A snapshot is a normal learning backup archive (the iOS-compatible format of
 * [LearningBackupService.backup]), written as `snapshot-<epoch seconds>.json`. At most one is taken
 * per [minInterval], only when the learning data changed since the previous one, and only the newest
 * [keep] stay. Taking one briefly sets the keyboard's engine aside ([LearningBackupService.backup]),
 * so the caller picks a quiet moment. Restoring goes through the existing import-merge path.
 */
class LearningSnapshots(
    private val storage: AppStorage,
    private val service: LearningBackupService,
    private val clock: () -> Instant = Instant::now,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val keep: Int = DEFAULT_KEEP,
    private val minIntervalSeconds: Long = DEFAULT_INTERVAL_SECONDS,
) {
    /** One stored snapshot. [id] is its file name. */
    class Entry(
        val id: String,
        val archive: LearningBackupArchive,
    )

    private val mutex = Mutex()

    /** Takes a snapshot when one is due; returns whether it did. Failures leave the existing ones as they were. */
    suspend fun snapshotIfDue(): Boolean =
        mutex.withLock {
            val newest = withContext(ioDispatcher) { names().maxOfOrNull { seconds(it) } }
            val now = clock().epochSecond
            if (newest != null && now - newest < minIntervalSeconds) return false
            val modified = service.learningModifiedAt() ?: return false
            if (newest != null && modified / MILLIS <= newest) return false
            val archive =
                try {
                    service.backup()
                } catch (e: LearningTransferException) {
                    if (e.problem == LearningTransferProblem.NOTHING_TO_BACK_UP ||
                        e.problem == LearningTransferProblem.NO_SOURCE
                    ) {
                        return false
                    }
                    throw e
                }
            withContext(ioDispatcher) {
                write(archive)
                prune()
            }
            true
        }

    /** Stored snapshots, newest first; an unreadable file is skipped. */
    suspend fun list(): List<Entry> =
        withContext(ioDispatcher) {
            names().sortedByDescending { seconds(it) }.mapNotNull { name ->
                val archive = decode(name) ?: return@mapNotNull null
                Entry(name, archive)
            }
        }

    /** The bytes of snapshot [id], or null when it is gone or [id] is not a snapshot name. */
    suspend fun read(id: String): ByteArray? =
        withContext(ioDispatcher) {
            if (!NAME.matches(id)) return@withContext null
            val file = File(storage.learningSnapshotsDir, id)
            if (!file.isFile || file.length() > LearningBackupArchive.MAXIMUM_ENCODED_BYTES) return@withContext null
            try {
                file.readBytes()
            } catch (_: IOException) {
                null
            }
        }

    private fun names(): List<String> =
        storage.learningSnapshotsDir
            .list()
            .orEmpty()
            .filter { NAME.matches(it) }

    private fun seconds(name: String): Long = NAME.matchEntire(name)!!.groupValues[1].toLong()

    private fun decode(name: String): LearningBackupArchive? =
        try {
            val file = File(storage.learningSnapshotsDir, name)
            if (file.length() > LearningBackupArchive.MAXIMUM_ENCODED_BYTES) {
                null
            } else {
                LearningBackupArchive.decode(file.readBytes())
            }
        } catch (_: LearningBackupException) {
            null
        } catch (_: IOException) {
            null
        }

    private fun write(archive: LearningBackupArchive) {
        val directory = storage.learningSnapshotsDir
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("cannot create " + directory.name)
        val target = File(directory, "snapshot-" + archive.createdAt.epochSecond + ".json")
        val temporary = File(directory, target.name + ".tmp")
        temporary.writeBytes(archive.encoded())
        Files.move(
            temporary.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    /** Keeps the newest [keep] snapshots and removes the rest and any interrupted temporary file. */
    internal fun prune() {
        val directory = storage.learningSnapshotsDir
        val kept = names().sortedByDescending { seconds(it) }.take(keep).toSet()
        for (file in directory.listFiles().orEmpty()) {
            if (file.name !in kept) file.delete()
        }
    }

    companion object {
        const val DEFAULT_KEEP = 3
        const val DEFAULT_INTERVAL_SECONDS = 24L * 60 * 60
        private const val MILLIS = 1000L
        private val NAME = Regex("snapshot-(\\d{1,12})\\.json")
    }
}
