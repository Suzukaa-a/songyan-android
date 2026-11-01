package app.songyan.ime.settings.ui.screens

import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.songyan.ime.settings.R
import app.songyan.ime.settings.state.LearningBackupSummary
import app.songyan.ime.settings.state.QuarantinedAvailability
import app.songyan.ime.settings.state.QuarantinedLearningSummary
import app.songyan.ime.settings.ui.LearningBackupViewModel
import app.songyan.ime.settings.ui.LearningNotice
import app.songyan.ime.settings.ui.components.ImeDialog
import app.songyan.ime.settings.ui.components.SectionHeader
import app.songyan.ime.settings.ui.components.SettingsRow
import app.songyan.ime.settings.ui.components.SettingsScaffold
import java.util.Date

/**
 * MIME types a backup or a Rime dictionary file (a text snapshot or a ZIP of them) may carry
 * depending on where it was saved or shared from.
 */
private val BACKUP_TYPES =
    arrayOf("application/json", "application/octet-stream", "text/*", "application/zip", "application/x-zip-compressed")

/**
 * 学习备份: save what the input scheme in use has learned to a file, merge such a file (from this
 * device or from Songyan on iOS) or the dictionary files of another Rime input method into it, and
 * save the backup made automatically before the last merge. Files go through the system document
 * pickers.
 */
@Composable
fun LearningBackupScreen(
    onBack: (() -> Unit)?,
    viewModel: LearningBackupViewModel = viewModel(factory = LearningBackupViewModel.Factory()),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val saver =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            viewModel.onSaveTarget(uri)
        }
    val opener =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            viewModel.onImportPicked(uri)
        }
    val folderOpener =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            viewModel.onFolderPicked(uri)
        }
    LaunchedEffect(state.saveFileName) {
        val name = state.saveFileName ?: return@LaunchedEffect
        viewModel.onSavePickerShown()
        saver.launch(name)
    }
    val snackbar = remember { SnackbarHostState() }
    val saved = stringResource(R.string.learning_saved)
    val merged = stringResource(R.string.learning_merged)
    val restored = stringResource(R.string.quarantine_restored)
    val deleted = stringResource(R.string.quarantine_deleted)
    LaunchedEffect(state.notice) {
        val text =
            when (val notice = state.notice) {
                null -> return@LaunchedEffect
                LearningNotice.Saved -> saved
                LearningNotice.Merged -> merged
                LearningNotice.Restored -> restored
                LearningNotice.Deleted -> deleted
                is LearningNotice.Report -> notice.message
                is LearningNotice.Failure -> notice.message
            }
        viewModel.consumeNotice()
        snackbar.showSnackbar(text)
    }

    SettingsScaffold(
        title = stringResource(R.string.learning_title),
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp),
        ) {
            val sourceName = state.sourceName
            Text(
                text =
                    when {
                        state.loading -> stringResource(R.string.learning_loading)
                        sourceName == null -> stringResource(R.string.learning_no_source)
                        else -> stringResource(R.string.learning_current, sourceName)
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (state.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            }
            val enabled = state.available && !state.busy && !state.loading && sourceName != null
            SectionHeader(stringResource(R.string.learning_section))
            SettingsRow(
                title = stringResource(R.string.learning_export_title),
                subtitle = stringResource(R.string.learning_export_subtitle),
                icon = Icons.Outlined.FileUpload,
                onClick = { if (enabled) viewModel.exportBackup() },
            )
            SettingsRow(
                title = stringResource(R.string.learning_import_title),
                subtitle = stringResource(R.string.learning_import_subtitle),
                icon = Icons.Outlined.FileDownload,
                onClick = { if (enabled) opener.launch(BACKUP_TYPES) },
            )
            SettingsRow(
                title = stringResource(R.string.learning_import_folder_title),
                subtitle = stringResource(R.string.learning_import_folder_subtitle),
                icon = Icons.Outlined.FolderOpen,
                onClick = { if (enabled) folderOpener.launch(null) },
            )
            state.safetyBackup?.let { safety ->
                SettingsRow(
                    title = stringResource(R.string.learning_safety_title),
                    subtitle = describe(safety),
                    icon = Icons.Outlined.History,
                    onClick = { if (state.available && !state.busy) viewModel.exportSafetyBackup() },
                )
            }
            if (state.snapshots.isNotEmpty()) {
                SectionHeader(stringResource(R.string.learning_snapshots_section))
                state.snapshots.forEach { snapshot ->
                    SettingsRow(
                        title = stringResource(R.string.learning_snapshot_title),
                        subtitle = describe(snapshot.summary),
                        icon = Icons.Outlined.History,
                        onClick = { if (enabled) viewModel.onSnapshotPicked(snapshot.id) },
                    )
                }
            }
            if (state.quarantined.isNotEmpty()) {
                SectionHeader(stringResource(R.string.quarantine_section))
                Text(
                    text = stringResource(R.string.quarantine_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                state.quarantined.forEach { item ->
                    QuarantinedRow(
                        item = item,
                        enabled = state.available && !state.busy && !state.loading,
                        onRestore = { viewModel.onQuarantinedRestore(item.id) },
                        onDelete = { viewModel.onQuarantinedDelete(item.id) },
                    )
                }
            }
            Text(
                text = stringResource(R.string.learning_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }

    state.confirmMerge?.let { summary ->
        ImeDialog(
            onDismissRequest = viewModel::cancelMerge,
            title =
                stringResource(
                    if (state.confirmIsRestore) R.string.quarantine_restore_title else R.string.learning_confirm_title,
                ),
            confirmButton = {
                TextButton(onClick = viewModel::confirmMerge) {
                    val restoring = state.confirmIsRestore
                    val label = if (restoring) R.string.quarantine_restore else R.string.learning_confirm_merge
                    Text(stringResource(label))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelMerge) { Text(stringResource(R.string.learning_cancel)) }
            },
        ) {
            Text(describe(summary), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(
                    if (state.confirmIsRestore) R.string.quarantine_restore_body else R.string.learning_confirm_body,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    state.confirmFiles?.let { description ->
        val canMerge = state.filesCanMerge
        ImeDialog(
            onDismissRequest = viewModel::cancelMerge,
            title =
                stringResource(
                    if (canMerge) R.string.learning_files_title else R.string.learning_files_nothing_title,
                ),
            confirmButton = {
                if (canMerge) {
                    TextButton(
                        onClick = viewModel::confirmMerge,
                    ) { Text(stringResource(R.string.learning_confirm_merge)) }
                } else {
                    TextButton(onClick = viewModel::cancelMerge) { Text(stringResource(R.string.learning_files_ok)) }
                }
            },
            dismissButton = {
                if (canMerge) {
                    TextButton(onClick = viewModel::cancelMerge) { Text(stringResource(R.string.learning_cancel)) }
                }
            },
        ) {
            Text(description, style = MaterialTheme.typography.bodyMedium)
            if (canMerge) {
                Text(
                    stringResource(R.string.learning_confirm_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    state.confirmDelete?.let { item ->
        ImeDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = stringResource(R.string.quarantine_delete_title),
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) { Text(stringResource(R.string.quarantine_delete)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDelete) { Text(stringResource(R.string.learning_cancel)) }
            },
        ) {
            Text(describe(item.sourceName, item.timeEpochMs), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.quarantine_delete_body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** One set-aside learning set: when and for which input scheme, what can be done now, and the two actions. */
@Composable
private fun QuarantinedRow(
    item: QuarantinedLearningSummary,
    enabled: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val time =
        remember(item.timeEpochMs) {
            val date = Date(item.timeEpochMs)
            DateFormat.getMediumDateFormat(context).format(date) + " " + DateFormat.getTimeFormat(context).format(date)
        }
    val status =
        when (item.availability) {
            QuarantinedAvailability.READY -> stringResource(R.string.quarantine_status_ready, item.sourceName)
            QuarantinedAvailability.NEEDS_SOURCE ->
                stringResource(
                    R.string.quarantine_status_needs_source,
                    item.sourceName,
                )
            QuarantinedAvailability.UNREADABLE -> stringResource(R.string.quarantine_status_unreadable, item.sourceName)
            QuarantinedAvailability.RESTORED -> stringResource(R.string.quarantine_status_restored, item.sourceName)
        }
    val restoreLabel = stringResource(R.string.quarantine_restore_description, time)
    val deleteLabel = stringResource(R.string.quarantine_delete_description, time)
    ListItem(
        headlineContent = { Text(time) },
        supportingContent = {
            Column {
                Text(status)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.availability == QuarantinedAvailability.READY) {
                        TextButton(
                            onClick = onRestore,
                            enabled = enabled,
                            modifier = Modifier.semantics { contentDescription = restoreLabel },
                        ) { Text(stringResource(R.string.quarantine_restore)) }
                    }
                    TextButton(
                        onClick = onDelete,
                        enabled = enabled,
                        modifier = Modifier.semantics { contentDescription = deleteLabel },
                    ) { Text(stringResource(R.string.quarantine_delete)) }
                }
            }
        },
        leadingContent = { Icon(imageVector = Icons.Outlined.Restore, contentDescription = null) },
    )
}

/** "<source> · <date and time>" for a set-aside set. */
@Composable
private fun describe(
    sourceName: String,
    epochMs: Long,
): String = describe(LearningBackupSummary(sourceName, epochMs))

/** "<source> · <date and time>", in the device's date format. */
@Composable
private fun describe(summary: LearningBackupSummary): String {
    val context = LocalContext.current
    val date = Date(summary.createdAtEpochMs)
    val formatted =
        DateFormat.getMediumDateFormat(context).format(date) + " " + DateFormat.getTimeFormat(context).format(date)
    return stringResource(R.string.learning_backup_detail, summary.sourceName, formatted)
}
