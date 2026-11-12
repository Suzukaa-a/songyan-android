package app.songyan.ime.settings.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.songyan.ime.script.ScriptEntry
import app.songyan.ime.script.ScriptSettingField
import app.songyan.ime.script.ScriptSettingKind
import app.songyan.ime.settings.R
import app.songyan.ime.settings.ui.ScriptsViewModel
import app.songyan.ime.settings.ui.components.SectionHeader
import app.songyan.ime.settings.ui.components.SettingsScaffold

/**
 * The confirmation page: what the script is called, what it will read, which sites it may connect
 * to and the settings it asks for, each in the declaration order the header carries. Secrets are
 * typed into masked fields, stay out of exports and are stored wrapped.
 *
 * The page is reached after every import and whenever the stored declarations change: the entry
 * says so with [ScriptEntry.confirmationPending]. Confirming with the form as it stands is always
 * allowed — a script without settings needs nothing typed.
 */
@Composable
fun ScriptConfirmScreen(
    scriptId: String,
    onDone: () -> Unit,
    viewModel: ScriptsViewModel = viewModel(factory = ScriptsViewModel.Factory()),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val entry = state.entries.firstOrNull { it.id == scriptId }
    // The fields, in declaration order; a fresh map each time the entry changes.
    var values by rememberSaveable(entry?.id) {
        mutableStateOf(entry?.settings?.associate { it.key to "" } ?: emptyMap())
    }

    LaunchedEffect(state.busy, state.pendingConfirmation) {
        // Confirming cleared the pending flag: the script is usable, the flow is over.
        if (!state.busy && state.pendingConfirmation == null && entry?.confirmationPending == false && entry != null) {
            onDone()
        }
    }

    SettingsScaffold(
        title = stringResource(R.string.scripts_confirm_title),
        onBack = onDone,
    ) { innerPadding ->
        if (entry == null) {
            // The entry vanished (deleted elsewhere); nothing to confirm anymore.
            LaunchedEffect(Unit) { onDone() }
            return@SettingsScaffold
        }
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { SectionHeader(stringResource(R.string.scripts_confirm_section_what)) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.scripts_confirm_name)) },
                    supportingContent = { Text(entry.name) },
                    leadingContent = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.scripts_confirm_reads)) },
                    supportingContent = { Text(stringResource(R.string.scripts_confirm_reads_body)) },
                    leadingContent = { Icon(Icons.Outlined.Save, contentDescription = null) },
                )
            }
            if (entry.connects.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.scripts_confirm_section_connects)) }
                items(entry.connects.size) { index ->
                    ListItem(
                        headlineContent = { Text(entry.connects[index]) },
                        leadingContent = { Icon(Icons.Outlined.TravelExplore, contentDescription = null) },
                    )
                }
            } else {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.scripts_confirm_connects_none)) },
                        supportingContent = { Text(stringResource(R.string.scripts_confirm_connects_none_body)) },
                    )
                }
            }
            if (entry.settings.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.scripts_confirm_section_settings)) }
                items(entry.settings.size) { index ->
                    SettingField(entry.settings[index], values) { key, value ->
                        values =
                            values + (key to value)
                    }
                }
            }
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.scripts_confirm_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { viewModel.confirm(entry.id, values) },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.busy) {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                        }
                        Text(stringResource(R.string.scripts_confirm_action))
                    }
                }
            }
        }
    }
}

/** One @setting or @secret of the header: a field labelled as declared, masked when secret. */
@Composable
private fun SettingField(
    field: ScriptSettingField,
    values: Map<String, String>,
    onChange: (key: String, value: String) -> Unit,
) {
    var reveal by rememberSaveable(field.key) { mutableStateOf(false) }
    OutlinedTextField(
        value = values[field.key] ?: "",
        onValueChange = { onChange(field.key, it) },
        label = {
            Text(
                field.label +
                    if (field.secret) stringResource(R.string.scripts_secret_suffix) else "",
            )
        },
        supportingText = {
            Text(
                when (field.kind) {
                    ScriptSettingKind.URL -> stringResource(R.string.scripts_setting_kind_url)
                    ScriptSettingKind.CHOICE -> stringResource(R.string.scripts_setting_kind_choice)
                    ScriptSettingKind.TEXT -> stringResource(R.string.scripts_setting_kind_text)
                },
            )
        },
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
                            contentDescription = stringResource(R.string.scripts_secret_toggle),
                        )
                    }
                }
            } else {
                null
            },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
}
