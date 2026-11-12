package app.songyan.ime.settings.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.songyan.ime.script.ScriptRunOutcome
import app.songyan.ime.settings.R
import app.songyan.ime.settings.ui.ScriptsNotice
import app.songyan.ime.settings.ui.ScriptsViewModel
import app.songyan.ime.settings.ui.components.SectionHeader
import app.songyan.ime.settings.ui.components.SettingsScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One script's own page: the trial run (type text, see what comes back, the console and the error
 * line when it fails), the in-app editor, the export that never carries secrets, the update check
 * for link origins, and delete. Enabling and ordering stay in the list; this page is where the
 * user looks at what a script does.
 */
@Composable
fun ScriptDetailScreen(
    scriptId: String,
    onBack: () -> Unit,
    onOpenConfirmation: (id: String) -> Unit,
    viewModel: ScriptsViewModel = viewModel(factory = ScriptsViewModel.Factory()),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val test by viewModel.test.collectAsStateWithLifecycle()
    val entry = state.entries.firstOrNull { it.id == scriptId }
    val context = LocalContext.current

    val snackbar = remember { SnackbarHostState() }
    val exportedText = stringResource(R.string.scripts_notice_exported)
    val exportFailedText = stringResource(R.string.scripts_notice_export_failed)
    val upToDateText = stringResource(R.string.scripts_notice_up_to_date)
    val updatedText = stringResource(R.string.scripts_notice_updated)
    val deletedText = stringResource(R.string.scripts_notice_deleted)
    // Snackbar effects cannot call composables, so a failure's wording is resolved here, when the
    // notice it belongs to is the current one.
    val actionFailureText =
        (state.notice as? ScriptsNotice.ActionFailed)?.let {
            scriptFailureText(it.failure.code, it.failure.message)
        }
    LaunchedEffect(state.notice) {
        val text =
            when (state.notice) {
                is ScriptsNotice.Exported -> exportedText
                ScriptsNotice.ExportFailed -> exportFailedText
                ScriptsNotice.UpToDate -> upToDateText
                is ScriptsNotice.Updated -> updatedText
                ScriptsNotice.Deleted -> deletedText
                is ScriptsNotice.ActionFailed -> actionFailureText
                null -> null
            }
        when (text) {
            null -> if (state.notice != null) viewModel.consumeNotice()
            else -> {
                snackbar.showSnackbar(text)
                viewModel.consumeNotice()
            }
        }
    }
    // An update check that brought new declarations opens the confirm page right away.
    LaunchedEffect(state.pendingConfirmation) {
        val pending = state.pendingConfirmation ?: return@LaunchedEffect
        if (pending.id != scriptId) return@LaunchedEffect
        onOpenConfirmation(pending.id)
        viewModel.consumePendingConfirmation()
    }

    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }

    SettingsScaffold(
        title = entry?.name ?: stringResource(R.string.scripts_title),
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbar) },
        actions = {
            if (entry?.sourceOrigin is app.songyan.ime.script.ScriptSourceOrigin.Link) {
                IconButton(onClick = { viewModel.checkUpdate(scriptId) }, enabled = !state.busy) {
                    Icon(
                        Icons.Outlined.CloudDownload,
                        contentDescription = stringResource(R.string.scripts_check_update),
                    )
                }
            }
            IconButton(onClick = { editorOpen = true }) {
                Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.scripts_edit))
            }
            IconButton(onClick = { viewModel.export(context, scriptId, (entry?.name ?: "script") + ".js") }) {
                Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.scripts_export))
            }
            IconButton(onClick = { deleting = true }) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.scripts_delete))
            }
        },
    ) { innerPadding ->
        if (entry == null) {
            // Deleted from the list behind this page; the way back is the only thing left.
            LaunchedEffect(Unit) { onBack() }
            return@SettingsScaffold
        }
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (entry.confirmationPending) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.scripts_pending_title)) },
                        supportingContent = { Text(stringResource(R.string.scripts_pending_body)) },
                        trailingContent = {
                            TextButton(onClick = { onOpenConfirmation(entry.id) }) {
                                Text(stringResource(R.string.scripts_confirm_action))
                            }
                        },
                    )
                }
            }
            if (!entry.availableOnThisHost) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.scripts_unavailable_title)) },
                        supportingContent = { Text(stringResource(R.string.scripts_unavailable_body)) },
                    )
                }
            }
            item { SectionHeader(stringResource(R.string.scripts_test_section)) }
            item {
                TestBox(
                    input = test?.takeIf { it.entryId == scriptId }?.input ?: "",
                    running = test?.takeIf { it.entryId == scriptId }?.running == true,
                    outcome = test?.takeIf { it.entryId == scriptId }?.outcome,
                    onInput = viewModel::editTestInput,
                    onRun = viewModel::runTest,
                    onBegin = { viewModel.beginTest(scriptId) },
                )
            }
            item { SectionHeader(stringResource(R.string.scripts_about_section)) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.scripts_origin)) },
                    supportingContent = { Text(originSummary(entry)) },
                )
            }
            entry.description?.let { description ->
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.scripts_description)) },
                        supportingContent = { Text(description) },
                    )
                }
            }
            if (entry.connects.isNotEmpty()) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.scripts_connects)) },
                        supportingContent = { Text(entry.connects.joinToString("\n")) },
                    )
                }
            }
            if (entry.settings.isNotEmpty()) {
                item {
                    val secretSuffix = stringResource(R.string.scripts_secret_suffix)
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.scripts_settings_fields)) },
                        supportingContent = {
                            Text(
                                entry.settings.joinToString("\n") { field ->
                                    field.label + if (field.secret) secretSuffix else ""
                                },
                            )
                        },
                    )
                }
            }
        }
    }

    if (editorOpen) {
        ScriptEditorDialog(
            scriptId = scriptId,
            initial = entry,
            viewModel = viewModel,
            onDismiss = { editorOpen = false },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.scripts_delete_title)) },
            text = { Text(stringResource(R.string.scripts_delete_body, entry?.name ?: "")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(scriptId)
                        deleting = false
                    },
                ) { Text(stringResource(R.string.scripts_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.scripts_cancel)) }
            },
        )
    }
}

/** The trial run: type text, run, and see the outputs, the console lines or the error with its line. */
@Composable
private fun TestBox(
    input: String,
    running: Boolean,
    outcome: ScriptRunOutcome?,
    onInput: (String) -> Unit,
    onRun: () -> Unit,
    onBegin: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = onInput,
            label = { Text(stringResource(R.string.scripts_test_input_label)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                onBegin()
                onRun()
            }, enabled = !running) {
                if (running) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                } else {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                }
                Text(stringResource(R.string.scripts_test_run))
            }
        }
        outcome?.let { answer ->
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp).heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    when (answer) {
                        is ScriptRunOutcome.Success -> {
                            if (answer.outputs.isEmpty()) {
                                Text(
                                    stringResource(R.string.scripts_test_no_output),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            } else {
                                Text(
                                    stringResource(R.string.scripts_test_outputs),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                answer.outputs.forEach { output ->
                                    Text(
                                        output,
                                        style =
                                            MaterialTheme.typography.bodyMedium.copy(
                                                fontFamily = FontFamily.Monospace,
                                            ),
                                    )
                                }
                            }
                            if (answer.consoleLines.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.scripts_test_console) +
                                        if (answer.consoleTruncated) {
                                            stringResource(
                                                R.string.scripts_test_console_truncated,
                                            )
                                        } else {
                                            ""
                                        },
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                answer.consoleLines.forEach { line ->
                                    Text(
                                        line,
                                        style =
                                            MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                            ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        is ScriptRunOutcome.Failed ->
                            ScriptFailureText(answer.code, answer.message, answer.line)
                        ScriptRunOutcome.Cancelled ->
                            Text(
                                stringResource(R.string.scripts_test_cancelled),
                                style = MaterialTheme.typography.bodySmall,
                            )
                    }
                }
            }
        }
    }
}

/** The in-app editor: the stored source, changed and stored back as an edited origin. A blank
 *  [scriptId] means a new script, started from the offline example the guide teaches. */
@Composable
private fun ScriptEditorDialog(
    scriptId: String,
    initial: app.songyan.ime.script.ScriptEntry?,
    viewModel: ScriptsViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // The stored source loads once; the field keeps whatever the user then types.
    val stored by produceState<String?>(initialValue = null, scriptId) {
        value =
            withContext(Dispatchers.IO) {
                if (scriptId.isEmpty()) {
                    readExample(context)
                } else {
                    viewModel.sourceOf(scriptId)?.toString(Charsets.UTF_8)
                }
            }
    }
    var text by rememberSaveable(stored) { mutableStateOf(stored ?: "") }
    app.songyan.ime.settings.ui.components.ImeDialog(
        onDismissRequest = onDismiss,
        title =
            stringResource(
                if (scriptId.isEmpty()) R.string.scripts_editor_new_title else R.string.scripts_editor_title,
            ),
        confirmButton = {
            TextButton(
                onClick = {
                    viewModel.applyEdit(scriptId, text)
                    onDismiss()
                },
                enabled = text.isNotBlank() && !state.busy,
            ) { Text(stringResource(R.string.scripts_editor_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.scripts_cancel)) }
        },
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
        )
        Text(
            text = stringResource(R.string.scripts_editor_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private suspend fun readExample(context: android.content.Context): String? =
    withContext(Dispatchers.IO) {
        try {
            context.assets
                .open("script/examples/minimal.js")
                .bufferedReader()
                .use { it.readText() }
        } catch (e: java.io.IOException) {
            null
        }
    }
