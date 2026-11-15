package app.songyan.ime.settings.ui.screens

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.songyan.ime.script.ScriptEntry
import app.songyan.ime.script.ScriptSourceOrigin
import app.songyan.ime.settings.R
import app.songyan.ime.settings.ui.ScriptsNotice
import app.songyan.ime.settings.ui.ScriptsViewModel
import app.songyan.ime.settings.ui.components.ImeDialog
import app.songyan.ime.settings.ui.components.LoadingScreen
import app.songyan.ime.settings.ui.components.SettingsScaffold
import app.songyan.ime.settings.ui.components.StateMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** How the add sheet offers its ways in. */
private enum class AddWay { PASTE, LINK }

/** MIME types a picked script file may carry, depending on where it was saved or shared from. */
private val SCRIPT_FILE_TYPES = arrayOf("text/*", "application/javascript", "application/octet-stream")

/**
 * Settings → Scripts: the list of installed scripts, the ways to add one, and, with none installed,
 * two ways to start — copying the guide that tells an AI how to write one, and the smallest
 * working example.
 *
 * A stored script is never enabled by storing it: the confirm page shows what it will read, which
 * sites it may connect to and the settings it asks for, and only the user's confirmation turns it
 * on. Enable, order and delete live in this list; the trial run, export and update check live on
 * the entry's page.
 */
@Composable
fun ScriptsScreen(
    onBack: (() -> Unit)?,
    onOpen: (id: String) -> Unit,
    onOpenConfirmation: (id: String) -> Unit,
    viewModel: ScriptsViewModel = viewModel(factory = ScriptsViewModel.Factory()),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // The keyboard and this screen live in the same process; a script added or removed while the
    // user is elsewhere shows up on every return without a manual refresh.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val copiedText = stringResource(R.string.scripts_guide_copied)
    val guideFailedText = stringResource(R.string.scripts_guide_failed)
    val guideLabel = stringResource(R.string.scripts_guide_clipboard_label)
    val deletedText = stringResource(R.string.scripts_notice_deleted)
    val upToDateText = stringResource(R.string.scripts_notice_up_to_date)
    val updatedText = stringResource(R.string.scripts_notice_updated)
    val exportFailedText = stringResource(R.string.scripts_notice_export_failed)
    val settingsSavedText = stringResource(R.string.scripts_notice_settings_saved)
    val savedText = stringResource(R.string.scripts_notice_saved)
    val alreadyAddedText = stringResource(R.string.scripts_notice_already_added)
    // Snackbar effects cannot call composables, so a failure's wording is resolved here, when the
    // notice it belongs to is the current one.
    val actionFailureText =
        (state.notice as? ScriptsNotice.ActionFailed)?.let {
            scriptFailureText(it.failure.code, it.failure.message)
        }
    LaunchedEffect(state.guideCopied, state.guideFailed) {
        when {
            state.guideCopied -> {
                snackbar.showSnackbar(copiedText)
                viewModel.consumeGuideCopied()
            }
            state.guideFailed -> {
                snackbar.showSnackbar(guideFailedText)
                viewModel.consumeGuideFailed()
            }
        }
    }
    LaunchedEffect(state.notice) {
        val text =
            when (state.notice) {
                null -> null
                ScriptsNotice.Deleted -> deletedText
                ScriptsNotice.UpToDate -> upToDateText
                is ScriptsNotice.Updated -> updatedText
                ScriptsNotice.ExportFailed -> exportFailedText
                ScriptsNotice.Exported -> null
                ScriptsNotice.SettingsSaved -> settingsSavedText
                ScriptsNotice.Saved -> savedText
                ScriptsNotice.AlreadyAdded -> alreadyAddedText
                is ScriptsNotice.ActionFailed -> actionFailureText
            }
        when (text) {
            null -> if (state.notice != null) viewModel.consumeNotice()
            else -> {
                snackbar.showSnackbar(text)
                viewModel.consumeNotice()
            }
        }
    }
    var addOpen by rememberSaveable { mutableStateOf(false) }
    var addWay by rememberSaveable { mutableStateOf<AddWay?>(null) }
    var pastedText by rememberSaveable { mutableStateOf("") }
    var linkText by rememberSaveable { mutableStateOf("") }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    val filePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.addFromFile(context, uri)
        }

    SettingsScaffold(
        title = stringResource(R.string.scripts_title),
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbar) },
        actions = {
            IconButton(onClick = { addOpen = true }, enabled = !state.busy) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.scripts_add))
            }
        },
    ) { innerPadding ->
        if (state.loading) {
            LoadingScreen(title = stringResource(R.string.scripts_title), onBack = onBack)
            return@SettingsScaffold
        }
        if (state.entries.isEmpty()) {
            ScriptsEmpty(
                context,
                onCopyGuide = { viewModel.copyAiGuide(context, guideLabel) },
                onAdd = { addOpen = true },
                onAddExample = viewModel::addFromPaste,
                modifier = Modifier.fillMaxSize().padding(innerPadding),
            )
        } else {
            val ordered = state.entries.sortedBy { it.order }
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                itemsIndexed(ordered, key = { _, entry -> entry.id }) { _, entry ->
                    ScriptListRow(
                        entry = entry,
                        // A script whose declarations the user has not confirmed yet has no page
                        // of its own to show: the row goes to the confirmation that completes the
                        // import instead. Confirmed scripts open their detail page as before.
                        onClick = { if (entry.confirmationPending) onOpenConfirmation(entry.id) else onOpen(entry.id) },
                        onEnabled = { enabled -> viewModel.setEnabled(entry.id, enabled) },
                    )
                }
            }
        }
    }

    // The add sheet: one entry per way in, then the way's own dialog.
    if (addOpen) {
        AlertDialog(
            onDismissRequest = { addOpen = false },
            title = { Text(stringResource(R.string.scripts_add)) },
            text = {
                Column {
                    // Choosing a way closes this sheet and opens the way's own dialog; leaving it
                    // open would put the sheet back over the page this flow navigates to.
                    AddWayRow(Icons.Outlined.ContentPaste, stringResource(R.string.scripts_add_paste)) {
                        addOpen = false
                        addWay = AddWay.PASTE
                    }
                    AddWayRow(Icons.Outlined.UploadFile, stringResource(R.string.scripts_add_file)) {
                        addOpen = false
                        filePicker.launch(SCRIPT_FILE_TYPES)
                    }
                    AddWayRow(Icons.Outlined.Link, stringResource(R.string.scripts_add_link)) {
                        addOpen = false
                        addWay = AddWay.LINK
                    }
                    AddWayRow(Icons.Outlined.Edit, stringResource(R.string.scripts_add_editor)) {
                        addOpen = false
                        editorOpen = true
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { addOpen = false }) { Text(stringResource(R.string.scripts_cancel)) }
            },
        )
    }
    when (addWay) {
        AddWay.PASTE ->
            ImeDialog(
                onDismissRequest = { addWay = null },
                title = stringResource(R.string.scripts_add_paste_title),
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.addFromPaste(pastedText)
                            pastedText = ""
                            addWay = null
                        },
                        enabled = pastedText.isNotBlank() && !state.busy,
                    ) { Text(stringResource(R.string.scripts_add)) }
                },
                dismissButton = {
                    TextButton(onClick = { addWay = null }) { Text(stringResource(R.string.scripts_cancel)) }
                },
            ) {
                OutlinedTextField(
                    value = pastedText,
                    onValueChange = { pastedText = it },
                    label = { Text(stringResource(R.string.scripts_add_paste_label)) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

        AddWay.LINK ->
            ImeDialog(
                onDismissRequest = { addWay = null },
                title = stringResource(R.string.scripts_add_link_title),
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.addFromLink(linkText)
                            linkText = ""
                            addWay = null
                        },
                        enabled = linkText.isNotBlank() && !state.busy,
                    ) { Text(stringResource(R.string.scripts_add)) }
                },
                dismissButton = {
                    TextButton(onClick = { addWay = null }) { Text(stringResource(R.string.scripts_cancel)) }
                },
            ) {
                OutlinedTextField(
                    value = linkText,
                    onValueChange = { linkText = it },
                    label = { Text(stringResource(R.string.scripts_add_link_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.scripts_add_link_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

        null -> {}
    }
    // A refused import (paste, file or link) is told here, once, with the line the parser names.
    state.importFailure?.let { failure ->
        if (addWay == null && !state.busy) {
            AlertDialog(
                onDismissRequest = viewModel::consumeImportFailure,
                title = { Text(stringResource(R.string.scripts_import_failed_title)) },
                text = { ScriptFailureText(failure.code, failure.message, failure.line) },
                confirmButton = {
                    TextButton(onClick = viewModel::consumeImportFailure) { Text(stringResource(R.string.scripts_ok)) }
                },
            )
        }
    }
    if (editorOpen) {
        NewScriptEditorDialog(
            viewModel = viewModel,
            onDismiss = { editorOpen = false },
        )
    }
}

/** The add-way editor: a fresh script started from the offline example the guide teaches. */
@Composable
private fun NewScriptEditorDialog(
    viewModel: ScriptsViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val example by produceState<String?>(initialValue = null, context) {
        value =
            withContext(Dispatchers.IO) {
                try {
                    context.assets
                        .open(EXAMPLE_ASSET)
                        .bufferedReader()
                        .use { it.readText() }
                } catch (e: IOException) {
                    null
                }
            }
    }
    var text by rememberSaveable(example) { mutableStateOf(example ?: "") }
    ImeDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.scripts_editor_new_title),
        confirmButton = {
            TextButton(
                onClick = {
                    viewModel.applyEdit("", text)
                    onDismiss()
                },
                enabled = text.isNotBlank() && !state.busy,
            ) { Text(stringResource(R.string.scripts_add)) }
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

@Composable
private fun AddWayRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

/** One installed script: the whole row opens it, and the switch is the only trailing control;
 *  ordering and deletion live on the detail page so a narrow row keeps one tap target. */
@Composable
private fun ScriptListRow(
    entry: ScriptEntry,
    onClick: () -> Unit,
    onEnabled: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                entry.description?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                Text(
                    text = originSummary(entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // The badge sits on its own line so the name and the single-line origin keep the
                // whole row width at the largest system font.
                if (entry.confirmationPending) PendingBadge()
            }
        },
        leadingContent = { Icon(Icons.Outlined.Code, contentDescription = null) },
        trailingContent = {
            val switchLabel =
                if (entry.enabled) {
                    stringResource(R.string.scripts_disable_script, entry.name)
                } else {
                    stringResource(R.string.scripts_enable_script, entry.name)
                }
            // A script waiting for confirmation cannot run yet: the row opens the confirmation
            // page, and a switch here would only suggest it can be turned on as it is.
            if (!entry.confirmationPending) {
                Switch(
                    checked = entry.enabled,
                    onCheckedChange = onEnabled,
                    modifier = Modifier.semantics { contentDescription = switchLabel },
                )
            }
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

/** The \"needs confirmation\" marker beside the name: a badge, not a whole status row. */
@Composable
private fun PendingBadge() {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = stringResource(R.string.scripts_pending_badge),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
internal fun originSummary(entry: ScriptEntry): String =
    when (val origin = entry.sourceOrigin) {
        ScriptSourceOrigin.Pasted -> stringResource(R.string.scripts_origin_pasted)
        is ScriptSourceOrigin.File ->
            if (isReadableFileOrigin(origin.displayName)) {
                stringResource(R.string.scripts_origin_file, origin.displayName)
            } else {
                // Old records kept the document picker's opaque id (`msf:42`) as the file name; the
                // raw id is noise, so they read as the generic origin instead.
                stringResource(R.string.scripts_origin_file_unknown)
            }
        is ScriptSourceOrigin.Link -> stringResource(R.string.scripts_origin_link)
        ScriptSourceOrigin.Edited -> stringResource(R.string.scripts_origin_edited)
    }

/** A picked file's display name is user-facing only when the picker gave a real name. */
internal fun isReadableFileOrigin(name: String): Boolean =
    name.isNotBlank() && !name.startsWith("msf:") && '\uFFFD' !in name

/**
 * Nothing installed: what a script is, the guide an AI needs to write one, and the example that
 * runs without the network. The guide is copied rather than shared because it lands in a chat
 * box, where the system share sheet is one step too many.
 */
@Composable
private fun ScriptsEmpty(
    context: Context,
    onCopyGuide: () -> Unit,
    onAdd: () -> Unit,
    onAddExample: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            StateMessage(
                title = stringResource(R.string.scripts_empty_title),
                body = stringResource(R.string.scripts_empty_body),
                icon = Icons.Outlined.Code,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        item {
            TextButton(onClick = onCopyGuide, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(stringResource(R.string.scripts_copy_guide))
            }
        }
        item {
            TextButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(stringResource(R.string.scripts_add))
            }
        }
        item {
            MinimalExamplePreview(
                context,
                onAdd = onAddExample,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/** The offline example from the script module's assets, shown as it is stored. */
@Composable
private fun MinimalExamplePreview(
    context: Context,
    onAdd: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val example by produceState<String?>(initialValue = null, context) {
        value =
            withContext(Dispatchers.IO) {
                try {
                    context.assets
                        .open(EXAMPLE_ASSET)
                        .bufferedReader()
                        .use { it.readText() }
                } catch (e: IOException) {
                    null
                }
            }
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.scripts_example_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = example ?: "",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.padding(12.dp),
            )
        }
        // Seeing the whole flow once is the point of the example, so it adds in one tap.
        example?.let { source ->
            TextButton(onClick = { onAdd(source) }) { Text(stringResource(R.string.scripts_example_add)) }
        }
    }
}

/** The user copy of an engine refusal: known codes carry their own wording, anything else falls
 *  back to the engine's message. The line the parser names is appended when there is one. */
@Composable
internal fun scriptFailureText(
    code: String,
    message: String?,
    line: Int = 0,
): String {
    val named =
        when (code) {
            "timeout" -> stringResource(R.string.scripts_error_timeout)
            "outOfMemory" -> stringResource(R.string.scripts_error_out_of_memory)
            "addressNotAllowed" -> stringResource(R.string.scripts_error_address_not_allowed)
            "networkFailure" -> stringResource(R.string.scripts_error_network_failure)
            "badHeader" -> stringResource(R.string.scripts_error_bad_header)
            "unsupportedApiVersion" -> stringResource(R.string.scripts_error_unsupported_api)
            "capabilityUnavailable" -> stringResource(R.string.scripts_error_capability)
            "unavailable" -> stringResource(R.string.scripts_error_unavailable)
            else -> null
        }
    return when {
        named != null && line > 0 -> stringResource(R.string.scripts_error_at_line, named, line)
        named != null -> named
        else -> stringResource(R.string.scripts_error_generic, message ?: code)
    }
}

/** Monospace failure text for a dialog body, with the line when the parser names one. */
@Composable
internal fun ScriptFailureText(
    code: String,
    message: String?,
    line: Int,
) {
    Text(
        text = scriptFailureText(code, message, line),
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.error,
    )
}

private const val EXAMPLE_ASSET = "script/examples/minimal.js"
