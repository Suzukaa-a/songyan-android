package app.songyan.ime.content

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.songyan.ime.R
import app.songyan.ime.learning.LearningBackupService
import app.songyan.ime.learning.LearningSnapshots
import app.songyan.ime.learning.LearningTransferException
import app.songyan.ime.learning.LearningTransferProblem
import app.songyan.ime.learning.PickedFile
import app.songyan.ime.learning.QuarantineState
import app.songyan.ime.learning.RimeSnapshotCollector
import app.songyan.ime.learning.RimeSnapshotMergeReport
import app.songyan.ime.learning.RimeSnapshotPlan
import app.songyan.ime.learning.SkippedSnapshot
import app.songyan.ime.learning.SnapshotSkipReason
import app.songyan.ime.settings.state.LearningBackupGateway
import app.songyan.ime.settings.state.LearningBackupResult
import app.songyan.ime.settings.state.LearningBackupSummary
import app.songyan.ime.settings.state.LearningImportCheck
import app.songyan.ime.settings.state.LearningPrepared
import app.songyan.ime.settings.state.LearningSnapshotSummary
import app.songyan.ime.settings.state.QuarantinedAvailability
import app.songyan.ime.settings.state.QuarantinedLearningSummary
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.LearningData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Learning backups from the settings screen: files come from and go to the system document
 * pickers, the work itself is [LearningBackupService]'s. Messages name no dictionary or entry.
 */
internal class LearningBackupGatewayImpl(
    context: Context,
    private val service: LearningBackupService,
    private val publisher: GenerationPublisher,
    private val sourceName: (sourceId: String) -> String,
    private val snapshots: LearningSnapshots? = null,
) : LearningBackupGateway {
    private val appContext = context.applicationContext

    /** Deletes a discarded import's staged copies off the caller's (main) thread. */
    private val discardScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var prepared: ByteArray? = null

    @Volatile
    private var inspected: LearningBackupArchive? = null

    /** The set-aside learning set [inspected] was read from, when it was. */
    @Volatile
    private var inspectedQuarantine: String? = null

    /** Rime dictionary files from another input method, staged and waiting for the merge. */
    @Volatile
    private var inspectedPlan: RimeSnapshotPlan? = null

    override val available: Boolean = true

    override suspend fun currentSourceName(): String? =
        withContext(Dispatchers.IO) { publisher.readActive()?.let { sourceName(it.sourceId) } }

    override suspend fun safetyBackup(): LearningBackupSummary? = service.safetyBackup()?.summary()

    override suspend fun prepareBackup(): LearningPrepared =
        try {
            val archive = service.backup()
            prepared = withContext(Dispatchers.IO) { archive.encoded() }
            LearningPrepared.Ready(appContext.getString(R.string.learning_backup_file_name, day(archive)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LearningPrepared.Failure(backupMessage(e))
        }

    override suspend fun prepareSafetyBackup(): LearningPrepared {
        val archive =
            service.safetyBackup()
                ?: return LearningPrepared.Failure(appContext.getString(R.string.learning_error_no_safety_backup))
        prepared = withContext(Dispatchers.IO) { archive.encoded() }
        return LearningPrepared.Ready(appContext.getString(R.string.learning_safety_file_name, day(archive)))
    }

    override suspend fun writePrepared(uri: Uri): LearningBackupResult {
        val bytes = prepared ?: return LearningBackupResult.Failure(appContext.getString(R.string.learning_error_write))
        return try {
            withContext(Dispatchers.IO) {
                // "wt" truncates: a provider that reuses an existing file must not keep its old tail.
                val stream = appContext.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("no stream")
                stream.use { it.write(bytes) }
            }
            prepared = null
            LearningBackupResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            LearningBackupResult.Failure(appContext.getString(R.string.learning_error_write))
        }
    }

    override fun discardPrepared() {
        prepared = null
    }

    override suspend fun snapshots(): List<LearningSnapshotSummary> =
        snapshots?.list().orEmpty().map { LearningSnapshotSummary(it.id, it.archive.summary()) }

    override suspend fun quarantined(): List<QuarantinedLearningSummary> =
        withContext(Dispatchers.IO) {
            val activeNamespace = publisher.readActive()?.let { LearningData.namespace(it.sourceId) }
            service.quarantined().map {
                QuarantinedLearningSummary(
                    id = it.id,
                    timeEpochMs = it.record.timeEpochMs,
                    sourceName = sourceName(it.record.sourceId),
                    availability =
                        when {
                            it.state == QuarantineState.UNREADABLE -> QuarantinedAvailability.UNREADABLE
                            it.state == QuarantineState.RESTORED -> QuarantinedAvailability.RESTORED
                            it.namespace != activeNamespace -> QuarantinedAvailability.NEEDS_SOURCE
                            else -> QuarantinedAvailability.READY
                        },
                )
            }
        }

    override suspend fun inspectQuarantined(id: String): LearningImportCheck {
        clearInspected()
        return try {
            val archive = service.inspectQuarantined(id)
            val time =
                service
                    .quarantined()
                    .firstOrNull { it.id == id }
                    ?.record
                    ?.timeEpochMs
                    ?: archive.createdAt.toEpochMilli()
            inspected = archive
            inspectedQuarantine = id
            LearningImportCheck.Ready(LearningBackupSummary(archive.sourceName, time))
        } catch (e: LearningTransferException) {
            LearningImportCheck.Failure(mergeMessage(e.problem))
        }
    }

    override suspend fun deleteQuarantined(id: String): LearningBackupResult =
        if (service.deleteQuarantined(id)) {
            LearningBackupResult.Success
        } else {
            LearningBackupResult.Failure(appContext.getString(R.string.learning_error_delete))
        }

    override suspend fun inspectSnapshot(id: String): LearningImportCheck {
        clearInspected()
        val bytes =
            snapshots?.read(id)
                ?: return LearningImportCheck.Failure(appContext.getString(R.string.learning_error_read))
        return inspectBytes(bytes)
    }

    override suspend fun inspect(uri: Uri): LearningImportCheck {
        clearInspected()
        val archive =
            try {
                withContext(Dispatchers.IO) { isArchive(uri) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return LearningImportCheck.Failure(appContext.getString(R.string.learning_error_read))
        if (!archive) {
            val name = withContext(Dispatchers.IO) { displayName(uri) }
            return inspectSnapshots(byContent = true) { listOf(PickedFile(name) { open(uri) }) }
        }
        val bytes =
            try {
                withContext(Dispatchers.IO) { readBounded(uri) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return LearningImportCheck.Failure(appContext.getString(R.string.learning_error_read))
        return inspectBytes(bytes)
    }

    override suspend fun inspectFolder(uri: Uri): LearningImportCheck {
        clearInspected()
        return inspectSnapshots(
            byContent = false,
        ) { RimeSnapshotCollector.filesIn(DocumentTreeNode.root(appContext, uri)) }
    }

    /** Stages the Rime snapshots [files] lists and describes what the merge would do. */
    private suspend fun inspectSnapshots(
        byContent: Boolean,
        files: () -> List<PickedFile>,
    ): LearningImportCheck {
        val plan =
            try {
                val picked = withContext(Dispatchers.IO) { files() }
                service.inspectRimeSnapshots(picked, byContent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RimeSnapshotCollector.TooLarge) {
                return LearningImportCheck.Failure(appContext.getString(R.string.learning_error_too_large))
            } catch (e: LearningTransferException) {
                return LearningImportCheck.Failure(mergeMessage(e.problem))
            } catch (_: Exception) {
                return LearningImportCheck.Failure(appContext.getString(R.string.learning_error_read))
            }
        if (plan.merges.isEmpty()) {
            service.discardRimeSnapshots(plan)
            if (plan.skipped.isEmpty()) {
                return LearningImportCheck.Failure(appContext.getString(R.string.learning_files_none_found))
            }
            return LearningImportCheck.Files(describeNothing(plan.skipped), canMerge = false)
        }
        inspectedPlan = plan
        val schemeName = withContext(Dispatchers.IO) { currentName() }
        return LearningImportCheck.Files(describePlan(plan, schemeName), canMerge = true)
    }

    private fun clearInspected() {
        inspected = null
        inspectedQuarantine = null
        inspectedPlan = null
    }

    private suspend fun inspectBytes(bytes: ByteArray): LearningImportCheck =
        try {
            val archive = service.inspect(bytes)
            inspected = archive
            LearningImportCheck.Ready(archive.summary())
        } catch (e: LearningTransferException) {
            LearningImportCheck.Failure(mergeMessage(e.problem))
        }

    override suspend fun mergeInspected(): LearningBackupResult {
        inspectedPlan?.let { return mergeSnapshots(it) }
        val archive =
            inspected ?: return LearningBackupResult.Failure(appContext.getString(R.string.learning_error_read))
        return try {
            service.merge(archive, inspectedQuarantine)
            inspected = null
            inspectedQuarantine = null
            LearningBackupResult.Success
        } catch (e: LearningTransferException) {
            LearningBackupResult.Failure(mergeMessage(e.problem))
        }
    }

    private suspend fun mergeSnapshots(plan: RimeSnapshotPlan): LearningBackupResult =
        try {
            val report = service.mergeRimeSnapshots(plan.merges)
            if (report.merged.isEmpty()) {
                LearningBackupResult.Failure(describeNothing(plan.skipped + report.skipped))
            } else {
                LearningBackupResult.Reported(describeMerged(report, plan.skipped.size + report.skipped.size))
            }
        } catch (e: LearningTransferException) {
            LearningBackupResult.Failure(mergeMessage(e.problem))
        } finally {
            inspectedPlan = null
            service.discardRimeSnapshots(plan)
        }

    private fun describeMerged(
        report: RimeSnapshotMergeReport,
        skipped: Int,
    ): String {
        val merged = LearningFilesText.merged(appContext, report.dictionaryCount, report.entryCount)
        if (skipped == 0) return merged
        return merged + appContext.resources.getQuantityString(R.plurals.learning_files_not_merged, skipped, skipped)
    }

    override fun discardInspected() {
        inspected = null
        inspectedQuarantine = null
        val plan = inspectedPlan ?: return
        inspectedPlan = null
        discardScope.launch { service.discardRimeSnapshots(plan) }
    }

    private fun describePlan(
        plan: RimeSnapshotPlan,
        schemeName: String,
    ): String {
        val merge =
            appContext.getString(
                R.string.learning_files_merge,
                LearningFilesText.dictionaries(appContext, plan.dictionaryCount),
                LearningFilesText.entries(appContext, plan.entryCount),
                schemeName,
            )
        if (plan.skipped.isEmpty()) return merge
        return merge + "\n\n" + skippedLines(plan.skipped)
    }

    private fun describeNothing(skipped: List<SkippedSnapshot>): String {
        val parts = mutableListOf(appContext.getString(R.string.learning_files_nothing), skippedLines(skipped))
        if (skipped.any { it.reason == SnapshotSkipReason.NOT_INSTALLED }) {
            parts += appContext.getString(R.string.learning_files_other_scheme_hint)
        }
        return parts.joinToString("\n\n")
    }

    private fun skippedLines(skipped: List<SkippedSnapshot>): String =
        (
            listOf(appContext.getString(R.string.learning_files_skipped_header)) +
                skipped.map {
                    appContext.getString(
                        when (it.reason) {
                            SnapshotSkipReason.NOT_A_SNAPSHOT -> R.string.learning_files_skip_invalid
                            SnapshotSkipReason.TOO_LARGE -> R.string.learning_files_skip_too_large
                            SnapshotSkipReason.EMPTY -> R.string.learning_files_skip_empty
                            SnapshotSkipReason.NOT_INSTALLED -> R.string.learning_files_skip_not_installed
                        },
                        it.fileName,
                    )
                }
        ).joinToString("\n")

    private fun currentName(): String = publisher.readActive()?.let { sourceName(it.sourceId) }.orEmpty()

    /** Whether the picked file is a Songyan learning backup (JSON) rather than Rime dictionary files. */
    private fun isArchive(uri: Uri): Boolean? {
        val input = appContext.contentResolver.openInputStream(uri) ?: return null
        return input.use { stream ->
            var byte = stream.read()
            // A UTF-8 BOM and whitespace may precede the JSON object.
            while (byte == 0xEF || byte == 0xBB || byte == 0xBF || (byte >= 0 && Character.isWhitespace(byte))) {
                byte = stream.read()
            }
            // An empty file goes to the backup decoder, which explains it as not a backup.
            byte < 0 || byte == '{'.code
        }
    }

    private fun open(uri: Uri): InputStream =
        appContext.contentResolver.openInputStream(uri) ?: throw IOException("no stream")

    private fun displayName(uri: Uri): String {
        val queried =
            try {
                appContext.contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
            } catch (_: Exception) {
                null
            }
        return queried ?: uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
    }

    /** Reads at most one byte past the archive limit, so an oversized pick is refused without loading it. */
    private fun readBounded(uri: Uri): ByteArray? {
        val limit = LearningBackupArchive.MAXIMUM_ENCODED_BYTES
        val input = appContext.contentResolver.openInputStream(uri) ?: return null
        return input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                // Past the limit the decoder refuses it as too large; nothing more is needed.
                if (out.size() > limit) break
            }
            out.toByteArray()
        }
    }

    private fun backupMessage(error: Exception): String =
        when ((error as? LearningTransferException)?.problem) {
            LearningTransferProblem.NO_SOURCE -> appContext.getString(R.string.learning_error_no_source)
            LearningTransferProblem.NOTHING_TO_BACK_UP -> appContext.getString(R.string.learning_error_nothing)
            else -> appContext.getString(R.string.learning_error_backup)
        }

    private fun mergeMessage(problem: LearningTransferProblem): String =
        appContext.getString(
            when (problem) {
                LearningTransferProblem.NO_SOURCE -> R.string.learning_error_no_source
                LearningTransferProblem.INVALID_ARCHIVE -> R.string.learning_error_invalid
                LearningTransferProblem.OTHER_SOURCE -> R.string.learning_error_other_source
                LearningTransferProblem.SAFETY_BACKUP_FAILED -> R.string.learning_error_safety
                LearningTransferProblem.QUARANTINE_GONE -> R.string.learning_error_quarantine_gone
                LearningTransferProblem.QUARANTINE_UNREADABLE -> R.string.learning_error_quarantine_unreadable
                LearningTransferProblem.QUARANTINE_NOT_IN_USE -> R.string.learning_error_quarantine_not_in_use
                LearningTransferProblem.QUARANTINE_EMPTY -> R.string.learning_error_quarantine_empty
                LearningTransferProblem.TOO_LARGE -> R.string.learning_error_too_large
                LearningTransferProblem.NOTHING_TO_BACK_UP,
                LearningTransferProblem.FAILED,
                -> R.string.learning_error_merge
            },
        )

    private fun LearningBackupArchive.summary() = LearningBackupSummary(sourceName, createdAt.toEpochMilli())

    private fun day(archive: LearningBackupArchive): String =
        DateTimeFormatter
            .ofPattern(
                "yyyy-MM-dd",
                Locale.ROOT,
            ).withZone(ZoneId.systemDefault())
            .format(archive.createdAt)

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
    }
}
