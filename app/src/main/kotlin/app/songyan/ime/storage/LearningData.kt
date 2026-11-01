package app.songyan.ime.storage

import android.util.Log
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Identities of the Rime sources the app stores under `rime/sources/`. */
object SourceIds {
    /** The bundled package. */
    const val BUILTIN = "songyan-builtin"

    /** The built-in input methods over the user's imported dictionary. */
    const val DICTIONARY = "user-pinyin"

    /** The single scheme-package slot of earlier versions; still readable, never written. */
    const val LEGACY_SCHEME = "user-scheme"

    /** Every imported scheme package: `scheme-` plus the package's identity hash. */
    const val SCHEME_PREFIX = "scheme-"

    private val PATTERN = Regex("^[a-z0-9][a-z0-9-]{0,39}$")

    fun isValid(sourceId: String): Boolean = PATTERN.matches(sourceId)

    fun isScheme(sourceId: String): Boolean = sourceId.startsWith(SCHEME_PREFIX) || sourceId == LEGACY_SCHEME
}

/** Merges every userdb of one learning directory into another; true only when all of it arrived. */
fun interface LearningMerger {
    suspend fun merge(
        from: File,
        into: File,
    ): Boolean
}

/**
 * Where each source's learning data lives.
 *
 * Rime records learned words by their codes, so input methods with different coding systems must
 * not share a userdb. The built-in input methods and the user's imported dictionary use the same
 * coding (full pinyin and the built-in double pinyin over the same dictionary family), so they
 * share one namespace; every imported scheme package learns in its own, keyed by the package
 * identity, so re-importing the same package finds its learning data again. A namespace never
 * follows a generation or a source tree: importing a dictionary, switching or deleting a scheme
 * package leaves every namespace on disk.
 */
object LearningData {
    /** The namespace of the built-in input methods and the imported dictionary. */
    const val PINYIN = "pinyin"

    private const val TAG = "LearningData"
    private const val USERDB_EXTENSION = ".userdb"
    private const val RETIRED_PREFIX = ".retired-"
    private const val MERGE_WORK_PREFIX = ".merge-work-"
    private const val MERGE_PREVIOUS_PREFIX = ".merge-previous-"
    private val LEGACY_SOURCES = listOf(SourceIds.BUILTIN, SourceIds.DICTIONARY)

    fun namespace(sourceId: String): String =
        when (sourceId) {
            SourceIds.BUILTIN, SourceIds.DICTIONARY -> PINYIN
            else -> sourceId
        }

    fun directory(
        storage: AppStorage,
        sourceId: String,
    ): File = storage.learningDir(namespace(sourceId))

    /**
     * Merges the per-source learning directories of earlier versions (`user/songyan-builtin` and
     * `user/user-pinyin`) into the shared [PINYIN] namespace. Runs in the input
     * method process before its first engine opens a userdb, so no engine holds any of them.
     *
     * When [PINYIN] does not exist yet, the legacy directory the user typed with most recently (the
     * active source's, else the newest) becomes it by a rename, which keeps every byte. Every other
     * legacy directory is merged into it by [merger] (librime's userdb export and import) and
     * deleted only after the merge succeeded; one that fails, or waits for a [merger] because no
     * generation is published yet, stays on disk and is tried again on the next start. Returns
     * true when no legacy directory is left.
     */
    suspend fun migrate(
        storage: AppStorage,
        activeSourceId: String?,
        merger: LearningMerger?,
    ): Boolean {
        clearRetired(storage)
        val target = storage.learningDir(PINYIN)
        val legacy =
            LEGACY_SOURCES
                .map { storage.learningDir(it) }
                .filter { it.isDirectory }
                .toMutableList()
        for (directory in legacy.toList()) {
            if (!holdsLearningData(directory)) {
                retire(storage, directory)
                legacy -= directory
            }
        }
        if (legacy.isEmpty()) return true
        if (!target.exists()) {
            val chosen =
                legacy.firstOrNull { it.name == activeSourceId }
                    ?: legacy.maxByOrNull { newestModification(it) }
            if (chosen != null) {
                try {
                    Files.move(chosen.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    legacy -= chosen
                } catch (e: IOException) {
                    Log.w(TAG, "cannot move legacy learning data from " + chosen.name, e)
                }
            }
        }
        if (merger == null) return legacy.isEmpty()
        for (directory in legacy.toList()) {
            val merged =
                try {
                    target.mkdirs()
                    merger.merge(directory, target)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "cannot merge legacy learning data from " + directory.name, e)
                    false
                }
            if (merged) {
                retire(storage, directory)
                legacy -= directory
            } else {
                Log.w(TAG, "legacy learning data kept for the next start: " + directory.name)
            }
        }
        return legacy.isEmpty()
    }

    /** Where a learning-backup merge builds the merged copy of [namespace] before it replaces the live one. */
    fun mergeWorkDir(
        storage: AppStorage,
        namespace: String,
    ): File = File(storage.userRoot, MERGE_WORK_PREFIX + namespace)

    /** Where the live [namespace] waits while the merged copy takes its place. */
    fun mergePreviousDir(
        storage: AppStorage,
        namespace: String,
    ): File = File(storage.userRoot, MERGE_PREVIOUS_PREFIX + namespace)

    /**
     * Finishes or undoes a learning-backup merge that the process did not live to complete. The
     * merge only ever writes its own copy and then swaps it in with two renames, so every
     * interrupted state has one safe outcome: a namespace that is missing gets its untouched
     * previous data back, a previous copy next to a live namespace is the leftover of a finished
     * swap, and a merge copy that was never swapped in is discarded. Must run while no engine of
     * this process has a learning directory open.
     */
    fun recoverInterruptedMerges(storage: AppStorage) {
        val children = storage.userRoot.listFiles().orEmpty()
        for (previous in children.filter { it.name.startsWith(MERGE_PREVIOUS_PREFIX) }) {
            val live = storage.learningDir(previous.name.removePrefix(MERGE_PREVIOUS_PREFIX))
            if (live.exists()) {
                previous.deleteRecursively()
            } else {
                try {
                    Files.move(previous.toPath(), live.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } catch (e: IOException) {
                    Log.w(TAG, "cannot restore learning data after an interrupted merge", e)
                }
            }
        }
        children.filter { it.name.startsWith(MERGE_WORK_PREFIX) }.forEach { it.deleteRecursively() }
    }

    /** Whether [directory] holds a userdb (librime keeps each as a `<name>.userdb` directory). */
    fun holdsLearningData(directory: File): Boolean =
        directory.listFiles().orEmpty().any { it.name.endsWith(USERDB_EXTENSION) }

    /**
     * Removes a legacy directory that is no longer needed. The rename comes first so a process
     * killed while deleting never leaves a half-deleted userdb where the next start would read it.
     */
    private fun retire(
        storage: AppStorage,
        directory: File,
    ) {
        val retired = File(storage.userRoot, RETIRED_PREFIX + directory.name + "-" + System.nanoTime())
        try {
            Files.move(directory.toPath(), retired.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            Log.w(TAG, "cannot retire legacy learning data " + directory.name, e)
            return
        }
        retired.deleteRecursively()
    }

    private fun clearRetired(storage: AppStorage) {
        storage.userRoot
            .listFiles()
            .orEmpty()
            .filter { it.name.startsWith(RETIRED_PREFIX) }
            .forEach { it.deleteRecursively() }
    }

    private fun newestModification(directory: File): Long =
        directory.walkTopDown().maxOfOrNull { it.lastModified() } ?: 0L
}
