package app.songyan.ime.settings.ui.screens

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.songyan.ime.settings.R
import app.songyan.ime.settings.ui.EditorNotice
import app.songyan.ime.settings.ui.RimeFileEditorViewModel
import app.songyan.ime.settings.ui.RimeFileSaveError
import app.songyan.ime.settings.ui.components.SettingsScaffold

/**
 * Edits one of the user's configuration files in the app, so no other app is needed. Saving checks
 * the text first and says which line is wrong instead of writing a file the next apply would
 * refuse; leaving with unsaved text asks first. After a save the keyboard still uses the old
 * settings until they are applied, which the snackbar offers.
 */
@Composable
fun RimeFileEditorScreen(
    key: String,
    grammarExample: Boolean,
    onClose: () -> Unit,
    /** Applies the saved changes; the screen then closes, as the progress and result show on the previous page. */
    onApply: () -> Unit,
    viewModel: RimeFileEditorViewModel =
        viewModel(
            key = "rime-file-$key-$grammarExample",
            factory = RimeFileEditorViewModel.Factory(key, grammarExample),
        ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var confirmingDiscard by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val back = { if (state.dirty) confirmingDiscard = true else onClose() }
    BackHandler(onBack = back)

    LaunchedEffect(state.externalIntents) {
        val intents = state.externalIntents ?: return@LaunchedEffect
        val opened =
            intents.any { intent ->
                try {
                    context.startActivity(intent)
                    true
                } catch (_: ActivityNotFoundException) {
                    false
                }
            }
        viewModel.externalLaunched(opened)
    }
    val noticeText =
        when (val notice = state.notice) {
            EditorNotice.NoEditor -> stringResource(R.string.rime_config_no_editor)
            is EditorNotice.Failure -> notice.text ?: stringResource(R.string.rime_config_failed)
            null -> null
        }
    LaunchedEffect(noticeText) {
        if (noticeText != null) {
            snackbar.showSnackbar(noticeText)
            viewModel.noticeShown()
        }
    }
    val savedText = stringResource(R.string.rime_editor_saved)
    val applyLabel = stringResource(R.string.rime_config_apply)
    LaunchedEffect(state.saved) {
        if (state.saved) {
            val result = snackbar.showSnackbar(savedText, actionLabel = applyLabel, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) {
                onApply()
                onClose()
            }
        }
    }

    SettingsScaffold(
        title = state.title ?: stringResource(R.string.rime_config_edit_global),
        onBack = back,
        snackbarHost = { SnackbarHost(snackbar) },
        actions = {
            TextButton(onClick = viewModel::save, enabled = state.dirty && !state.saving && !state.failed) {
                Text(stringResource(R.string.rime_editor_save))
            }
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = !state.loading && !state.failed) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.rime_editor_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rime_editor_open_elsewhere)) },
                        onClick = {
                            menuOpen = false
                            viewModel.openElsewhere()
                        },
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding(),
        ) {
            if (state.loading || state.saving) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (state.failed) {
                Text(
                    text = state.loadFailure ?: stringResource(R.string.rime_editor_load_failed),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp).testTag(RIME_EDITOR_ERROR_TAG),
                )
            } else if (!state.loading) {
                if (state.fileName.endsWith(".lua") || SCRIPT_ENTRY.containsMatchIn(state.text)) {
                    Text(
                        text = stringResource(R.string.rime_editor_scripts_banner),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .testTag(RIME_EDITOR_SCRIPTS_TAG),
                    )
                }
                val error = state.error
                if (error != null) {
                    Text(
                        text =
                            when (error) {
                                is RimeFileSaveError.Invalid ->
                                    error.line?.let { stringResource(R.string.rime_editor_invalid_line, it) }
                                        ?: stringResource(R.string.rime_editor_invalid)
                                is RimeFileSaveError.Failed -> error.text ?: stringResource(R.string.rime_config_failed)
                            },
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag(RIME_EDITOR_ERROR_TAG),
                    )
                }
                OutlinedTextField(
                    value = state.text,
                    onValueChange = viewModel::edit,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions =
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                        ),
                    isError = error != null,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .verticalScroll(rememberScrollState())
                            .testTag(RIME_EDITOR_FIELD_TAG),
                )
            }
        }
    }

    if (confirmingDiscard) {
        AlertDialog(
            onDismissRequest = { confirmingDiscard = false },
            title = { Text(stringResource(R.string.rime_editor_discard_title)) },
            text = { Text(stringResource(R.string.rime_editor_discard_body)) },
            confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.layout_editor_discard)) } },
            dismissButton = {
                TextButton(onClick = { confirmingDiscard = false }) {
                    Text(stringResource(R.string.layout_editor_keep_editing))
                }
            },
        )
    }
}

/** A Lua component entry in a configuration file: what makes the file run a script. */
private val SCRIPT_ENTRY = Regex("(?m)^[^#\\n]*lua_(?:processor|translator|filter|segmentor)@")

internal const val RIME_EDITOR_SCRIPTS_TAG = "rime_editor_scripts"
internal const val RIME_EDITOR_FIELD_TAG = "rime_editor_field"
internal const val RIME_EDITOR_ERROR_TAG = "rime_editor_error"
