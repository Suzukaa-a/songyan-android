package app.songyan.ime.settings.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.songyan.ime.script.ScriptEntry
import app.songyan.ime.script.ScriptRunOutcome
import app.songyan.ime.script.ScriptSettingField
import app.songyan.ime.script.ScriptSettingKind
import app.songyan.ime.settings.R
import app.songyan.ime.settings.ui.ScriptsNotice
import app.songyan.ime.settings.ui.ScriptsViewModel
import app.songyan.ime.settings.ui.components.HideKeyboardOnEnter
import app.songyan.ime.settings.ui.components.ImeDialog
import app.songyan.ime.settings.ui.components.LoadingScreen
import app.songyan.ime.settings.ui.components.SectionHeader
import app.songyan.ime.settings.ui.components.SettingsScaffold
import app.songyan.ime.settings.ui.components.StateMessage
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
    // A page opened from the list can still have the search keyboard up; it would cover the
    // actions at the bottom of this page.
    HideKeyboardOnEnter()
    val test by viewModel.test.collectAsStateWithLifecycle()
    val entry = state.entries.firstOrNull { it.id == scriptId }
    val context = LocalContext.current

    val snackbar = remember { SnackbarHostState() }
    val exportedText = stringResource(R.string.scripts_notice_exported)
    val exportFailedText = stringResource(R.string.scripts_notice_export_failed)
    val upToDateText = stringResource(R.string.scripts_notice_up_to_date)
    val updatedText = stringResource(R.string.scripts_notice_updated)
    val deletedText = stringResource(R.string.scripts_notice_deleted)
    val settingsSavedText = stringResource(R.string.scripts_notice_settings_saved)
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
                ScriptsNotice.SettingsSaved -> settingsSavedText
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
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }

    if (state.loading) {
        // The catalog is still being read; the entry is not "gone", it is just not here yet. This
        // page and the confirmation page are opened from a list that has already loaded, but a
        // process restoration can compose them first.
        LoadingScreen(
            title = entry?.name ?: stringResource(R.string.scripts_title),
            onBack = onBack,
        )
        return
    }

    SettingsScaffold(
        title = entry?.name ?: stringResource(R.string.scripts_title),
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbar) },
        actions = {
            // One overflow keeps the bar calm: the destructive entry sits behind the menu, not
            // one stray tap away.
            var menuOpen by remember { mutableStateOf(false) }
            val ordered = state.entries.sortedBy { it.order }
            val index = ordered.indexOfFirst { it.id == scriptId }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.scripts_more_actions),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (entry?.sourceOrigin is app.songyan.ime.script.ScriptSourceOrigin.Link) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.scripts_check_update)) },
                            enabled = !state.busy,
                            onClick = {
                                menuOpen = false
                                viewModel.checkUpdate(scriptId)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.scripts_edit)) },
                        onClick = {
                            menuOpen = false
                            editorOpen = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.scripts_export)) },
                        onClick = {
                            menuOpen = false
                            viewModel.export(context, scriptId, (entry?.name ?: "script") + ".js")
                        },
                    )
                    if (index > 0) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.scripts_move_up)) },
                            onClick = {
                                menuOpen = false
                                viewModel.move(scriptId, up = true)
                            },
                        )
                    }
                    if (index in 0 until ordered.lastIndex) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.scripts_move_down)) },
                            onClick = {
                                menuOpen = false
                                viewModel.move(scriptId, up = false)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.scripts_delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            deleting = true
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        if (entry == null) {
            // Deleted from the list behind this page: say so instead of leaving a blank page.
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                StateMessage(title = stringResource(R.string.scripts_missing_title))
            }
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
                        trailingContent = {
                            TextButton(onClick = { settingsOpen = true }) {
                                Text(stringResource(R.string.scripts_settings_edit))
                            }
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
    if (settingsOpen && entry != null) {
        ScriptSettingsDialog(
            scriptId = scriptId,
            entry = entry,
            viewModel = viewModel,
            onDismiss = { settingsOpen = false },
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
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
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
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
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

/** The editable settings form of an already confirmed script: saved non-secret values are
 *  prefilled, a blank secret keeps the stored one, and the clearance checkbox is the explicit
 *  way to delete a stored secret. */
@Composable
private fun ScriptSettingsDialog(
    scriptId: String,
    entry: ScriptEntry,
    viewModel: ScriptsViewModel,
    onDismiss: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var values by rememberSaveable(scriptId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var clearSecrets by rememberSaveable(scriptId) { mutableStateOf<List<String>>(emptyList()) }
    var loaded by rememberSaveable(scriptId) { mutableStateOf(false) }
    val saved by
        produceState<Map<String, String>?>(initialValue = null, scriptId) {
            value = viewModel.savedSettings(scriptId)
        }
    LaunchedEffect(saved) {
        val savedValues = saved ?: return@LaunchedEffect
        if (!loaded) {
            values = savedValues
            loaded = true
        }
    }
    // The save round-trip is a busy window: close the dialog only after it has started and
    // finished, so a fast state emission cannot dismiss the form before the write happened.
    var saving by remember { mutableStateOf(false) }
    var sawBusy by remember { mutableStateOf(false) }
    LaunchedEffect(state.busy) {
        if (!saving) return@LaunchedEffect
        if (state.busy) {
            sawBusy = true
        } else if (sawBusy) {
            saving = false
            sawBusy = false
            onDismiss()
        }
    }
    ImeDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.scripts_settings_title),
        confirmButton = {
            TextButton(
                onClick = {
                    saving = true
                    viewModel.updateSettings(scriptId, values, clearSecrets.toSet())
                },
                enabled = !state.busy,
            ) {
                if (state.busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.scripts_editor_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.scripts_cancel)) }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            entry.settings.forEach { field ->
                SettingsEditField(
                    field = field,
                    value = values[field.key] ?: "",
                    clearSaved = field.key in clearSecrets,
                    onChange = { value -> values = values + (field.key to value) },
                    onClearSaved = { clear ->
                        clearSecrets =
                            if (clear) {
                                clearSecrets + field.key
                            } else {
                                clearSecrets - field.key
                            }
                    },
                )
            }
        }
    }
}

/** One field of the settings dialog; the same masking, validation and secret rules as the
 *  confirmation page. */
@Composable
private fun SettingsEditField(
    field: ScriptSettingField,
    value: String,
    clearSaved: Boolean,
    onChange: (String) -> Unit,
    onClearSaved: (Boolean) -> Unit,
) {
    var reveal by rememberSaveable(field.key) { mutableStateOf(false) }
    val addressInvalid =
        field.kind == ScriptSettingKind.URL &&
            value.isNotBlank() &&
            !value.trim().startsWith("http://") &&
            !value.trim().startsWith("https://")
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = {
                Text(
                    field.label +
                        if (field.secret) stringResource(R.string.scripts_secret_suffix) else "",
                )
            },
            supportingText = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        when {
                            addressInvalid -> stringResource(R.string.scripts_setting_url_invalid)
                            field.kind == ScriptSettingKind.URL -> stringResource(R.string.scripts_setting_kind_url)
                            field.kind == ScriptSettingKind.CHOICE ->
                                stringResource(R.string.scripts_setting_choice_hint)
                            else -> stringResource(R.string.scripts_setting_kind_text)
                        },
                    )
                    if (field.secret) Text(stringResource(R.string.scripts_secret_keep_hint))
                }
            },
            isError = addressInvalid,
            singleLine = true,
            visualTransformation =
                if (field.secret && !reveal) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
            trailingIcon =
                if (field.secret) {
                    {
                        IconButton(onClick = { reveal = !reveal }) {
                            Icon(
                                imageVector = if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription =
                                    stringResource(
                                        if (reveal) R.string.scripts_secret_hide else R.string.scripts_secret_show,
                                    ),
                            )
                        }
                    }
                } else {
                    null
                },
            modifier = Modifier.fillMaxWidth(),
        )
        if (field.secret) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { onClearSaved(!clearSaved) },
            ) {
                Checkbox(checked = clearSaved, onCheckedChange = onClearSaved)
                Text(stringResource(R.string.scripts_secret_clear))
            }
        }
    }
}
