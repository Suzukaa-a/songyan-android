package app.songyan.ime.learning

import app.songyan.ime.content.LearningBackupArchive
import app.songyan.ime.content.LearningBackupException
import app.songyan.ime.engine.EngineOwnership
import app.songyan.ime.rime.RimeSnapshotFile
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.LearningData
import app.songyan.ime.storage.PublicationRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

/** One learning snapshot as the engine reads and writes it (a librime `.userdb.txt`). */
class LearningSnapshot(
    val name: String,
    val data: ByteArray,
)

/** Why the engine refused a learning operation. */
enum class LearningEngineFailure {
    /** A snapshot is malformed. */
    INVALID,

    /** A snapshot is for a dictionary the input methods in use do not learn into. */
    OTHER_SOURCE,

    /** This engine build cannot do learning backup. */
    UNSUPPORTED,

    /** Reading or writing the learning data failed. */
    FAILED,
}

class LearningEngineException(
    val failure: LearningEngineFailure,
    cause: Throwable? = null,
) : Exception(failure.name, cause)

/**
 * The engine operations a learning backup needs, each opening and closing its own engine over one
 * published generation. Implemented in [app.songyan.ime.rime.RimeBindings].
 */
interface LearningEngine {
    class Export(
        val engineVersion: String,
        val snapshots: List<LearningSnapshot>,
    )

    suspend fun export(
        generationDir: File,
        userDataDir: File,
    ): Export

    /** Merges [snapshots] into [userDataDir]; refuses the whole batch before writing anything it cannot take. */
    suspend fun merge(
        generationDir: File,
        userDataDir: File,
        snapshots: List<LearningSnapshot>,
    )

    /** The user dictionaries the generation's input methods learn into, in use or not. */
    suspend fun declaredDictionaries(
        generationDir: File,
        userDataDir: File,
    ): Set<String>

    /**
     * Merges snapshots from any Rime front-end into [userDataDir] with librime's restore; refuses
     * the batch before writing when one is for a dictionary the generation does not declare.
     */
    suspend fun mergeSnapshotFiles(
        generationDir: File,
        userDataDir: File,
        snapshots: List<RimeSnapshotFile>,
    )
}

/** What [LearningBackupService.inspectRimeSnapshots] found: what will merge and what will not. */
class RimeSnapshotPlan(
    val merges: List<StagedSnapshot>,
    val skipped: List<SkippedSnapshot>,
    /** Holds this plan's staged copies only, so discarding it never touches a later plan's. */
    internal val stagingDir: File,
) {
    val dictionaryCount: Int get() = merges.map { it.snapshot.dictionary }.toSet().size
    val entryCount: Long get() = merges.sumOf { it.snapshot.entryCount.toLong() }
}

/** What [LearningBackupService.mergeRimeSnapshots] merged and what it left out. */
class RimeSnapshotMergeReport(
    val merged: List<StagedSnapshot>,
    val skipped: List<SkippedSnapshot>,
) {
    val dictionaryCount: Int get() = merged.map { it.snapshot.dictionary }.toSet().size
    val entryCount: Long get() = merged.sumOf { it.snapshot.entryCount.toLong() }
}

/** Why a backup, an import or a merge did not happen; in every case the learning data is unchanged. */
enum class LearningTransferProblem {
    /** No input scheme is published yet. */
    NO_SOURCE,

    /** The input scheme in use has not learned anything yet. */
    NOTHING_TO_BACK_UP,

    /** The file is not a learning backup, or it is damaged. */
    INVALID_ARCHIVE,

    /** The backup belongs to another input scheme. */
    OTHER_SOURCE,

    /** The current learning data could not be saved first, so the merge did not start. */
    SAFETY_BACKUP_FAILED,

    /** Reading or writing learning data failed. */
    FAILED,

    /** A set-aside learning set is gone or was already restored. */
    QUARANTINE_GONE,

    /** The engine cannot read a set-aside learning set safely; it is kept untouched. */
    QUARANTINE_UNREADABLE,

    /** A set-aside learning set belongs to an input scheme that is not in use. */
    QUARANTINE_NOT_IN_USE,

    /** A set-aside learning set holds no words the input scheme in use can take. */
    QUARANTINE_EMPTY,

    /** The picked files hold more dictionaries or data than one import takes. */
    TOO_LARGE,
}

class LearningTransferException(
    val problem: LearningTransferProblem,
    cause: Throwable? = null,
) : Exception(problem.name, cause)

/**
 * Learning backup, import and merge, run in the input method's process (section 2.2 of the plan):
 * the input method's engine is closed for the duration through [EngineOwnership.exclusive], one
 * engine at a time reads or writes the learning data, and the keyboard opens its engine again
 * afterwards.
 *
 * A merge never writes the live learning directory. It saves the current learning data as a
 * backup the user can export, merges into a copy, and swaps the copy in with two renames that
 * [LearningData.recoverInterruptedMerges] completes or undoes after a crash; any failure before
 * the swap discards the copy. So a merge either lands completely or leaves the learning data as
 * it was.
 */
class LearningBackupService(
    private val storage: AppStorage,
    private val publisher: GenerationPublisher,
    private val ownership: EngineOwnership,
    private val engine: LearningEngine,
    /** Display name of a source, written into the backups made from it. */
    private val sourceName: (sourceId: String) -> String,
    private val clock: () -> Instant = Instant::now,
    private val quarantine: QuarantineStore = QuarantineStore(storage),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Runs after the merge into the copy and before the swap; tests use it to interrupt there. */
    private val beforeSwap: suspend () -> Unit = {},
) {
    /** One operation at a time on the set-aside sets: a delete never races an engine reading one. */
    private val quarantineLock = Mutex()

    private val safetyFile: File get() = File(storage.learningBackupDir, SAFETY_FILE)

    /** Copies of picked Rime snapshots between [inspectRimeSnapshots] and the merge. */
    private val snapshotStaging: File get() = File(storage.rimeRoot, SNAPSHOT_STAGING)

    /** The learning data of the input scheme in use, as a backup archive. */
    suspend fun backup(): LearningBackupArchive =
        ownership.exclusive {
            withContext(ioDispatcher) {
                LearningData.recoverInterruptedMerges(storage)
                val active = activeOrFail()
                snapshotArchive(active) ?: fail(LearningTransferProblem.NOTHING_TO_BACK_UP)
            }
        }

    /**
     * Epoch milliseconds of the newest change to the learning data of the input scheme in use, or
     * null when there is no input scheme or it has learned nothing. Reads only file times, so it
     * never opens an engine.
     */
    suspend fun learningModifiedAt(): Long? =
        withContext(ioDispatcher) {
            val active = publisher.readActive()?.takeIf { publisher.isComplete(it) } ?: return@withContext null
            val live = storage.learningDir(LearningData.namespace(active.sourceId))
            if (!LearningData.holdsLearningData(live)) return@withContext null
            live.walkTopDown().maxOf { it.lastModified() }
        }

    /** Reads a picked file and checks that it may merge into the input scheme in use. */
    suspend fun inspect(bytes: ByteArray): LearningBackupArchive =
        withContext(ioDispatcher) {
            val archive =
                try {
                    LearningBackupArchive.decode(bytes)
                } catch (e: LearningBackupException) {
                    throw LearningTransferException(LearningTransferProblem.INVALID_ARCHIVE, e)
                }
            requireSameSource(archive, activeOrFail())
            archive
        }

    /**
     * Merges [archive] into the learning data of the input scheme in use. [restoredFrom] names the
     * set-aside learning set the archive was read from; it is marked restored once the merge landed.
     */
    suspend fun merge(
        archive: LearningBackupArchive,
        restoredFrom: String? = null,
    ) {
        ownership.exclusive {
            // Once the copy is merged, the swap must finish even if the caller goes away.
            withContext(ioDispatcher + NonCancellable) {
                mergeExclusive(archive)
                if (restoredFrom != null) quarantine.markRestored(restoredFrom)
            }
        }
    }

    /**
     * Reads Rime user dictionary snapshots from another front-end ([RimeSnapshotCollector]) and
     * sorts them by whether the input scheme in use learns into their dictionaries; any input
     * method of it counts, in use or not. The staged copies wait for [mergeRimeSnapshots] or
     * [discardRimeSnapshots]; a later call replaces them.
     */
    suspend fun inspectRimeSnapshots(
        files: List<PickedFile>,
        byContent: Boolean,
    ): RimeSnapshotPlan =
        ownership.exclusive {
            withContext(ioDispatcher) {
                val active = activeOrFail()
                // Copies left by an earlier plan that was never merged or discarded (the process ended).
                snapshotStaging.listFiles().orEmpty().forEach { it.deleteRecursively() }
                val staging = File(snapshotStaging, UUID.randomUUID().toString())
                val collected =
                    try {
                        RimeSnapshotCollector.collect(files, staging, byContent)
                    } catch (e: RimeSnapshotCollector.TooLarge) {
                        staging.deleteRecursively()
                        throw LearningTransferException(LearningTransferProblem.TOO_LARGE, e)
                    } catch (e: IOException) {
                        staging.deleteRecursively()
                        throw LearningTransferException(LearningTransferProblem.FAILED, e)
                    } catch (e: Exception) {
                        staging.deleteRecursively()
                        throw e
                    }
                val declared = if (collected.staged.isEmpty()) emptySet() else declaredDictionaries(active)
                val (merges, foreign) = collected.staged.partition { it.snapshot.dictionary in declared }
                RimeSnapshotPlan(
                    merges,
                    collected.skipped + foreign.map { SkippedSnapshot(it.fileName, SnapshotSkipReason.NOT_INSTALLED) },
                    staging,
                )
            }
        }

    /** Drops the copies [inspectRimeSnapshots] staged for [plan]. */
    suspend fun discardRimeSnapshots(plan: RimeSnapshotPlan) {
        withContext(ioDispatcher) { plan.stagingDir.deleteRecursively() }
    }

    /**
     * Merges Rime user dictionary snapshots into the learning data of the input scheme in use,
     * each into the dictionary its header names, with the guarantees of [merge]: the keyboard's
     * engine yields, the current learning data is saved first, the merge lands in a copy that is
     * swapped in whole. librime's restore merges the way a sync between devices does, so nothing
     * already learned is replaced. A snapshot whose dictionary no input method of the scheme in
     * use learns into is skipped; when none is left, nothing is saved or written.
     *
     * The snapshot files must stay in place until this returns; the caller owns them.
     */
    suspend fun mergeRimeSnapshots(snapshots: List<StagedSnapshot>): RimeSnapshotMergeReport =
        ownership.exclusive {
            withContext(ioDispatcher + NonCancellable) {
                LearningData.recoverInterruptedMerges(storage)
                val active = activeOrFail()
                val declared = declaredDictionaries(active)
                val (merges, foreign) = snapshots.partition { it.snapshot.dictionary in declared }
                val skipped = foreign.map { SkippedSnapshot(it.fileName, SnapshotSkipReason.NOT_INSTALLED) }
                if (merges.isNotEmpty()) {
                    mergeIntoCopy(active) { work ->
                        engine.mergeSnapshotFiles(generationDir(active), work, merges.map { it.snapshot })
                    }
                }
                RimeSnapshotMergeReport(merges, skipped)
            }
        }

    /** The dictionaries of [active], read by an engine over an empty scratch directory. */
    private suspend fun declaredDictionaries(active: PublicationRecord): Set<String> {
        val scratch = File(storage.rimeRoot, DECLARED_SCRATCH)
        try {
            scratch.deleteRecursively()
            if (!scratch.mkdirs()) throw IOException("cannot create the scratch directory")
            return engine.declaredDictionaries(generationDir(active), scratch)
        } catch (e: LearningEngineException) {
            throw LearningTransferException(LearningTransferProblem.FAILED, e)
        } catch (e: IOException) {
            throw LearningTransferException(LearningTransferProblem.FAILED, e)
        } finally {
            scratch.deleteRecursively()
        }
    }

    /** The learning sets an engine start crash moved aside, newest first. */
    suspend fun quarantined(): List<QuarantinedSet> = withContext(ioDispatcher) { quarantine.list() }

    /** Deletes the learning data of the set-aside set [id]; false when that did not happen. */
    suspend fun deleteQuarantined(id: String): Boolean =
        quarantineLock.withLock { withContext(ioDispatcher + NonCancellable) { quarantine.delete(id) } }

    /**
     * Reads the set-aside learning set [id] into a backup archive for the input scheme in use,
     * ready for [merge]. The set's userdb is the kind of data that crashed the engine, so it is
     * never put back as it is: a copy is opened with a temporary engine and only the text export
     * of the copy leaves it, to go through the same checked merge as any backup. A marker written
     * before the engine opens it turns a native crash into "unreadable, kept" next time.
     */
    suspend fun inspectQuarantined(id: String): LearningBackupArchive =
        ownership.exclusive { quarantineLock.withLock { withContext(ioDispatcher) { exportQuarantined(id) } } }

    private suspend fun exportQuarantined(id: String): LearningBackupArchive {
        LearningData.recoverInterruptedMerges(storage)
        val set = quarantine.find(id) ?: fail(LearningTransferProblem.QUARANTINE_GONE)
        when (set.state) {
            QuarantineState.HELD -> Unit
            QuarantineState.UNREADABLE -> fail(LearningTransferProblem.QUARANTINE_UNREADABLE)
            QuarantineState.RESTORED -> fail(LearningTransferProblem.QUARANTINE_GONE)
        }
        val active = activeOrFail()
        if (LearningData.namespace(active.sourceId) != set.namespace) {
            fail(LearningTransferProblem.QUARANTINE_NOT_IN_USE)
        }
        val work = File(storage.rimeRoot, QUARANTINE_WORK)
        try {
            work.deleteRecursively()
            copyLearningDirectory(quarantine.dataDirectory(set), work)
            try {
                quarantine.markReadAttempt(id)
            } catch (e: IOException) {
                // Without the marker a crash would not be remembered, so the engine does not open it.
                throw LearningTransferException(LearningTransferProblem.FAILED, e)
            }
            val export =
                try {
                    engine.export(generationDir(active), work)
                } catch (e: LearningEngineException) {
                    throw LearningTransferException(LearningTransferProblem.QUARANTINE_UNREADABLE, e)
                } finally {
                    quarantine.clearReadAttempt(id)
                }
            if (export.snapshots.isEmpty()) fail(LearningTransferProblem.QUARANTINE_EMPTY)
            return archiveOf(active, export)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LearningTransferException) {
            throw e
        } catch (e: Exception) {
            throw LearningTransferException(LearningTransferProblem.FAILED, e)
        } finally {
            work.deleteRecursively()
        }
    }

    /** The backup saved automatically before the last merge, or null when there is none. */
    suspend fun safetyBackup(): LearningBackupArchive? =
        withContext(ioDispatcher) {
            val file = safetyFile
            if (!file.isFile || file.length() > LearningBackupArchive.MAXIMUM_ENCODED_BYTES) return@withContext null
            try {
                LearningBackupArchive.decode(file.readBytes())
            } catch (_: LearningBackupException) {
                null
            } catch (_: IOException) {
                null
            }
        }

    private suspend fun mergeExclusive(archive: LearningBackupArchive) {
        LearningData.recoverInterruptedMerges(storage)
        val active = activeOrFail()
        requireSameSource(archive, active)
        mergeIntoCopy(active) { work ->
            engine.merge(
                generationDir(active),
                work,
                archive.dictionaries.map { LearningSnapshot(it.name, it.data) },
            )
        }
    }

    /**
     * Saves the learning data of [active] as the safety backup, runs [merge] on a copy of it and
     * swaps the copy in; any failure before the swap discards the copy.
     */
    private suspend fun mergeIntoCopy(
        active: PublicationRecord,
        merge: suspend (work: File) -> Unit,
    ) {
        val namespace = LearningData.namespace(active.sourceId)
        val live = storage.learningDir(namespace)
        if (LearningData.holdsLearningData(live)) {
            val safety =
                try {
                    snapshotArchive(active)
                } catch (e: LearningTransferException) {
                    throw LearningTransferException(LearningTransferProblem.SAFETY_BACKUP_FAILED, e)
                }
            if (safety != null) saveSafetyBackup(safety)
        }
        val work = LearningData.mergeWorkDir(storage, namespace)
        try {
            work.deleteRecursively()
            copyLearningDirectory(live, work)
            try {
                merge(work)
            } catch (e: LearningEngineException) {
                throw LearningTransferException(
                    when (e.failure) {
                        LearningEngineFailure.INVALID -> LearningTransferProblem.INVALID_ARCHIVE
                        LearningEngineFailure.OTHER_SOURCE -> LearningTransferProblem.OTHER_SOURCE
                        else -> LearningTransferProblem.FAILED
                    },
                    e,
                )
            }
            beforeSwap()
            swapIn(work, live, LearningData.mergePreviousDir(storage, namespace))
        } catch (e: CancellationException) {
            throw e
        } catch (e: LearningTransferException) {
            work.deleteRecursively()
            throw e
        } catch (e: Exception) {
            work.deleteRecursively()
            throw LearningTransferException(LearningTransferProblem.FAILED, e)
        }
    }

    private suspend fun snapshotArchive(active: PublicationRecord): LearningBackupArchive? {
        val live = storage.learningDir(LearningData.namespace(active.sourceId))
        if (!LearningData.holdsLearningData(live)) return null
        val export =
            try {
                engine.export(generationDir(active), live)
            } catch (e: LearningEngineException) {
                throw LearningTransferException(LearningTransferProblem.FAILED, e)
            }
        if (export.snapshots.isEmpty()) return null
        return archiveOf(active, export)
    }

    private fun archiveOf(
        active: PublicationRecord,
        export: LearningEngine.Export,
    ): LearningBackupArchive =
        try {
            LearningBackupArchive(
                sourceId = LearningBackupIdentity.archiveSourceId(active.sourceId),
                resourceRevision = active.revision.ifEmpty { active.generation },
                sourceName = sourceName(active.sourceId).ifBlank { active.sourceId },
                engineVersion = export.engineVersion,
                createdAt = clock(),
                dictionaries = export.snapshots.map { LearningBackupArchive.Dictionary(it.name, it.data) },
            )
        } catch (e: LearningBackupException) {
            throw LearningTransferException(LearningTransferProblem.FAILED, e)
        }

    private fun saveSafetyBackup(archive: LearningBackupArchive) {
        try {
            val directory = storage.learningBackupDir
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("cannot create " + directory.name)
            val temporary = File(directory, "$SAFETY_FILE.tmp")
            temporary.writeBytes(archive.encoded())
            Files.move(
                temporary.toPath(),
                safetyFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: IOException) {
            throw LearningTransferException(LearningTransferProblem.SAFETY_BACKUP_FAILED, e)
        } catch (e: LearningBackupException) {
            throw LearningTransferException(LearningTransferProblem.SAFETY_BACKUP_FAILED, e)
        }
    }

    private fun activeOrFail(): PublicationRecord {
        val active = publisher.readActive() ?: fail(LearningTransferProblem.NO_SOURCE)
        if (!publisher.isComplete(active)) fail(LearningTransferProblem.NO_SOURCE)
        return active
    }

    private fun requireSameSource(
        archive: LearningBackupArchive,
        active: PublicationRecord,
    ) {
        if (!LearningBackupIdentity.accepts(archive.sourceId, active.sourceId)) {
            fail(LearningTransferProblem.OTHER_SOURCE)
        }
    }

    private fun generationDir(active: PublicationRecord): File = storage.generationDir(active.generation)

    private fun fail(problem: LearningTransferProblem): Nothing = throw LearningTransferException(problem)

    companion object {
        private const val SAFETY_FILE = "before-merge.json"

        /** Picked Rime snapshots waiting for the merge; outside `user/`, so never live. */
        private const val SNAPSHOT_STAGING = "snapshot-import"

        /** An empty user directory for the engine that lists the declared dictionaries. */
        private const val DECLARED_SCRATCH = "declared-scratch"

        /** Scratch copy of a set-aside userdb while an engine reads it; outside `user/`, so never live. */
        private const val QUARANTINE_WORK = "quarantine-work"

        /** librime's sync folder and the engine's own staging hold exports, never learning data. */
        private val NOT_COPIED = setOf("sync", "learning-backup-work")

        /** Copies the learning directory [from] (if any) to the new directory [to]. */
        internal fun copyLearningDirectory(
            from: File,
            to: File,
        ) {
            if (!to.mkdirs()) throw IOException("cannot create the merge directory")
            if (!from.isDirectory) return
            for (child in from.listFiles().orEmpty()) {
                if (child.name in NOT_COPIED) continue
                if (!child.copyRecursively(File(to, child.name), overwrite = false)) {
                    throw IOException("cannot copy learning data")
                }
            }
        }

        /**
         * Replaces [live] by [work]: live becomes [previous], work becomes live, previous is
         * deleted. A crash between the steps is finished or undone by
         * [LearningData.recoverInterruptedMerges].
         */
        internal fun swapIn(
            work: File,
            live: File,
            previous: File,
        ) {
            if (live.exists()) Files.move(live.toPath(), previous.toPath(), StandardCopyOption.ATOMIC_MOVE)
            Files.move(work.toPath(), live.toPath(), StandardCopyOption.ATOMIC_MOVE)
            previous.deleteRecursively()
        }
    }
}
