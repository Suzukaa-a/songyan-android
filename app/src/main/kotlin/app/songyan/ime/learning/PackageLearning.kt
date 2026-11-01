package app.songyan.ime.learning

import app.songyan.ime.rime.RimeLearningException
import app.songyan.ime.rime.RimeUserDbText
import app.songyan.ime.storage.AppStorage
import java.io.File

/**
 * The learned-word snapshots a scheme package shipped. The deploy process stages them while it
 * imports the package ([stage], [keep]); the merge waits for the user's answer and for the package
 * to be in use, because only the scheme in use has a published generation to merge into.
 */
class PackageLearning(
    private val storage: AppStorage,
) {
    /** Where [stage] puts the snapshots of one import request until the import ends. */
    fun stagingDir(requestId: String): File = File(storage.importStagingDir, STAGING_PREFIX + requestId)

    /** Copies the snapshots out of [archive]; null when it has none, or they cannot be read. */
    fun stage(
        archive: File,
        requestId: String,
    ): File? {
        val dir = stagingDir(requestId)
        val collected =
            try {
                RimeSnapshotCollector.collectFromPackage(archive, dir)
            } catch (_: Exception) {
                dir.deleteRecursively()
                return null
            }
        if (collected.staged.isEmpty()) {
            dir.deleteRecursively()
            return null
        }
        return dir
    }

    /** Makes [staged] the pending snapshots of [sourceId], replacing what an earlier version left. */
    fun keep(
        staged: File,
        sourceId: String,
    ) {
        val target = storage.packageLearningDir(sourceId)
        target.deleteRecursively()
        target.parentFile?.mkdirs()
        if (!staged.renameTo(target)) {
            staged.deleteRecursively()
        }
    }

    /** The snapshots waiting for [sourceId], read from their headers. */
    fun pending(sourceId: String): List<StagedSnapshot> =
        storage
            .packageLearningDir(sourceId)
            .listFiles { file -> file.isFile && RimeSnapshotCollector.isSnapshotName(file.name) }
            .orEmpty()
            .sortedBy { it.name }
            .mapNotNull { file ->
                try {
                    StagedSnapshot(file.name, RimeUserDbText.inspect(file)).takeIf { it.snapshot.entryCount > 0 }
                } catch (_: RimeLearningException) {
                    null
                }
            }

    fun discard(sourceId: String) {
        storage.packageLearningDir(sourceId).deleteRecursively()
    }

    /** Drops the snapshots of every package that is no longer stored. */
    fun sweep(stored: Set<String>) {
        storage.packageLearningRoot
            .listFiles()
            .orEmpty()
            .filter { it.name !in stored }
            .forEach { it.deleteRecursively() }
    }

    private companion object {
        const val STAGING_PREFIX = "learning-"
    }
}

/** What accepting the merge of a package's snapshots did. */
sealed interface PackageLearningOutcome {
    class Merged(
        val report: RimeSnapshotMergeReport,
    ) : PackageLearningOutcome

    /** No dictionary of the package's input methods matches a snapshot; nothing was written. */
    data object NothingToMerge : PackageLearningOutcome

    /** The package could not be put in use, so nothing was merged. */
    data object NotInUse : PackageLearningOutcome

    /** The snapshots are gone (answered already, or the package was removed). */
    data object Gone : PackageLearningOutcome

    data object Failed : PackageLearningOutcome
}

/**
 * Merges a package's pending snapshots after the user agreed: puts the package in use first when
 * it is not, then merges into the learning data of the scheme in use. The package itself was
 * installed long before; whatever happens here never touches it.
 */
class PackageLearningMerge(
    private val learning: PackageLearning,
    private val activeSourceId: () -> String?,
    private val activate: suspend (sourceId: String) -> Boolean,
    private val merge: suspend (List<StagedSnapshot>) -> RimeSnapshotMergeReport,
) {
    suspend fun accept(sourceId: String): PackageLearningOutcome {
        val snapshots = learning.pending(sourceId)
        if (snapshots.isEmpty()) {
            learning.discard(sourceId)
            return PackageLearningOutcome.Gone
        }
        if (activeSourceId() != sourceId && !activate(sourceId)) {
            // Kept: the user can use the package later and be asked again by importing it once more.
            return PackageLearningOutcome.NotInUse
        }
        return try {
            val report = merge(snapshots)
            if (report.merged.isEmpty()) {
                PackageLearningOutcome.NothingToMerge
            } else {
                PackageLearningOutcome.Merged(
                    report,
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            PackageLearningOutcome.Failed
        } finally {
            learning.discard(sourceId)
        }
    }
}
