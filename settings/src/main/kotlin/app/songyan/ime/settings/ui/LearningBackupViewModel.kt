package app.songyan.ime.settings.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import app.songyan.ime.settings.state.LearningBackupGateway
import app.songyan.ime.settings.state.LearningBackupHolder
import app.songyan.ime.settings.state.LearningBackupResult
import app.songyan.ime.settings.state.LearningBackupSummary
import app.songyan.ime.settings.state.LearningImportCheck
import app.songyan.ime.settings.state.LearningPrepared
import app.songyan.ime.settings.state.LearningSnapshotSummary
import app.songyan.ime.settings.state.QuarantinedLearningSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the last finished action tells the user. */
sealed interface LearningNotice {
    data object Saved : LearningNotice

    data object Merged : LearningNotice

    /** Set-aside learning data was merged back. */
    data object Restored : LearningNotice

    data object Deleted : LearningNotice

    /** Done, with what happened (already localized by the app module). */
    data class Report(
        val message: String,
    ) : LearningNotice

    data class Failure(
        /** Already localized by the app module. */
        val message: String,
    ) : LearningNotice
}

data class LearningBackupUiState(
    val loading: Boolean = true,
    val available: Boolean = true,
    /** The input scheme in use; null when nothing is published yet. */
    val sourceName: String? = null,
    val safetyBackup: LearningBackupSummary? = null,
    /** Automatic copies kept on this device, newest first. */
    val snapshots: List<LearningSnapshotSummary> = emptyList(),
    val busy: Boolean = false,
    /** A backup is ready: the screen opens the save picker with this file name once. */
    val saveFileName: String? = null,
    /** A picked backup passed its checks and waits for the user to confirm the merge. */
    val confirmMerge: LearningBackupSummary? = null,
    /**
     * Dictionary files from another Rime input method were read: what will merge and what will
     * not. Confirmed like [confirmMerge] when [filesCanMerge]; otherwise only acknowledged.
     */
    val confirmFiles: String? = null,
    val filesCanMerge: Boolean = false,
    /** The waiting merge reads set-aside learning data, so the dialog words it as a restore. */
    val confirmIsRestore: Boolean = false,
    /** Learning data set aside after the engine kept failing at start, newest first. */
    val quarantined: List<QuarantinedLearningSummary> = emptyList(),
    /** Set-aside learning data the user asked to delete and has not confirmed yet. */
    val confirmDelete: QuarantinedLearningSummary? = null,
    val notice: LearningNotice? = null,
)

/** Learning backup screen: save a backup, merge one, save the backup made before the last merge. */
class LearningBackupViewModel(
    private val gateway: LearningBackupGateway,
) : ViewModel() {
    private val mutable = MutableStateFlow(LearningBackupUiState(available = gateway.available))
    val uiState: StateFlow<LearningBackupUiState> = mutable.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val name = gateway.currentSourceName()
            val safety = gateway.safetyBackup()
            val snapshots = gateway.snapshots()
            val quarantined = gateway.quarantined()
            mutable.update {
                it.copy(
                    loading = false,
                    sourceName = name,
                    safetyBackup = safety,
                    snapshots = snapshots,
                    quarantined = quarantined,
                )
            }
        }
    }

    fun exportBackup() = prepare { gateway.prepareBackup() }

    fun exportSafetyBackup() = prepare { gateway.prepareSafetyBackup() }

    /** The save picker is on screen; it must not open a second time. */
    fun onSavePickerShown() {
        mutable.update { it.copy(saveFileName = null) }
    }

    /** The save picker returned; null means the user backed out. */
    fun onSaveTarget(uri: Uri?) {
        if (uri == null) {
            gateway.discardPrepared()
            return
        }
        run {
            when (val result = gateway.writePrepared(uri)) {
                LearningBackupResult.Success -> LearningNotice.Saved
                is LearningBackupResult.Reported -> LearningNotice.Report(result.message)
                is LearningBackupResult.Failure -> LearningNotice.Failure(result.message)
            }
        }
    }

    /** The open picker returned a backup or dictionary file to merge; null means the user backed out. */
    fun onImportPicked(uri: Uri?) {
        if (uri == null) return
        inspect { gateway.inspect(uri) }
    }

    /** The folder picker returned a folder of dictionary files; null means the user backed out. */
    fun onFolderPicked(uri: Uri?) {
        if (uri == null) return
        inspect { gateway.inspectFolder(uri) }
    }

    private fun inspect(check: suspend () -> LearningImportCheck) {
        start()
        viewModelScope.launch { showCheck(check()) }
    }

    private fun showCheck(check: LearningImportCheck) {
        when (check) {
            is LearningImportCheck.Ready -> mutable.update { it.copy(busy = false, confirmMerge = check.summary) }
            is LearningImportCheck.Files ->
                mutable.update {
                    it.copy(busy = false, confirmFiles = check.description, filesCanMerge = check.canMerge)
                }
            is LearningImportCheck.Failure ->
                mutable.update { it.copy(busy = false, notice = LearningNotice.Failure(check.message)) }
        }
    }

    /** Restores from an automatic copy: it is checked like a picked file, then waits for the same confirmation. */
    fun onSnapshotPicked(id: String) {
        inspect { gateway.inspectSnapshot(id) }
    }

    /** Restores set-aside learning data: it is read through the engine, then waits for the merge confirmation. */
    fun onQuarantinedRestore(id: String) {
        start()
        viewModelScope.launch {
            when (val check = gateway.inspectQuarantined(id)) {
                is LearningImportCheck.Ready ->
                    mutable.update { it.copy(busy = false, confirmMerge = check.summary, confirmIsRestore = true) }
                is LearningImportCheck.Files -> showCheck(check)
                is LearningImportCheck.Failure -> {
                    // A refusal can change what the list offers (unreadable, gone).
                    val quarantined = gateway.quarantined()
                    mutable.update {
                        it.copy(busy = false, quarantined = quarantined, notice = LearningNotice.Failure(check.message))
                    }
                }
            }
        }
    }

    fun onQuarantinedDelete(id: String) {
        val item = mutable.value.quarantined.firstOrNull { it.id == id } ?: return
        mutable.update { it.copy(confirmDelete = item) }
    }

    fun cancelDelete() {
        mutable.update { it.copy(confirmDelete = null) }
    }

    fun confirmDelete() {
        val item = mutable.value.confirmDelete ?: return
        mutable.update { it.copy(confirmDelete = null, busy = true, notice = null) }
        viewModelScope.launch {
            val notice =
                when (val result = gateway.deleteQuarantined(item.id)) {
                    LearningBackupResult.Success -> LearningNotice.Deleted
                    is LearningBackupResult.Reported -> LearningNotice.Report(result.message)
                    is LearningBackupResult.Failure -> LearningNotice.Failure(result.message)
                }
            val quarantined = gateway.quarantined()
            mutable.update { it.copy(busy = false, notice = notice, quarantined = quarantined) }
        }
    }

    fun confirmMerge() {
        val restore = mutable.value.confirmIsRestore
        mutable.update { it.closedConfirmation() }
        run {
            when (val result = gateway.mergeInspected()) {
                LearningBackupResult.Success -> if (restore) LearningNotice.Restored else LearningNotice.Merged
                is LearningBackupResult.Reported -> LearningNotice.Report(result.message)
                is LearningBackupResult.Failure -> LearningNotice.Failure(result.message)
            }
        }
    }

    fun cancelMerge() {
        gateway.discardInspected()
        mutable.update { it.closedConfirmation() }
    }

    private fun LearningBackupUiState.closedConfirmation() =
        copy(confirmMerge = null, confirmFiles = null, filesCanMerge = false, confirmIsRestore = false)

    fun consumeNotice() {
        mutable.update { it.copy(notice = null) }
    }

    private fun prepare(action: suspend () -> LearningPrepared) {
        start()
        viewModelScope.launch {
            when (val prepared = action()) {
                is LearningPrepared.Ready ->
                    mutable.update {
                        it.copy(
                            busy = false,
                            saveFileName = prepared.suggestedFileName,
                        )
                    }
                is LearningPrepared.Failure ->
                    mutable.update { it.copy(busy = false, notice = LearningNotice.Failure(prepared.message)) }
            }
        }
    }

    private fun run(action: suspend () -> LearningNotice) {
        start()
        viewModelScope.launch {
            val notice = action()
            val safety = gateway.safetyBackup()
            val quarantined = gateway.quarantined()
            mutable.update { it.copy(busy = false, notice = notice, safetyBackup = safety, quarantined = quarantined) }
        }
    }

    private fun start() {
        mutable.update { it.copy(busy = true, notice = null) }
    }

    class Factory : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(
            modelClass: Class<T>,
            extras: CreationExtras,
        ): T {
            @Suppress("UNCHECKED_CAST")
            return LearningBackupViewModel(LearningBackupHolder.current) as T
        }
    }
}
