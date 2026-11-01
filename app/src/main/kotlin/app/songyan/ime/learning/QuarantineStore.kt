package app.songyan.ime.learning

import android.util.Log
import app.songyan.ime.engine.EngineStartGuard
import app.songyan.ime.engine.QuarantineRecord
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.LearningData
import app.songyan.ime.storage.SourceIds
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Where a quarantined learning set stands; see [QuarantinedSet.state]. */
enum class QuarantineState {
    /** Nothing has touched it; it can be read for a restore. */
    HELD,

    /**
     * A restore opened it with an engine and the process never came back, so the engine cannot
     * read it safely. It is kept as it is and never opened again.
     */
    UNREADABLE,

    /** Its words were merged back; the set stays until the user deletes it. */
    RESTORED,
}

/** One quarantine that moved learning data aside, as the restore screens list it. */
data class QuarantinedSet(
    /** The quarantine directory's name under `rime/quarantine/`; opaque to callers. */
    val id: String,
    val record: QuarantineRecord,
    /** The learning namespace the moved data belonged to ([LearningData.namespace]). */
    val namespace: String,
    val state: QuarantineState,
)

/**
 * Reads and updates what [EngineStartGuard] moved into `rime/quarantine/`. A quarantined userdb is
 * the kind of data that crashed the engine, so nothing here opens it: the markers below let the
 * restore flow record, before an engine reads a set, that it is about to, so a native crash during
 * that read leaves the set marked [QuarantineState.UNREADABLE] instead of crashing again.
 * Disk IO; call off the main thread.
 */
class QuarantineStore(
    private val storage: AppStorage,
) {
    private val root: File get() = File(storage.rimeRoot, EngineStartGuard.QUARANTINE_DIR)

    /** Sets that still hold learning data, newest first. */
    fun list(): List<QuarantinedSet> =
        root
            .listFiles()
            .orEmpty()
            .mapNotNull { read(it) }
            .sortedByDescending { it.record.timeEpochMs }

    fun find(id: String): QuarantinedSet? = if (isValidId(id)) read(File(root, id)) else null

    /** The directory holding the set's userdbs; read-only for every caller. */
    fun dataDirectory(set: QuarantinedSet): File = File(File(root, set.id), USER_PREFIX + set.namespace)

    /** Durably records that an engine is about to read [id]; throws when that cannot be guaranteed. */
    fun markReadAttempt(id: String) {
        val file = File(setDirectory(id), ATTEMPT_MARKER)
        FileOutputStream(file).use {
            it.write(1)
            it.flush()
            it.fd.sync()
        }
    }

    /** The read came back (with a result or a clean error): the engine survived it. */
    fun clearReadAttempt(id: String) {
        File(setDirectory(id), ATTEMPT_MARKER).delete()
    }

    /** Its words were merged back. Best effort: a lost marker only offers the restore again. */
    fun markRestored(id: String) {
        try {
            File(setDirectory(id), RESTORED_MARKER).writeBytes(byteArrayOf(1))
        } catch (e: IOException) {
            Log.w(TAG, "cannot record the restore", e)
        }
    }

    /**
     * Deletes the set's learning data and keeps its record (so the About page still tells what
     * happened). The data is renamed out of the way first: a process killed mid-delete never
     * leaves a half-deleted userdb under a name something reads.
     */
    fun delete(id: String): Boolean {
        val set = find(id) ?: return false
        val data = dataDirectory(set)
        val doomed = File(data.parentFile, DELETING_PREFIX + data.name + "-" + System.nanoTime())
        try {
            Files.move(data.toPath(), doomed.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            Log.w(TAG, "cannot delete quarantined learning data", e)
            return false
        }
        try {
            File(data.parentFile, RECORD_NAME).writeText(set.record.copy(movedLearningData = false).toJson())
        } catch (e: IOException) {
            Log.w(TAG, "cannot update the quarantine record", e)
        }
        doomed.deleteRecursively()
        File(data.parentFile, ATTEMPT_MARKER).delete()
        File(data.parentFile, RESTORED_MARKER).delete()
        return true
    }

    private fun setDirectory(id: String): File {
        require(isValidId(id)) { "bad quarantine id" }
        return File(root, id)
    }

    private fun read(directory: File): QuarantinedSet? {
        if (!directory.isDirectory || !isValidId(directory.name)) return null
        val record =
            try {
                QuarantineRecord.fromJson(File(directory, RECORD_NAME).readText())
            } catch (_: IOException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } ?: return null
        if (!record.movedLearningData) return null
        val data =
            directory
                .listFiles()
                .orEmpty()
                .filter { it.isDirectory && it.name.startsWith(USER_PREFIX) }
                .firstOrNull { LearningData.holdsLearningData(it) } ?: return null
        val namespace = data.name.removePrefix(USER_PREFIX)
        if (!SourceIds.isValid(namespace)) return null
        val state =
            when {
                File(directory, ATTEMPT_MARKER).exists() -> QuarantineState.UNREADABLE
                File(directory, RESTORED_MARKER).exists() -> QuarantineState.RESTORED
                else -> QuarantineState.HELD
            }
        return QuarantinedSet(directory.name, record, namespace, state)
    }

    private companion object {
        const val TAG = "QuarantineStore"
        const val RECORD_NAME = "quarantine.json"
        const val USER_PREFIX = "user-"
        const val ATTEMPT_MARKER = "restore-attempt"
        const val RESTORED_MARKER = "restored"
        const val DELETING_PREFIX = ".deleting-"

        /** Directory names the guard writes: the time in milliseconds, plus `-<n>` on a clash. */
        private val ID = Regex("^[0-9]{1,20}(-[0-9]{1,6})?$")

        fun isValidId(id: String): Boolean = ID.matches(id)
    }
}
