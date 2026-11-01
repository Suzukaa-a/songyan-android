package app.songyan.ime.settings.state

import android.net.Uri

/** Where a learning backup came from and when it was made. */
data class LearningBackupSummary(
    /** The input scheme it was made from, as the backup names it. */
    val sourceName: String,
    val createdAtEpochMs: Long,
)

/** One automatic copy of the learning data kept on this device. */
data class LearningSnapshotSummary(
    /** Opaque; hand it back to [LearningBackupGateway.inspectSnapshot]. */
    val id: String,
    val summary: LearningBackupSummary,
)

/** Whether a set-aside learning set can be restored now. */
enum class QuarantinedAvailability {
    /** Its words can be merged into the input scheme in use. */
    READY,

    /** It belongs to another input scheme; that one has to be in use first. */
    NEEDS_SOURCE,

    /** The engine cannot read it safely; it is kept as it is and can only be deleted. */
    UNREADABLE,

    /** Its words were merged back already. */
    RESTORED,
}

/** Learning data the keyboard moved aside after it kept closing unexpectedly at start. */
data class QuarantinedLearningSummary(
    /** Opaque; hand it back to the gateway. */
    val id: String,
    val timeEpochMs: Long,
    /** The input scheme the data was learned in. */
    val sourceName: String,
    val availability: QuarantinedAvailability,
)

/** A backup ready to be saved: the user picks where, then [LearningBackupGateway.writePrepared] writes it. */
sealed interface LearningPrepared {
    data class Ready(
        val suggestedFileName: String,
    ) : LearningPrepared

    data class Failure(
        /** Already localized. */
        val message: String,
    ) : LearningPrepared
}

/** A picked backup file, checked and waiting for the user to confirm the merge. */
sealed interface LearningImportCheck {
    data class Ready(
        val summary: LearningBackupSummary,
    ) : LearningImportCheck

    data class Failure(
        /** Already localized. */
        val message: String,
    ) : LearningImportCheck

    /**
     * Dictionary files from another Rime input method. [description] (already localized) says
     * what will merge and which files will not, and why; with [canMerge] false nothing would
     * merge, so there is nothing to confirm.
     */
    data class Files(
        val description: String,
        val canMerge: Boolean,
    ) : LearningImportCheck
}

sealed interface LearningBackupResult {
    data object Success : LearningBackupResult

    /** Done, with what was merged (already localized). */
    data class Reported(
        val message: String,
    ) : LearningBackupResult

    data class Failure(
        /** Already localized. */
        val message: String,
    ) : LearningBackupResult
}

/**
 * Bridge the settings UI uses for learning backups of the input scheme in use. The work runs in
 * this (the input method's) process with the keyboard's engine set aside meanwhile; every call
 * returns when it is done.
 */
interface LearningBackupGateway {
    val available: Boolean

    /** Display name of the input scheme in use, or null before anything is published. */
    suspend fun currentSourceName(): String?

    /** The backup saved automatically before the last merge, or null when there is none. */
    suspend fun safetyBackup(): LearningBackupSummary?

    /** Reads the learning data of the input scheme in use into a backup waiting to be saved. */
    suspend fun prepareBackup(): LearningPrepared

    /** Picks up the backup saved before the last merge, waiting to be saved. */
    suspend fun prepareSafetyBackup(): LearningPrepared

    /** Writes the waiting backup to [uri] (from the system's create-document picker). */
    suspend fun writePrepared(uri: Uri): LearningBackupResult

    fun discardPrepared()

    /**
     * Reads and checks the picked file: a Songyan learning backup, or dictionary files from
     * another Rime input method (one file or a ZIP). It then waits for [mergeInspected].
     */
    suspend fun inspect(uri: Uri): LearningImportCheck

    /** Reads the dictionary files in a picked folder (from the system's open-tree picker), like [inspect]. */
    suspend fun inspectFolder(uri: Uri): LearningImportCheck = LearningImportCheck.Failure("")

    /** Merges the backup [inspect] accepted into the learning data of the input scheme in use. */
    suspend fun mergeInspected(): LearningBackupResult

    fun discardInspected()

    /** The automatic copies kept on this device (a few, newest first). */
    suspend fun snapshots(): List<LearningSnapshotSummary> = emptyList()

    /** Checks the automatic copy [id] like a picked file; it then waits for [mergeInspected]. */
    suspend fun inspectSnapshot(id: String): LearningImportCheck = LearningImportCheck.Failure("")

    /** Learning data set aside after repeated engine start failures, newest first. */
    suspend fun quarantined(): List<QuarantinedLearningSummary> = emptyList()

    /**
     * Reads the set-aside learning data [id] through a temporary engine into a backup that waits
     * for [mergeInspected]; the set-aside data itself is never put back as it is. The summary's
     * time is when the data was set aside.
     */
    suspend fun inspectQuarantined(id: String): LearningImportCheck = LearningImportCheck.Failure("")

    /** Deletes the set-aside learning data [id]. */
    suspend fun deleteQuarantined(id: String): LearningBackupResult = LearningBackupResult.Failure("")
}

class UnavailableLearningBackupGateway : LearningBackupGateway {
    override val available: Boolean = false

    override suspend fun currentSourceName(): String? = null

    override suspend fun safetyBackup(): LearningBackupSummary? = null

    override suspend fun prepareBackup(): LearningPrepared = LearningPrepared.Failure("")

    override suspend fun prepareSafetyBackup(): LearningPrepared = LearningPrepared.Failure("")

    override suspend fun writePrepared(uri: Uri): LearningBackupResult = LearningBackupResult.Failure("")

    override fun discardPrepared() = Unit

    override suspend fun inspect(uri: Uri): LearningImportCheck = LearningImportCheck.Failure("")

    override suspend fun mergeInspected(): LearningBackupResult = LearningBackupResult.Failure("")

    override fun discardInspected() = Unit
}

/** Process-wide holder; the app module installs its implementation once during Application creation. */
object LearningBackupHolder {
    private val fallback = UnavailableLearningBackupGateway()

    @Volatile
    private var installed: LearningBackupGateway? = null

    val current: LearningBackupGateway
        get() = installed ?: fallback

    fun install(gateway: LearningBackupGateway) {
        installed = gateway
    }
}
