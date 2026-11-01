package app.songyan.ime.settings.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.songyan.ime.settings.R
import app.songyan.ime.settings.state.ImportedDictionaryInfo
import app.songyan.ime.settings.state.InstalledScheme
import app.songyan.ime.settings.state.RecommendedSchemes
import app.songyan.ime.settings.state.StoredSourceInfo
import app.songyan.ime.settings.state.StoredSourceKind
import app.songyan.ime.settings.state.StoredSourceStatus
import app.songyan.ime.settings.ui.DictionaryImportUiState
import app.songyan.ime.settings.ui.EngineFeaturesUiState
import app.songyan.ime.settings.ui.RimeConfigMessage
import app.songyan.ime.settings.ui.RimeConfigUiState
import app.songyan.ime.settings.ui.SchemeImportUiState
import app.songyan.ime.settings.ui.StoredSourceUiState
import app.songyan.ime.settings.ui.components.GrammarModelActions
import app.songyan.ime.settings.ui.components.GrammarModelCard
import app.songyan.ime.settings.ui.components.ImportKind
import app.songyan.ime.settings.ui.components.ImportStatusCard
import app.songyan.ime.settings.ui.components.RadioRow
import app.songyan.ime.settings.ui.components.SectionHeader
import app.songyan.ime.settings.ui.components.SettingsScaffold
import app.songyan.ime.settings.ui.components.StateMessage
import app.songyan.ime.settings.ui.components.importCardOf
import app.songyan.ime.settings.ui.normalizeDictionaryUrl
import app.songyan.ime.settings.ui.normalizeSchemeUrl

/** The state and actions behind the grammar-model card. */
data class GrammarModelCardInput(
    val state: EngineFeaturesUiState,
    val actions: GrammarModelActions,
    val onImportFile: (Uri) -> Unit,
)

/**
 * Installed input schemes and the user-imported dictionary that extends them.
 *
 * The scheme in use carries a selected radio row; picking another writes the preference the
 * keyboard reads before it creates its engine session. The dictionary section above can import a
 * Rime dictionary from a file or a link; the scheme section can import a Rime scheme package (ZIP).
 * Both are full deploys, so the screen reports progress and stays usable meanwhile.
 */
@Composable
fun SchemesScreen(
    schemes: List<InstalledScheme>,
    inUseSchemeId: String?,
    importedDictionary: ImportedDictionaryInfo?,
    dictionaryImport: DictionaryImportUiState,
    dictionaryImportAvailable: Boolean,
    /** The picked files and the scheme package to stack them onto (null: the built-in input methods). */
    onImportDictionaryFile: (List<Uri>, String?) -> Unit,
    onImportDictionaryFromUrl: (String, String?) -> Unit,
    onRemoveImportedDictionary: (String?) -> Unit,
    onDismissDictionaryImport: () -> Unit,
    schemeImport: SchemeImportUiState,
    schemeImportAvailable: Boolean,
    onImportScheme: (Uri) -> Unit,
    onDismissSchemeImport: () -> Unit,
    onAnswerSchemeLearning: (Boolean) -> Unit = {},
    onCancelImport: (ImportKind) -> Unit,
    onRetryImport: (ImportKind) -> Unit,
    onUseScheme: (String) -> Unit,
    onBack: (() -> Unit)?,
    sources: List<StoredSourceInfo> = emptyList(),
    sourceAction: StoredSourceUiState = StoredSourceUiState.Idle,
    onOpenSource: (String) -> Unit = {},
    onDismissSourceAction: () -> Unit = {},
    stackedDictionaries: List<ImportedDictionaryInfo> = emptyList(),
    onImportSchemeFolder: (Uri) -> Unit = {},
    onImportSchemeFromLink: (String) -> Unit = {},
    onRestoreSource: (String) -> Unit = {},
    /** A package another app handed over; nothing is imported until the user agrees. */
    incomingPackage: Uri? = null,
    onConfirmIncomingPackage: () -> Unit = {},
    onDismissIncomingPackage: () -> Unit = {},
    /** The user's Rime configuration; the section is left out while unavailable. */
    rimeConfig: RimeConfigUiState = RimeConfigUiState(),
    /** Intents that open the configuration folder in the Files app, tried in order. */
    rimeConfigIntents: () -> List<Intent> = { emptyList() },
    onRimeConfigNoFilesApp: () -> Unit = {},
    onApplyRimeConfig: () -> Unit = {},
    onDismissRimeConfigMessage: () -> Unit = {},
    onOpenRimeEditor: (key: String) -> Unit = {},
    onRestoreRimeFile: (String) -> Unit = {},
    onDismissRimeOverrideNotice: () -> Unit = {},
    onUseRimeScripts: () -> Unit = {},
    onCancelRimeScripts: () -> Unit = {},
    /** The grammar-model download and import the card after a package import drives; the card is left out without it. */
    grammarModel: GrammarModelCardInput? = null,
) {
    val running = dictionaryImport is DictionaryImportUiState.Running
    val schemeRunning = schemeImport is SchemeImportUiState.Running
    val sourceRunning = sourceAction is StoredSourceUiState.Running
    // With imported scheme packages the user first says which one the dictionary is for.
    val targets = sources.filter { it.kind == StoredSourceKind.SCHEME }
    val builtinName = sources.firstOrNull { it.kind == StoredSourceKind.BUILTIN }?.name
    var importTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var choosingFor by rememberSaveable { mutableStateOf<String?>(null) }
    // Several files: a dictionary may import others (import_tables), picked together with it.
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) onImportDictionaryFile(uris, importTarget)
        }
    // A scheme package is a ZIP; some document providers only offer it as generic binary.
    val schemePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onImportScheme(uri)
        }
    val folderPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) onImportSchemeFolder(uri)
        }
    val grammarPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) grammarModel?.onImportFile?.invoke(uri)
        }
    var grammarCardDismissed by rememberSaveable { mutableStateOf(false) }
    // A new import brings its own card, even when the one before was closed.
    LaunchedEffect(schemeRunning) { if (schemeRunning) grammarCardDismissed = false }
    var showSchemeLinkDialog by rememberSaveable { mutableStateOf(false) }
    var schemeLinkText by rememberSaveable { mutableStateOf("") }
    var recommendedId by rememberSaveable { mutableStateOf<String?>(null) }
    var showUrlDialog by rememberSaveable { mutableStateOf(false) }
    var urlText by rememberSaveable { mutableStateOf("") }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    var removeTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val startImport: (String) -> Unit = { action ->
        if (targets.isEmpty()) {
            importTarget = null
            if (action == IMPORT_FROM_FILE) picker.launch(arrayOf("*/*")) else showUrlDialog = true
        } else {
            choosingFor = action
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val importCard = importCardOf(dictionaryImport, schemeImport)

    val sourceMessage =
        (sourceAction as? StoredSourceUiState.Failed)?.let {
            it.message
                ?: stringResource(R.string.schemes_source_failed)
        }
    LaunchedEffect(sourceMessage) {
        if (sourceMessage != null) {
            snackbarHostState.showSnackbar(sourceMessage)
            onDismissSourceAction()
        }
    }
    val deletedMessage =
        (sourceAction as? StoredSourceUiState.Deleted)?.let {
            stringResource(R.string.schemes_source_deleted, it.name)
        }
    val undoLabel = stringResource(R.string.schemes_source_undo)
    val context = LocalContext.current
    val configMessage =
        when (val message = rimeConfig.message) {
            RimeConfigMessage.Applied -> stringResource(R.string.rime_config_applied)
            RimeConfigMessage.NoFilesApp -> stringResource(R.string.rime_config_no_files_app)
            is RimeConfigMessage.Failure -> message.text ?: stringResource(R.string.rime_config_failed)
            null -> null
        }
    var showRimeEditPicker by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(configMessage) {
        if (configMessage != null) {
            snackbarHostState.showSnackbar(configMessage)
            onDismissRimeConfigMessage()
        }
    }
    LaunchedEffect(sourceAction) {
        val deleted = sourceAction as? StoredSourceUiState.Deleted ?: return@LaunchedEffect
        val result =
            snackbarHostState.showSnackbar(
                message = deletedMessage.orEmpty(),
                actionLabel = undoLabel,
                withDismissAction = true,
            )
        // Dismiss first: the restore request replaces the state it was offered from.
        onDismissSourceAction()
        if (result == SnackbarResult.ActionPerformed) onRestoreSource(deleted.sourceId)
    }

    SettingsScaffold(
        title = stringResource(R.string.row_schemes_title),
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            if (importCard != null) {
                item(key = "import_card") {
                    ImportStatusCard(
                        card = importCard,
                        onCancel = { onCancelImport(importCard.kind) },
                        onRetry = { onRetryImport(importCard.kind) },
                        onDismiss = {
                            if (importCard.kind ==
                                ImportKind.DICTIONARY
                            ) {
                                onDismissDictionaryImport()
                            } else {
                                onDismissSchemeImport()
                            }
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        onAnswerOffer = onAnswerSchemeLearning,
                    )
                }
            }
            val required = (schemeImport as? SchemeImportUiState.Succeeded)?.info?.requiredModels.orEmpty()
            if (grammarModel != null && required.isNotEmpty() && !grammarCardDismissed) {
                item(key = "grammar_model_card") {
                    GrammarModelCard(
                        paths = required,
                        state = grammarModel.state,
                        actions =
                            grammarModel.actions.copy(
                                onPickFile = { grammarPicker.launch(arrayOf("*/*")) },
                                onDismiss = {
                                    grammarCardDismissed = true
                                    grammarModel.actions.onDismiss()
                                },
                            ),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            item { SectionHeader(stringResource(R.string.schemes_dictionary_section)) }
            if (importedDictionary != null) {
                item(key = "imported_dictionary") {
                    ImportedDictionaryRow(
                        info = importedDictionary,
                        enabled = !running && !schemeRunning,
                        onRemove = {
                            removeTarget = null
                            confirmRemove = true
                        },
                    )
                }
            }
            for (stacked in stackedDictionaries) {
                item(key = "stacked_dictionary_" + stacked.targetSourceId) {
                    ImportedDictionaryRow(
                        info = stacked,
                        enabled = !running && !schemeRunning,
                        onRemove = {
                            removeTarget = stacked.targetSourceId
                            confirmRemove = true
                        },
                    )
                }
            }
            if (!dictionaryImportAvailable) {
                item { BodyText(stringResource(R.string.schemes_dictionary_unavailable)) }
            } else {
                item {
                    // Stacked instead of side by side so long labels and font scale 1.3 never squeeze
                    // a button below its text.
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Document providers disagree on the MIME type of a .dict.yaml file, so the
                        // picker cannot filter more than "anything".
                        Button(
                            onClick = { startImport(IMPORT_FROM_FILE) },
                            enabled = !running && !schemeRunning,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.schemes_dictionary_import_file))
                        }
                        OutlinedButton(
                            onClick = { startImport(IMPORT_FROM_URL) },
                            enabled = !running && !schemeRunning,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.schemes_dictionary_import_url))
                        }
                    }
                }
                item { BodyText(stringResource(R.string.schemes_dictionary_note)) }
            }

            item { SectionHeader(stringResource(R.string.row_schemes_title)) }
            if (!schemeImportAvailable) {
                item { BodyText(stringResource(R.string.schemes_scheme_import_unavailable)) }
            } else {
                item {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // One deploy at a time: a dictionary import may still be running.
                        val idle = !schemeRunning && !running
                        Button(
                            onClick = { schemePicker.launch(SCHEME_ARCHIVE_MIME_TYPES) },
                            enabled = idle,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.schemes_scheme_import_file))
                        }
                        OutlinedButton(
                            onClick = { folderPicker.launch(null) },
                            enabled = idle,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.schemes_scheme_import_folder))
                        }
                        OutlinedButton(
                            onClick = { showSchemeLinkDialog = true },
                            enabled = idle,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.schemes_scheme_import_link))
                        }
                    }
                }
                item { BodyText(stringResource(R.string.schemes_scheme_import_note)) }
                item { SectionHeader(stringResource(R.string.schemes_recommended_section)) }
                items(RecommendedSchemes.catalog.size, key = { "recommended_" + RecommendedSchemes.catalog[it].id }) {
                    val recommended = RecommendedSchemes.catalog[it]
                    ListItem(
                        headlineContent = { Text(stringResource(recommended.nameRes)) },
                        supportingContent = { Text(stringResource(recommended.detailRes)) },
                        modifier =
                            Modifier.clickable(enabled = !schemeRunning && !running, role = Role.Button) {
                                recommendedId = recommended.id
                            },
                    )
                }
            }
            if (sources.size > 1) {
                item { SectionHeader(stringResource(R.string.schemes_sources_section)) }
                items(sources.size, key = { "source_" + sources[it].id }) { index ->
                    val source = sources[index]
                    StoredSourceRow(
                        source = source,
                        working = (sourceAction as? StoredSourceUiState.Running)?.sourceId == source.id,
                        onOpen = { onOpenSource(source.id) },
                    )
                }
            }
            item { SectionHeader(stringResource(R.string.schemes_methods_section)) }
            if (schemes.isEmpty()) {
                item {
                    StateMessage(
                        title = stringResource(R.string.schemes_empty_title),
                        body = stringResource(R.string.schemes_empty_body),
                        icon = Icons.Outlined.Book,
                    )
                }
            } else {
                items(schemes.size) { index ->
                    val scheme = schemes[index]
                    // The selected radio is the state; repeating 正在使用/未使用 under every name
                    // would only add noise.
                    RadioRow(
                        title = scheme.name,
                        selected = scheme.id == inUseSchemeId,
                        onClick = { onUseScheme(scheme.id) },
                    )
                }
            }
            if (rimeConfig.available) {
                rimeConfigRows(
                    state = rimeConfig,
                    // One deploy at a time.
                    busy = running || schemeRunning || sourceRunning,
                    onOpen = {
                        val opened =
                            rimeConfigIntents().any { intent ->
                                try {
                                    context.startActivity(intent)
                                    true
                                } catch (_: ActivityNotFoundException) {
                                    false
                                }
                            }
                        if (!opened) onRimeConfigNoFilesApp()
                    },
                    onApply = onApplyRimeConfig,
                    onEdit = { showRimeEditPicker = true },
                    onRestore = onRestoreRimeFile,
                )
            }
        }
    }

    if (showRimeEditPicker) {
        RimeEditPickerDialog(
            targets = rimeConfig.info?.editTargets.orEmpty(),
            onPick = {
                showRimeEditPicker = false
                onOpenRimeEditor(it.key)
            },
            onDismiss = { showRimeEditPicker = false },
        )
    }
    rimeConfig.scriptConfirmation?.let { RimeScriptConfirmDialog(it, onUseRimeScripts, onCancelRimeScripts) }
    rimeConfig.overrideNotice?.let { RimeOverrideNoticeDialog(it, onDismissRimeOverrideNotice) }

    if (showSchemeLinkDialog) {
        SchemeLinkDialog(
            url = schemeLinkText,
            onUrlChange = { schemeLinkText = it },
            onConfirm = {
                onImportSchemeFromLink(schemeLinkText)
                schemeLinkText = ""
                showSchemeLinkDialog = false
            },
            onDismiss = { showSchemeLinkDialog = false },
        )
    }

    val recommended = RecommendedSchemes.catalog.firstOrNull { it.id == recommendedId }
    if (recommended != null) {
        val name = stringResource(recommended.nameRes)
        AlertDialog(
            onDismissRequest = { recommendedId = null },
            title = { Text(stringResource(R.string.schemes_recommended_confirm_title, name)) },
            text = { Text(stringResource(R.string.schemes_recommended_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        recommendedId = null
                        onImportSchemeFromLink(recommended.url)
                    },
                ) {
                    Text(stringResource(R.string.schemes_recommended_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { recommendedId = null }) {
                    Text(stringResource(R.string.schemes_dictionary_cancel))
                }
            },
        )
    }

    if (incomingPackage != null) {
        AlertDialog(
            onDismissRequest = onDismissIncomingPackage,
            title = { Text(stringResource(R.string.schemes_incoming_title)) },
            text = { Text(stringResource(R.string.schemes_incoming_body)) },
            confirmButton = {
                TextButton(onClick = onConfirmIncomingPackage) {
                    Text(stringResource(R.string.schemes_incoming_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissIncomingPackage) {
                    Text(stringResource(R.string.schemes_dictionary_cancel))
                }
            },
        )
    }

    if (showUrlDialog) {
        DictionaryUrlDialog(
            url = urlText,
            onUrlChange = { urlText = it },
            onConfirm = {
                onImportDictionaryFromUrl(urlText, importTarget)
                urlText = ""
                showUrlDialog = false
            },
            onDismiss = { showUrlDialog = false },
        )
    }

    val action = choosingFor
    if (action != null) {
        DictionaryTargetDialog(
            builtinName = builtinName ?: stringResource(R.string.schemes_dictionary_target_builtin),
            targets = targets,
            onChoose = { target ->
                choosingFor = null
                importTarget = target
                if (action == IMPORT_FROM_FILE) picker.launch(arrayOf("*/*")) else showUrlDialog = true
            },
            onDismiss = { choosingFor = null },
        )
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.schemes_dictionary_remove_title)) },
            text = { Text(stringResource(R.string.schemes_dictionary_remove_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemove = false
                        onRemoveImportedDictionary(removeTarget)
                    },
                ) {
                    Text(stringResource(R.string.schemes_dictionary_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) {
                    Text(stringResource(R.string.schemes_dictionary_cancel))
                }
            },
        )
    }
}

private const val IMPORT_FROM_FILE = "file"
private const val IMPORT_FROM_URL = "url"

/** Which input scheme an imported dictionary is for: the built-in input methods or one package. */
@Composable
private fun DictionaryTargetDialog(
    builtinName: String,
    targets: List<StoredSourceInfo>,
    onChoose: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    // The source in use is the likely answer, so it starts selected.
    var selected by rememberSaveable { mutableStateOf(targets.firstOrNull { it.inUse }?.id) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.schemes_dictionary_target_title)) },
        text = {
            LazyColumn {
                item(key = "builtin") {
                    RadioRow(title = builtinName, selected = selected == null, onClick = { selected = null })
                }
                items(targets.size, key = { targets[it].id }) { index ->
                    val target = targets[index]
                    RadioRow(title = target.name, selected = selected == target.id, onClick = { selected = target.id })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onChoose(selected) }) {
                Text(stringResource(R.string.schemes_dictionary_target_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.schemes_dictionary_cancel)) }
        },
    )
}

@Composable
private fun BodyText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun ImportedDictionaryRow(
    info: ImportedDictionaryInfo,
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(info.name) },
        supportingContent = {
            Column {
                Text(
                    stringResource(R.string.schemes_dictionary_entry_count, info.entryCount) +
                        " · " +
                        stringResource(
                            if (info.inUse) R.string.schemes_status_in_use else R.string.schemes_status_not_in_use,
                        ),
                )
                info.targetName?.let { target ->
                    Text(stringResource(R.string.schemes_dictionary_stacked_on, target))
                }
            }
        },
        trailingContent = {
            TextButton(onClick = onRemove, enabled = enabled) {
                Text(stringResource(R.string.schemes_dictionary_remove))
            }
        },
    )
}

/** One stored input scheme: its name, kind and status; tapping it opens the page with its actions. */
@Composable
private fun StoredSourceRow(
    source: StoredSourceInfo,
    working: Boolean,
    onOpen: () -> Unit,
) {
    val kind =
        stringResource(
            when (source.kind) {
                StoredSourceKind.BUILTIN -> R.string.schemes_source_kind_builtin
                StoredSourceKind.DICTIONARY -> R.string.schemes_source_kind_dictionary
                StoredSourceKind.SCHEME -> R.string.schemes_source_kind_scheme
            },
        )
    val status =
        stringResource(
            when {
                working -> R.string.schemes_source_working
                source.status == StoredSourceStatus.DAMAGED -> R.string.schemes_status_damaged
                source.status == StoredSourceStatus.UPDATE_AVAILABLE -> R.string.schemes_status_update_available
                source.inUse -> R.string.schemes_status_in_use
                else -> R.string.schemes_status_not_in_use
            },
        )
    ListItem(
        headlineContent = { Text(source.name) },
        supportingContent = { Text(if (source.status == StoredSourceStatus.DAMAGED) status else "$kind · $status") },
        modifier = Modifier.clickable(role = Role.Button, onClick = onOpen),
    )
}

@Composable
private fun SchemeLinkDialog(
    url: String,
    onUrlChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val valid = normalizeSchemeUrl(url) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.schemes_scheme_import_link)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    label = { Text(stringResource(R.string.schemes_scheme_link_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    isError = url.isNotBlank() && !valid,
                    supportingText = {
                        if (url.isNotBlank() && !valid) Text(stringResource(R.string.schemes_scheme_link_invalid))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.schemes_scheme_link_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = valid) {
                Text(stringResource(R.string.schemes_scheme_link_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.schemes_dictionary_cancel)) }
        },
    )
}

@Composable
private fun DictionaryUrlDialog(
    url: String,
    onUrlChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val valid = normalizeDictionaryUrl(url) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.schemes_dictionary_import_url)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    label = { Text(stringResource(R.string.schemes_dictionary_url_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    isError = url.isNotBlank() && !valid,
                    supportingText = {
                        if (url.isNotBlank() && !valid) {
                            Text(stringResource(R.string.schemes_dictionary_url_invalid))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.schemes_dictionary_url_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = valid) {
                Text(stringResource(R.string.schemes_dictionary_url_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.schemes_dictionary_cancel))
            }
        },
    )
}

/** A scheme package is a ZIP archive; some document providers expose it as generic binary only. */
private val SCHEME_ARCHIVE_MIME_TYPES = arrayOf("application/zip", "application/octet-stream")
