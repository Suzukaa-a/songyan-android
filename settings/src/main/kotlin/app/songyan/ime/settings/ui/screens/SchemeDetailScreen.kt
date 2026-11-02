package app.songyan.ime.settings.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.songyan.ime.settings.R
import app.songyan.ime.settings.state.StoredSourceInfo
import app.songyan.ime.settings.state.StoredSourceKind
import app.songyan.ime.settings.state.StoredSourceStatus
import app.songyan.ime.settings.ui.DictionaryImportUiState
import app.songyan.ime.settings.ui.SchemeImportUiState
import app.songyan.ime.settings.ui.StoredSourceUiState
import app.songyan.ime.settings.ui.components.GrammarModelCard
import app.songyan.ime.settings.ui.components.ImportKind
import app.songyan.ime.settings.ui.components.ImportStatusCard
import app.songyan.ime.settings.ui.components.RadioRow
import app.songyan.ime.settings.ui.components.SectionHeader
import app.songyan.ime.settings.ui.components.SettingsScaffold
import app.songyan.ime.settings.ui.components.StateMessage
import app.songyan.ime.settings.ui.components.importCardOf

/**
 * One stored input scheme: its status, the input methods it contains, where it came from and how to
 * update it, and the ways to stop using or delete it. The list page only names the packages; every
 * action on a single package lives here. Deleting the package leaves the page, so the list shows the
 * undo offer.
 */
@Composable
fun SchemeDetailScreen(
    sourceId: String,
    sources: List<StoredSourceInfo>,
    sourceAction: StoredSourceUiState,
    schemeImport: SchemeImportUiState,
    dictionaryImport: DictionaryImportUiState,
    onUse: (String) -> Unit,
    onUpdate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismissSourceAction: () -> Unit,
    onCancelImport: (ImportKind) -> Unit,
    onRetryImport: (ImportKind) -> Unit,
    onDismissSchemeImport: () -> Unit,
    onAnswerSchemeLearning: (Boolean) -> Unit,
    onBack: (() -> Unit)?,
    onSelectMethod: (String) -> Unit = {},
    onImportScheme: (Uri) -> Unit = {},
    grammarModel: GrammarModelCardInput? = null,
) {
    val schemePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onImportScheme(uri)
        }
    val grammarPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) grammarModel?.onImportFile?.invoke(uri)
        }
    val source = sources.firstOrNull { it.id == sourceId }
    // A package that was here and is gone was deleted: there is nothing left to show.
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(source != null) {
        if (source != null) {
            seen = true
        } else if (seen) {
            onBack?.invoke()
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val failure =
        (sourceAction as? StoredSourceUiState.Failed)?.let {
            it.message ?: stringResource(R.string.schemes_source_failed)
        }
    LaunchedEffect(failure) {
        if (failure != null) {
            snackbarHostState.showSnackbar(failure)
            onDismissSourceAction()
        }
    }
    val working = (sourceAction as? StoredSourceUiState.Running)?.sourceId == sourceId
    // One deploy at a time: any running request keeps every action here disabled.
    val idle =
        sourceAction !is StoredSourceUiState.Running &&
            schemeImport !is SchemeImportUiState.Running &&
            schemeImport !is SchemeImportUiState.MergingLearning &&
            dictionaryImport !is DictionaryImportUiState.Running
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var showModels by rememberSaveable(sourceId) { mutableStateOf(false) }

    SettingsScaffold(
        title = source?.name ?: stringResource(R.string.row_schemes_title),
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        if (source == null) {
            StateMessage(
                title = stringResource(R.string.schemes_detail_not_found),
                icon = Icons.Outlined.Book,
                modifier = Modifier.padding(innerPadding),
            )
        } else {
            val card = importCardOf(DictionaryImportUiState.Idle, schemeImport.forSource(sourceId))
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                if (card != null) {
                    item(key = "import_card") {
                        ImportStatusCard(
                            card = card,
                            onCancel = { onCancelImport(card.kind) },
                            onRetry = { onRetryImport(card.kind) },
                            onDismiss = onDismissSchemeImport,
                            onAnswerOffer = onAnswerSchemeLearning,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                statusItems(source, working, idle, onUse)
                methodItems(source, idle, onSelectMethod)
                if (source.kind == StoredSourceKind.SCHEME && source.usable) {
                    updateItems(source, idle, onUpdate) { schemePicker.launch(arrayOf("*/*")) }
                }
                if (grammarModel != null && source.requiredModels.isNotEmpty()) {
                    item(key = "models_entry") {
                        app.songyan.ime.settings.ui.components.SettingsRow(
                            title = stringResource(R.string.schemes_detail_models),
                            subtitle = stringResource(R.string.schemes_grammar_needed),
                            onClick = { showModels = !showModels },
                        )
                    }
                }
                if (grammarModel != null &&
                    ((showModels && source.requiredModels.isNotEmpty()) || grammarModel.state.grammarModelImported)
                ) {
                    item(key = "required_models") {
                        GrammarModelCard(
                            paths = source.requiredModels,
                            state = grammarModel.state,
                            actions =
                                grammarModel.actions.copy(
                                    onPickFile = { grammarPicker.launch(arrayOf("*/*")) },
                                    onDismiss = {
                                        showModels = false
                                        grammarModel.actions.onDismiss()
                                    },
                                ),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                manageItems(source, sources, idle, onUse, onDelete = { confirmDelete = true })
            }
        }
    }

    if (confirmDelete && source != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.schemes_source_delete_title, source.name)) },
            text = { Text(stringResource(R.string.schemes_source_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete(source.id)
                    },
                ) {
                    Text(stringResource(R.string.schemes_source_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.schemes_dictionary_cancel))
                }
            },
        )
    }
}

/** Only this package's own results show here; the result card of another package belongs to the list. */
private fun SchemeImportUiState.forSource(sourceId: String): SchemeImportUiState {
    val reported =
        when (this) {
            is SchemeImportUiState.Succeeded -> info?.sourceId
            is SchemeImportUiState.UpToDate -> info?.sourceId
            else -> null
        }
    return if (reported != null && reported != sourceId) SchemeImportUiState.Idle else this
}

private fun LazyListScope.statusItems(
    source: StoredSourceInfo,
    working: Boolean,
    idle: Boolean,
    onUse: (String) -> Unit,
) {
    item { SectionHeader(stringResource(R.string.schemes_detail_status_section)) }
    item(key = "status") {
        val status =
            when {
                working -> stringResource(R.string.schemes_source_working)
                source.status == StoredSourceStatus.DAMAGED -> stringResource(R.string.schemes_status_damaged)
                source.status == StoredSourceStatus.UPDATE_AVAILABLE && source.inUse ->
                    stringResource(R.string.schemes_status_in_use) + " · " +
                        stringResource(R.string.schemes_status_update_available)
                source.status == StoredSourceStatus.UPDATE_AVAILABLE ->
                    stringResource(R.string.schemes_status_update_available)
                source.inUse -> stringResource(R.string.schemes_status_in_use)
                else -> stringResource(R.string.schemes_status_not_in_use)
            }
        ListItem(
            headlineContent = { Text(status) },
            supportingContent =
                if (source.status == StoredSourceStatus.DAMAGED) {
                    { Text(stringResource(R.string.schemes_source_damaged_note)) }
                } else {
                    null
                },
        )
    }
    val useLabel =
        when {
            !source.usable -> null
            source.status == StoredSourceStatus.UPDATE_AVAILABLE -> R.string.schemes_source_use_new
            !source.inUse -> R.string.schemes_source_use
            else -> null
        }
    if (useLabel != null) {
        item(key = "use") {
            Button(
                onClick = { onUse(source.id) },
                enabled = idle,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Text(stringResource(useLabel))
            }
        }
    }
}

/** The input methods the package contains; the one the keyboard types with is marked. */
private fun LazyListScope.methodItems(
    source: StoredSourceInfo,
    idle: Boolean,
    onSelect: (String) -> Unit,
) {
    if (source.schemes.isEmpty()) return
    item { SectionHeader(stringResource(R.string.schemes_methods_section)) }
    items(source.schemes.size, key = { "method_" + source.schemes[it].id }) { index ->
        val method = source.schemes[index]
        if (source.inUse) {
            RadioRow(
                title = method.name,
                selected = method.id == source.inUseSchemeId,
                enabled = idle,
                onClick = { onSelect(method.id) },
            )
        } else {
            ListItem(
                headlineContent = { Text(method.name) },
                trailingContent = {
                    if (method.id == source.inUseSchemeId) {
                        Text(
                            stringResource(R.string.schemes_status_in_use),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
            )
        }
    }
}

private fun LazyListScope.updateItems(
    source: StoredSourceInfo,
    idle: Boolean,
    onUpdate: (String) -> Unit,
    onReimport: () -> Unit,
) {
    item { SectionHeader(stringResource(R.string.schemes_detail_update_section)) }
    item(key = "origin") {
        ListItem(
            headlineContent = {
                Text(
                    source.originHost?.let { stringResource(R.string.schemes_detail_from_link, it) }
                        ?: stringResource(R.string.schemes_detail_from_file),
                )
            },
        )
    }
    if (source.updatable) {
        item(key = "update") {
            OutlinedButton(
                onClick = { onUpdate(source.id) },
                enabled = idle,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Text(stringResource(R.string.schemes_source_update))
            }
        }
    } else {
        item(key = "reimport_note") {
            OutlinedButton(
                onClick = onReimport,
                enabled = idle,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) { Text(stringResource(R.string.schemes_scheme_import_file)) }
            DetailNote(stringResource(R.string.schemes_detail_reimport_note))
        }
    }
}

private fun LazyListScope.manageItems(
    source: StoredSourceInfo,
    sources: List<StoredSourceInfo>,
    idle: Boolean,
    onUse: (String) -> Unit,
    onDelete: () -> Unit,
) {
    item { SectionHeader(stringResource(R.string.row_schemes_title)) }
    if (!source.deletable) {
        item(key = "builtin_note") { DetailNote(stringResource(R.string.schemes_detail_builtin_note)) }
        return
    }
    val fallback = sources.firstOrNull { it.kind == StoredSourceKind.BUILTIN }
    if (source.inUse && fallback != null) {
        item(key = "stop_using") {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onUse(fallback.id) },
                    enabled = idle,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.schemes_detail_stop_using))
                }
                Text(
                    stringResource(R.string.schemes_detail_stop_using_note, fallback.name),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    item(key = "delete") {
        TextButton(
            onClick = onDelete,
            enabled = idle,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Text(stringResource(R.string.schemes_source_delete), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun DetailNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}
