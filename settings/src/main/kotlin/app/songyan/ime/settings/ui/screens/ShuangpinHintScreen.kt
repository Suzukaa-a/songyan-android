package app.songyan.ime.settings.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.songyan.ime.layout.ShuangpinHintException
import app.songyan.ime.layout.ShuangpinHintPreset
import app.songyan.ime.layout.ShuangpinHintPresets
import app.songyan.ime.layout.ShuangpinHintProblem
import app.songyan.ime.settings.R
import app.songyan.ime.settings.ui.ShuangpinHintMessage
import app.songyan.ime.settings.ui.ShuangpinHintViewModel
import app.songyan.ime.settings.ui.components.RadioRow
import app.songyan.ime.settings.ui.components.SectionHeader
import app.songyan.ime.settings.ui.components.SettingsRow
import app.songyan.ime.settings.ui.components.SettingsScaffold
import app.songyan.ime.settings.ui.components.SwitchRow
import java.util.UUID

/**
 * 双拼提示: an opt-in overlay of each letter's shuangpin finals on the letter keys, independent of
 * the input method in use. The built-in Xiaohe preset can be copied and edited; presets move
 * between devices (and the iOS app) as JSON files.
 */
@Composable
fun ShuangpinHintScreen(
    onBack: (() -> Unit)?,
    viewModel: ShuangpinHintViewModel = viewModel(factory = ShuangpinHintViewModel.Factory()),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.contentResolver
    val snackbar = remember { SnackbarHostState() }
    val text = message?.let { shuangpinMessageText(it) }
    LaunchedEffect(message) {
        if (text != null) {
            snackbar.showSnackbar(text)
            viewModel.consumeMessage()
        }
    }

    var editing by remember { mutableStateOf<ShuangpinHintPreset?>(null) }
    var deleting by remember { mutableStateOf<ShuangpinHintPreset?>(null) }
    var resetting by rememberSaveable { mutableStateOf(false) }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    var linking by rememberSaveable { mutableStateOf(false) }
    val importedSuffix = stringResource(R.string.shuangpin_imported_suffix)
    val newName = stringResource(R.string.shuangpin_editor_default_name)
    val importer =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.importFile(resolver, uri, importedSuffix)
        }
    val exporter =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) viewModel.export(resolver, uri, exportId)
        }
    val snapshot = state.snapshot
    val copyName = stringResource(R.string.shuangpin_copy_suffix, "%s")

    SettingsScaffold(
        title = stringResource(R.string.shuangpin_title),
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp),
        ) {
            if (state.damaged) {
                Text(
                    text = stringResource(R.string.shuangpin_damaged),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            SwitchRow(
                title = stringResource(R.string.shuangpin_switch),
                subtitle =
                    stringResource(
                        R.string.shuangpin_current,
                        snapshot.preset(snapshot.selectedPresetId)?.name.orEmpty(),
                    ),
                checked = snapshot.enabled,
                onCheckedChange = viewModel::setEnabled,
                enabled = state.loaded && !state.damaged,
            )
            Text(
                text = stringResource(R.string.shuangpin_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            SectionHeader(stringResource(R.string.shuangpin_presets_section))
            Column(modifier = Modifier.selectableGroup()) {
                for (preset in snapshot.presets) {
                    val builtin = preset.id == ShuangpinHintPresets.BUILTIN_ID
                    RadioRow(
                        title = preset.name,
                        subtitle =
                            stringResource(
                                if (builtin) R.string.shuangpin_builtin_count else R.string.shuangpin_count,
                                preset.hints.size,
                            ),
                        selected = preset.id == snapshot.selectedPresetId,
                        enabled = !state.damaged,
                        onClick = { viewModel.select(preset.id) },
                        trailing = {
                            PresetMenu(
                                builtin = builtin,
                                enabled = !state.damaged,
                                onCopy = { viewModel.duplicate(preset.id, copyName.replace("%s", preset.name)) },
                                onEdit = { editing = preset },
                                onExport = {
                                    exportId = preset.id
                                    exporter.launch(preset.name + ".json")
                                },
                                onDelete = { deleting = preset },
                            )
                        },
                    )
                }
            }
            Text(
                text = stringResource(R.string.shuangpin_presets_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (!state.damaged) {
                SettingsRow(
                    title = stringResource(R.string.shuangpin_new),
                    subtitle = null,
                    onClick = {
                        editing =
                            ShuangpinHintPreset(
                                id = UUID.randomUUID().toString().lowercase(),
                                name = newName,
                                hints = emptyMap(),
                            )
                    },
                )
                SettingsRow(
                    title = stringResource(R.string.shuangpin_import_file),
                    subtitle = null,
                    onClick = {
                        importer.launch(
                            arrayOf("application/json", "text/plain", "application/octet-stream"),
                        )
                    },
                )
                SettingsRow(
                    title = stringResource(R.string.shuangpin_import_link),
                    subtitle = null,
                    onClick = { linking = true },
                )
                SettingsRow(
                    title = stringResource(R.string.shuangpin_export_all),
                    subtitle = null,
                    onClick = {
                        exportId = null
                        exporter.launch("shuangpin-hints.json")
                    },
                )
            }
            SettingsRow(
                title = stringResource(R.string.shuangpin_reset),
                subtitle = null,
                onClick = { resetting = true },
            )
        }
    }

    editing?.let { preset ->
        ShuangpinHintEditor(
            initial = preset,
            isNew = snapshot.preset(preset.id) == null,
            onDismiss = { editing = null },
            onSave = {
                viewModel.save(it)
                editing = null
            },
        )
    }
    deleting?.let { preset ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.shuangpin_delete_title, preset.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(preset.id)
                    deleting = null
                }) { Text(stringResource(R.string.shuangpin_delete)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { deleting = null },
                ) { Text(stringResource(R.string.shuangpin_cancel)) }
            },
        )
    }
    if (linking) {
        HintLinkDialog(
            downloading = state.downloading,
            onImport = { viewModel.importLink(it, importedSuffix) },
            onStop = viewModel::cancelLinkImport,
            onClose = { linking = false },
        )
    }
    if (resetting) {
        AlertDialog(
            onDismissRequest = { resetting = false },
            title = { Text(stringResource(R.string.shuangpin_reset_title)) },
            text = { Text(stringResource(R.string.shuangpin_reset_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.reset()
                    resetting = false
                }) { Text(stringResource(R.string.shuangpin_reset)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { resetting = false },
                ) { Text(stringResource(R.string.shuangpin_cancel)) }
            },
        )
    }
}

@Composable
private fun PresetMenu(
    builtin: Boolean,
    enabled: Boolean,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.shuangpin_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            fun item(
                label: Int,
                action: () -> Unit,
            ) = @Composable {
                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                    open = false
                    action()
                })
            }
            item(R.string.shuangpin_copy, onCopy)()
            if (!builtin) item(R.string.shuangpin_edit, onEdit)()
            item(R.string.shuangpin_export, onExport)()
            if (!builtin) item(R.string.shuangpin_delete, onDelete)()
        }
    }
}

/** The name and one field per letter, in keyboard rows; a hint over the byte limit is flagged as it is typed. */
@Composable
private fun ShuangpinHintEditor(
    initial: ShuangpinHintPreset,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (ShuangpinHintPreset) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial.name) }
    val hints = remember { mutableStateMapOf<Char, String>().apply { putAll(initial.hints) } }
    var confirmDiscard by remember { mutableStateOf(false) }
    val changed = name != initial.name || hints.filterValues { it.isNotBlank() } != initial.hints
    val tooLong = hints.values.any { it.trim().encodeToByteArray().size > ShuangpinHintPreset.MAXIMUM_HINT_BYTES }
    val nameValid = name.isNotBlank() && name.encodeToByteArray().size <= ShuangpinHintPreset.MAXIMUM_NAME_BYTES
    val leave = { if (changed) confirmDiscard = true else onDismiss() }

    Dialog(
        onDismissRequest = leave,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BackHandler(onBack = leave)
        SettingsScaffold(
            title =
                stringResource(
                    if (isNew) R.string.shuangpin_editor_new_title else R.string.shuangpin_editor_edit_title,
                ),
            onBack = leave,
            actions = {
                TextButton(
                    onClick = { onSave(initial.copy(name = name.trim(), hints = hints.toMap())) },
                    enabled = nameValid && !tooLong && (changed || isNew),
                ) { Text(stringResource(R.string.shuangpin_save)) }
            },
        ) { padding ->
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .imePadding()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.shuangpin_editor_name)) },
                    singleLine = true,
                    isError = !nameValid,
                    supportingText =
                        if (!nameValid) {
                            (
                                {
                                    Text(
                                        stringResource(R.string.shuangpin_editor_name_required),
                                    )
                                }
                            )
                        } else {
                            null
                        },
                    modifier = Modifier.fillMaxWidth(),
                )
                ShuangpinHintPreset.LETTER_ROWS.forEachIndexed { index, row ->
                    SectionHeader(stringResource(R.string.shuangpin_editor_row, index + 1))
                    for (letter in row) {
                        val value = hints[letter].orEmpty()
                        val over = value.trim().encodeToByteArray().size > ShuangpinHintPreset.MAXIMUM_HINT_BYTES
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 2.dp),
                        ) {
                            Text(
                                text = letter.uppercase(),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.width(32.dp),
                            )
                            OutlinedTextField(
                                value = value,
                                onValueChange = { text -> hints[letter] = text.replace("\n", "").replace("\r", "") },
                                singleLine = true,
                                isError = over,
                                supportingText =
                                    if (over) {
                                        {
                                            Text(
                                                stringResource(
                                                    R.string.shuangpin_editor_too_long,
                                                    ShuangpinHintPreset.MAXIMUM_HINT_BYTES,
                                                ),
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.shuangpin_editor_discard_title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onDismiss()
                }) { Text(stringResource(R.string.shuangpin_editor_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.shuangpin_cancel)) }
            },
        )
    }
}

/**
 * The link dialog. It stays open while the file downloads, with a way to stop, and closes once the
 * download ended; the outcome shows like a file import's.
 */
@Composable
private fun HintLinkDialog(
    downloading: Boolean,
    onImport: (String) -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
) {
    var link by rememberSaveable { mutableStateOf("") }
    var started by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(downloading) {
        if (downloading) {
            started = true
        } else if (started) {
            onClose()
        }
    }
    AlertDialog(
        onDismissRequest = { if (!downloading) onClose() },
        title = { Text(stringResource(R.string.shuangpin_link_title)) },
        text = {
            Column {
                Text(stringResource(R.string.shuangpin_link_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    placeholder = { Text("https://") },
                    singleLine = true,
                    enabled = !downloading,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                if (downloading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
                    Text(
                        stringResource(R.string.shuangpin_downloading),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onImport(link) }, enabled = !downloading && link.isNotBlank()) {
                Text(stringResource(R.string.shuangpin_link_confirm))
            }
        },
        dismissButton = {
            if (downloading) {
                TextButton(onClick = onStop) { Text(stringResource(R.string.shuangpin_link_stop)) }
            } else {
                TextButton(onClick = onClose) { Text(stringResource(R.string.shuangpin_cancel)) }
            }
        },
    )
}

@Composable
private fun shuangpinMessageText(message: ShuangpinHintMessage): String =
    when (message) {
        is ShuangpinHintMessage.Saved -> stringResource(R.string.shuangpin_saved, message.name)
        is ShuangpinHintMessage.Imported -> stringResource(R.string.shuangpin_imported, message.name)
        is ShuangpinHintMessage.Deleted -> stringResource(R.string.shuangpin_deleted, message.name)
        ShuangpinHintMessage.Exported -> stringResource(R.string.shuangpin_exported)
        ShuangpinHintMessage.Reset -> stringResource(R.string.shuangpin_reset_done)
        ShuangpinHintMessage.FileUnreadable -> stringResource(R.string.shuangpin_file_unreadable)
        ShuangpinHintMessage.LinkInvalid -> stringResource(R.string.shuangpin_link_invalid)
        is ShuangpinHintMessage.DownloadFailed -> message.message ?: stringResource(R.string.shuangpin_download_failed)
        ShuangpinHintMessage.WriteFailed -> stringResource(R.string.shuangpin_write_failed)
        is ShuangpinHintMessage.Refused -> shuangpinErrorText(message.error)
    }

@Composable
private fun shuangpinErrorText(error: ShuangpinHintException): String {
    val field = error.field.orEmpty()
    return when (error.problem) {
        ShuangpinHintProblem.TOO_LARGE -> stringResource(R.string.shuangpin_error_too_large)
        ShuangpinHintProblem.MALFORMED -> stringResource(R.string.shuangpin_error_malformed)
        ShuangpinHintProblem.UNKNOWN_FIELD -> stringResource(R.string.shuangpin_error_unknown_field, field)
        ShuangpinHintProblem.MISSING_FIELD -> stringResource(R.string.shuangpin_error_missing_field, field)
        ShuangpinHintProblem.WRONG_TYPE -> stringResource(R.string.shuangpin_error_wrong_type, field)
        ShuangpinHintProblem.UNSUPPORTED_VERSION -> stringResource(R.string.shuangpin_error_version)
        ShuangpinHintProblem.INVALID_ID -> stringResource(R.string.shuangpin_error_id)
        ShuangpinHintProblem.INVALID_NAME -> stringResource(R.string.shuangpin_error_name)
        ShuangpinHintProblem.TOO_MANY_HINTS -> stringResource(R.string.shuangpin_error_too_many_hints)
        ShuangpinHintProblem.INVALID_LETTER -> stringResource(R.string.shuangpin_error_letter, field)
        ShuangpinHintProblem.DUPLICATE_LETTER -> stringResource(R.string.shuangpin_error_duplicate_letter, field)
        ShuangpinHintProblem.HINT_TOO_LONG -> stringResource(R.string.shuangpin_error_hint_too_long, field.uppercase())
        ShuangpinHintProblem.HINT_HAS_LINE_BREAK ->
            stringResource(
                R.string.shuangpin_error_line_break,
                field.uppercase(),
            )
        ShuangpinHintProblem.TOO_MANY_PRESETS -> stringResource(R.string.shuangpin_error_too_many_presets)
        ShuangpinHintProblem.DUPLICATE_PRESET -> stringResource(R.string.shuangpin_error_duplicate_preset)
        ShuangpinHintProblem.MISSING_SELECTION -> stringResource(R.string.shuangpin_error_missing_selection)
    }
}
