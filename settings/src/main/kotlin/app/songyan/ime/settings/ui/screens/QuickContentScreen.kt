package app.songyan.ime.settings.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.songyan.ime.content.CommonPhrase
import app.songyan.ime.content.ContentErrorCode
import app.songyan.ime.content.LocalQuickContentStore
import app.songyan.ime.settings.R
import app.songyan.ime.settings.ui.PhraseInputError
import app.songyan.ime.settings.ui.QuickContentMessage
import app.songyan.ime.settings.ui.QuickContentViewModel
import app.songyan.ime.settings.ui.components.DetailRow
import app.songyan.ime.settings.ui.components.ImeDialog
import app.songyan.ime.settings.ui.components.SectionHeader
import app.songyan.ime.settings.ui.components.SettingsScaffold
import app.songyan.ime.settings.ui.components.SwitchRow
import kotlinx.coroutines.launch

/**
 * 常用语与剪贴板: the opt-in clipboard history, user phrases and a summary of the symbol library.
 *
 * Every switch and row here writes the same files the keyboard reads, so a change applies the
 * next time the keyboard loads its quick content. The screen states the limits instead of failing
 * silently when they are reached. Dialog state survives rotation and process recreation.
 */
@Composable
fun QuickContentScreen(
    onBack: (() -> Unit)?,
    startWithAddPhrase: Boolean = false,
    viewModel: QuickContentViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editorOpen by rememberSaveable { mutableStateOf(startWithAddPhrase) }
    var editorId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmClearClipboard by rememberSaveable { mutableStateOf(false) }
    // The picked phrase file waits here for the user to confirm that it replaces the current phrases.
    var pendingPhraseImport by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmRestoreSymbols by rememberSaveable { mutableStateOf(false) }
    val resolver = LocalContext.current.contentResolver
    val jsonTypes = arrayOf("application/json", "text/plain", "application/octet-stream")
    val phrasesImporter =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) pendingPhraseImport = uri.toString()
        }
    val phrasesExporter =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) viewModel.exportPhrases(resolver, uri)
        }
    val symbolsImporter =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.importSymbols(resolver, uri)
        }
    val symbolsExporter =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) viewModel.exportSymbols(resolver, uri)
        }

    // Another window (the keyboard, or the user copying) may change the files while this screen is
    // in the background, so re-read them every time it comes back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    // Deleting one entry is never confirmed with a dialog, for the clipboard and for phrases alike;
    // the way back is an Undo action on the confirmation.
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val deletedText = stringResource(R.string.quick_content_deleted)
    val undoText = stringResource(R.string.quick_content_undo)

    val message by viewModel.message.collectAsStateWithLifecycle()
    val messageText = message?.let { transferMessageText(it) }
    LaunchedEffect(message) {
        if (messageText != null) {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(messageText)
            viewModel.consumeMessage()
        }
    }

    fun undoable(restore: () -> Unit) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result =
                snackbar.showSnackbar(
                    message = deletedText,
                    actionLabel = undoText,
                    duration = SnackbarDuration.Long,
                )
            if (result == SnackbarResult.ActionPerformed) restore()
        }
    }

    SettingsScaffold(
        title = stringResource(R.string.row_quick_content_title),
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        if (state.loading) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
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
            state.error?.let { code ->
                item {
                    Text(
                        text = contentErrorText(code),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            item { SectionHeader(stringResource(R.string.quick_content_clipboard_section)) }
            item {
                SwitchRow(
                    title = stringResource(R.string.quick_content_clipboard_switch),
                    subtitle =
                        stringResource(
                            R.string.quick_content_clipboard_body,
                            LocalQuickContentStore.MAXIMUM_HISTORY,
                        ),
                    checked = state.clipboardEnabled,
                    onCheckedChange = viewModel::setClipboardEnabled,
                )
            }
            if (state.clipboardEnabled) {
                if (state.clipboard.isEmpty()) {
                    item { InfoRow(stringResource(R.string.quick_content_clipboard_empty)) }
                } else {
                    items(state.clipboard, key = { it }) { entry ->
                        ListItem(
                            headlineContent = { Text(entry, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            trailingContent = {
                                IconButton(
                                    onClick = {
                                        viewModel.deleteClipboardEntry(entry)
                                        undoable { viewModel.restoreClipboardEntry(entry) }
                                    },
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = stringResource(R.string.quick_content_clipboard_delete),
                                    )
                                }
                            },
                        )
                    }
                    item {
                        ActionRow(
                            label = stringResource(R.string.quick_content_clipboard_clear),
                            onClick = { confirmClearClipboard = true },
                        )
                    }
                }
            }

            item {
                // The count lives in the heading; the body below only explains the empty state, so no
                // number is printed twice before the user can act.
                SectionHeader(
                    text = stringResource(R.string.quick_content_phrases_section),
                    trailing =
                        stringResource(
                            R.string.quick_content_phrases_counter,
                            state.phrases.size,
                            LocalQuickContentStore.MAXIMUM_PHRASES,
                        ),
                )
            }
            if (state.phrases.isEmpty()) {
                item { InfoRow(stringResource(R.string.quick_content_phrases_empty)) }
            } else {
                items(state.phrases, key = { it.id }) { phrase ->
                    ListItem(
                        headlineContent = { Text(phrase.text, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent =
                            phrase.shortcut.takeIf { it.isNotEmpty() }?.let { term ->
                                { Text(term, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            },
                        trailingContent = {
                            Row {
                                IconButton(
                                    onClick = {
                                        editorId = phrase.id
                                        editorOpen = true
                                    },
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Edit,
                                        contentDescription = stringResource(R.string.quick_content_edit),
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        viewModel.deletePhrase(phrase.id)
                                        undoable { viewModel.restorePhrase(phrase) }
                                    },
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = stringResource(R.string.quick_content_delete),
                                    )
                                }
                            }
                        },
                    )
                }
            }
            item {
                ActionRow(
                    label = stringResource(R.string.quick_content_phrases_add),
                    enabled = state.canAddPhrase,
                    onClick = {
                        editorId = null
                        editorOpen = true
                    },
                )
            }

            item {
                ActionRow(
                    label = stringResource(R.string.quick_content_phrases_import),
                    onClick = { phrasesImporter.launch(jsonTypes) },
                )
            }
            item {
                ActionRow(
                    label = stringResource(R.string.quick_content_phrases_export),
                    enabled = state.phrases.isNotEmpty(),
                    onClick = { phrasesExporter.launch("songyan-phrases.json") },
                )
            }

            item { SectionHeader(stringResource(R.string.quick_content_symbols_section)) }
            // One line carries what the symbol library is; the per-count rows only made the section
            // look like scaffolding.
            item {
                DetailRow(
                    title =
                        stringResource(
                            if (state.customSymbolCatalog) {
                                R.string.quick_content_symbols_custom
                            } else {
                                R.string.quick_content_symbols_builtin
                            },
                        ),
                    body =
                        stringResource(
                            R.string.quick_content_symbols_summary,
                            state.symbolItemCount,
                            state.symbolCategoryCount,
                        ),
                )
            }
            item {
                ActionRow(
                    label = stringResource(R.string.quick_content_symbols_import),
                    onClick = { symbolsImporter.launch(jsonTypes) },
                )
            }
            item {
                ActionRow(
                    label = stringResource(R.string.quick_content_symbols_export),
                    onClick = { symbolsExporter.launch("symbol-catalog.json") },
                )
            }
            if (state.customSymbolCatalog) {
                item {
                    ActionRow(
                        label = stringResource(R.string.quick_content_symbols_restore),
                        onClick = { confirmRestoreSymbols = true },
                    )
                }
            }
            if (state.recentSymbolCount > 0) {
                item { InfoRow(title = stringResource(R.string.quick_content_symbols_recent, state.recentSymbolCount)) }
                item {
                    ActionRow(
                        label = stringResource(R.string.quick_content_symbols_clear_recent),
                        onClick = viewModel::clearRecentSymbols,
                    )
                }
            }
        }
    }

    val editing = state.phrases.firstOrNull { it.id == editorId }
    if (editorOpen && !state.loading) {
        PhraseEditDialog(
            editing = editing,
            validate = { text, shortcut -> viewModel.validatePhrase(editorId, text, shortcut) },
            onDismiss = { editorOpen = false },
            save = { text, shortcut, onResult ->
                viewModel.savePhrase(editorId, text, shortcut) { code ->
                    if (code == null) editorOpen = false
                    onResult(code)
                }
            },
        )
    }

    pendingPhraseImport?.let { picked ->
        AlertDialog(
            onDismissRequest = { pendingPhraseImport = null },
            title = { Text(stringResource(R.string.quick_content_phrases_import_title)) },
            text = { Text(stringResource(R.string.quick_content_phrases_import_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.importPhrases(resolver, android.net.Uri.parse(picked))
                        pendingPhraseImport = null
                    },
                ) {
                    Text(stringResource(R.string.quick_content_phrases_import_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingPhraseImport = null }) {
                    Text(stringResource(R.string.quick_content_cancel))
                }
            },
        )
    }

    if (confirmRestoreSymbols) {
        AlertDialog(
            onDismissRequest = { confirmRestoreSymbols = false },
            title = { Text(stringResource(R.string.quick_content_symbols_restore_title)) },
            text = { Text(stringResource(R.string.quick_content_symbols_restore_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.restoreDefaultSymbols()
                        confirmRestoreSymbols = false
                    },
                ) {
                    Text(stringResource(R.string.quick_content_symbols_restore_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestoreSymbols = false }) {
                    Text(stringResource(R.string.quick_content_cancel))
                }
            },
        )
    }

    if (confirmClearClipboard) {
        AlertDialog(
            onDismissRequest = { confirmClearClipboard = false },
            title = { Text(stringResource(R.string.quick_content_clipboard_clear_title)) },
            text = { Text(stringResource(R.string.quick_content_clipboard_clear_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearClipboard()
                        confirmClearClipboard = false
                    },
                ) {
                    Text(stringResource(R.string.quick_content_clipboard_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearClipboard = false }) {
                    Text(stringResource(R.string.quick_content_cancel))
                }
            },
        )
    }
}

/** A row that only states a fact: not clickable, so it never looks like a dead link. */
@Composable
private fun InfoRow(title: String) {
    ListItem(headlineContent = { Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant) })
}

@Composable
private fun ActionRow(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    ) {
        Text(text = label, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * The add / edit dialog. It opens with the field focused, keeps what was typed across rotation, and
 * stays open until the write is known to have succeeded, so a failed save never throws the text away.
 */
@Composable
private fun PhraseEditDialog(
    editing: CommonPhrase?,
    validate: (text: String, shortcut: String) -> PhraseInputError?,
    onDismiss: () -> Unit,
    save: (text: String, shortcut: String, onResult: (ContentErrorCode?) -> Unit) -> Unit,
) {
    var text by rememberSaveable(editing?.id) { mutableStateOf(editing?.text.orEmpty()) }
    var shortcut by rememberSaveable(editing?.id) { mutableStateOf(editing?.shortcut.orEmpty()) }
    var validationError by rememberSaveable(editing?.id) { mutableStateOf<PhraseInputError?>(null) }
    var storageError by rememberSaveable(editing?.id) { mutableStateOf<ContentErrorCode?>(null) }
    var saving by rememberSaveable(editing?.id) { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    ImeDialog(
        onDismissRequest = onDismiss,
        title =
            stringResource(
                if (editing ==
                    null
                ) {
                    R.string.quick_content_phrase_add_title
                } else {
                    R.string.quick_content_phrase_edit_title
                },
            ),
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    val failure = validate(text, shortcut)
                    if (failure != null) {
                        validationError = failure
                        return@TextButton
                    }
                    saving = true
                    save(text, shortcut) { code ->
                        saving = false
                        storageError = code
                    }
                },
            ) {
                Text(stringResource(R.string.quick_content_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) {
                Text(stringResource(R.string.quick_content_cancel))
            }
        },
    ) {
        // Inside the dialog's own composition: the field exists by the time this runs, which a
        // request made from the screen's composition could not rely on.
        LaunchedEffect(Unit) { focus.requestFocus() }
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                validationError = null
                storageError = null
            },
            label = { Text(stringResource(R.string.quick_content_phrase_text_label)) },
            minLines = 2,
            enabled = !saving,
            isError = validationError != null || storageError != null,
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        // Keyboard search matches this term, so it is plain Latin input (dz, sh), never autocorrected.
        OutlinedTextField(
            value = shortcut,
            onValueChange = {
                shortcut = it
                validationError = null
                storageError = null
            },
            label = { Text(stringResource(R.string.quick_content_phrase_shortcut_label)) },
            supportingText = { Text(stringResource(R.string.quick_content_phrase_shortcut_hint)) },
            singleLine = true,
            enabled = !saving,
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                ),
            isError = validationError == PhraseInputError.SHORTCUT_TOO_LONG,
            modifier = Modifier.fillMaxWidth(),
        )
        validationError?.let { failure ->
            Text(
                text = phraseErrorText(failure),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        storageError?.let { code ->
            Text(
                text = contentErrorText(code),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun phraseErrorText(error: PhraseInputError): String =
    when (error) {
        PhraseInputError.TEXT_REQUIRED -> stringResource(R.string.quick_content_error_text_required)
        PhraseInputError.TEXT_TOO_LONG -> stringResource(R.string.quick_content_error_text_too_long)
        PhraseInputError.SHORTCUT_TOO_LONG -> stringResource(R.string.quick_content_error_shortcut_too_long)
        PhraseInputError.DUPLICATE -> stringResource(R.string.quick_content_error_duplicate)
        PhraseInputError.LIMIT_REACHED ->
            stringResource(R.string.quick_content_error_limit_reached, LocalQuickContentStore.MAXIMUM_PHRASES)
    }

@Composable
private fun contentErrorText(code: ContentErrorCode): String =
    when (code) {
        ContentErrorCode.NOT_A_REGULAR_FILE,
        ContentErrorCode.OVERSIZE,
        ContentErrorCode.MALFORMED_JSON,
        ContentErrorCode.NOT_AN_OBJECT,
        ContentErrorCode.UNKNOWN_FIELD,
        ContentErrorCode.UNSUPPORTED_VERSION,
        ContentErrorCode.INVALID_VALUE,
        -> stringResource(R.string.quick_content_error_invalid)

        ContentErrorCode.STORAGE_UNAVAILABLE -> stringResource(R.string.quick_content_error_storage)
        ContentErrorCode.LOCK_FAILED -> stringResource(R.string.quick_content_error_locked)
    }

@Composable
private fun transferMessageText(message: QuickContentMessage): String =
    when (message) {
        is QuickContentMessage.PhrasesImported -> stringResource(R.string.quick_content_phrases_imported, message.count)
        QuickContentMessage.PhrasesExported -> stringResource(R.string.quick_content_phrases_exported)
        is QuickContentMessage.SymbolsImported -> stringResource(R.string.quick_content_symbols_imported, message.count)
        QuickContentMessage.SymbolsExported -> stringResource(R.string.quick_content_symbols_exported)
        QuickContentMessage.SymbolsRestored -> stringResource(R.string.quick_content_symbols_restored)
        QuickContentMessage.FileUnreadable -> stringResource(R.string.quick_content_file_unreadable)
        QuickContentMessage.FileRejected -> stringResource(R.string.quick_content_file_rejected)
        QuickContentMessage.WriteFailed -> stringResource(R.string.quick_content_write_failed)
    }
