package app.songyan.ime.settings.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.songyan.ime.settings.R
import app.songyan.ime.settings.state.RimeEditTarget
import app.songyan.ime.settings.state.RimeOverrideFile
import app.songyan.ime.settings.ui.RimeConfigUiState
import app.songyan.ime.settings.ui.ScriptConfirmation
import app.songyan.ime.settings.ui.components.SectionHeader

/**
 * The user's own Rime configuration: where to edit it (the Files app) and the one action that
 * makes the keyboard use the edits. The keyboard never changes while the user is still editing.
 */
internal fun LazyListScope.rimeConfigRows(
    state: RimeConfigUiState,
    busy: Boolean,
    onOpen: () -> Unit,
    onApply: () -> Unit,
    onEdit: () -> Unit,
    onRestore: (String) -> Unit,
) {
    item(key = "rime_config_header") { SectionHeader(stringResource(R.string.rime_config_section)) }
    item(key = "rime_config_body") {
        Text(
            text = stringResource(R.string.rime_config_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
    item(key = "rime_config_status") {
        val info = state.info
        val error = info?.failure
        val status =
            when {
                state.applying -> stringResource(R.string.rime_config_applying)
                error != null -> error
                info?.notice != null -> info.notice
                info == null -> null
                info.pending -> stringResource(R.string.rime_config_pending)
                info.fileCount == 0 -> stringResource(R.string.rime_config_empty)
                else -> pluralStringResource(R.plurals.rime_config_in_use, info.fileCount, info.fileCount)
            }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (status != null) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        if (error != null &&
                            !state.applying
                        ) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    modifier = Modifier.testTag(RIME_CONFIG_STATUS_TAG),
                )
            }
            if (state.applying) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
    item(key = "rime_config_actions") {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onOpen,
                enabled = state.available,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.rime_config_open))
            }
            OutlinedButton(
                onClick = onEdit,
                enabled = state.available && !state.applying && state.info != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.rime_config_edit))
            }
            Button(
                onClick = onApply,
                enabled = state.canApply && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.rime_config_apply))
            }
        }
    }
    changedFileRows(state.info?.overrides.orEmpty(), onRestore)
}

/** Files the user changed (each with its way back) and the notices about them; one list item per file. */
private fun LazyListScope.changedFileRows(
    files: List<RimeOverrideFile>,
    onRestore: (String) -> Unit,
) {
    if (files.isEmpty()) return
    item(key = "rime_config_changed_header") { SectionHeader(stringResource(R.string.rime_config_changed_files)) }
    items(files, key = { "rime_config_changed_" + it.path }) { file ->
        ListItem(
            headlineContent = { Text(file.path) },
            supportingContent =
                if (file.updated) {
                    { Text(stringResource(R.string.rime_config_file_updated, file.path)) }
                } else {
                    null
                },
            trailingContent = {
                TextButton(onClick = { onRestore(file.path) }) { Text(stringResource(R.string.rime_config_restore)) }
            },
        )
    }
}

/** Which input method's file to open; its own file, or the one every input method shares. */
@Composable
internal fun RimeEditPickerDialog(
    targets: List<RimeEditTarget>,
    onPick: (RimeEditTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rime_config_edit)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                for (target in targets + RimeEditTarget.GLOBAL) {
                    ListItem(
                        headlineContent = { Text(target.name ?: stringResource(R.string.rime_config_edit_global)) },
                        modifier = Modifier.clickable { onPick(target) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Asked before applying: scripts that are new or changed run inside the keyboard, so the user decides. */
@Composable
internal fun RimeScriptConfirmDialog(
    confirmation: ScriptConfirmation,
    onUse: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title =
            if (confirmation.firstTime) {
                { Text(stringResource(R.string.rime_config_scripts_first_title)) }
            } else {
                null
            },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(
                        if (confirmation.firstTime) {
                            R.string.rime_config_scripts_first_body
                        } else {
                            R.string.rime_config_scripts_changed
                        },
                    ),
                )
                for (script in confirmation.scripts) {
                    Text(script, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onUse) { Text(stringResource(R.string.rime_config_scripts_use)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Told once per file: it no longer follows the input scheme's updates. */
@Composable
internal fun RimeOverrideNoticeDialog(
    paths: List<String>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.rime_config_override_notice))
                for (path in paths) {
                    Text(path, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.rime_config_override_ok)) } },
    )
}

/** Test tag of the status line under the configuration's description. */
internal const val RIME_CONFIG_STATUS_TAG = "rime_config_status"
