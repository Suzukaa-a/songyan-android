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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import app.songyan.ime.settings.ui.components.HideKeyboardOnEnter
import app.songyan.ime.settings.ui.components.LoadingScreen
import app.songyan.ime.settings.ui.components.SectionHeader
import app.songyan.ime.settings.ui.components.SettingsScaffold
import app.songyan.ime.settings.ui.components.StateMessage

/**
 * The confirmation page: what the script is called, what it will read, which sites it may connect
 * to and the settings it asks for, each in the declaration order the header carries. Secrets are
 * typed into masked fields, stay out of exports and are stored wrapped.
 *
 * The page is reached after every import and whenever the stored declarations change: the entry
 * says so with [ScriptEntry.confirmationPending]. A re-confirmation prefills the saved non-secret
 * values, and a blank secret field keeps the stored secret instead of wiping it; the explicit
 * "clear" checkbox is the only way to delete one. The confirm button sits in a bar pinned to the
 * bottom, so a long form never pushes it off screen.
 */
@Composable
fun ScriptConfirmScreen(
    scriptId: String,
    onDone: () -> Unit,
    viewModel: ScriptsViewModel = viewModel(factory = ScriptsViewModel.Factory()),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // The page is reached right after the paste dialog: the IME it raised can still be on screen
    // and would cover the form. Take it down as soon as the page appears.
    HideKeyboardOnEnter()
    val entry = state.entries.firstOrNull { it.id == scriptId }
    // What the user typed; the saved non-secret values are merged in once the proxy answers.
    var values by rememberSaveable(entry?.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    // The secret keys the user explicitly asked to delete; blank fields alone never delete one.
    var clearSecrets by rememberSaveable(entry?.id) { mutableStateOf<List<String>>(emptyList()) }
    var prefilled by rememberSaveable(entry?.id) { mutableStateOf(false) }
    val saved by
        produceState<Map<String, String>?>(initialValue = null, entry?.id) {
            value = entry?.let { viewModel.savedSettings(it.id) } ?: emptyMap()
        }
    LaunchedEffect(saved) {
        val loaded = saved ?: return@LaunchedEffect
        if (!prefilled && entry != null) {
            values = loaded
            prefilled = true
        }
    }

    if (state.loading) {
        // The catalog is still being read. Waiting here is what keeps "not loaded yet" from
        // looking like "the script is gone" and closing the page the user just opened.
        LoadingScreen(title = stringResource(R.string.scripts_confirm_title), onBack = onDone)
        return
    }

    LaunchedEffect(state.busy, state.pendingConfirmation) {
        // Confirming cleared the pending flag: the script is usable, the flow is over.
        if (!state.busy && state.pendingConfirmation == null && entry?.confirmationPending == false) {
            onDone()
        }
    }

    SettingsScaffold(
        title = stringResource(R.string.scripts_confirm_title),
        onBack = onDone,
    ) { innerPadding ->
        if (entry == null) {
            // The entry vanished (deleted elsewhere); nothing to confirm anymore.
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                StateMessage(title = stringResource(R.string.scripts_missing_title))
            }
            return@SettingsScaffold
        }
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 112.dp),
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
                        supportingContent = { Text(stringResource(readsBodyRes(entry.needsAudio))) },
                        leadingContent = { Icon(Icons.Outlined.Save, contentDescription = null) },
                    )
                }
                // The section header is always present, like "What it is": an empty list is a
                // statement under the heading, not a site row that happens to say "none".
                item { SectionHeader(stringResource(R.string.scripts_confirm_section_connects)) }
                if (entry.connects.isNotEmpty()) {
                    items(entry.connects.size) { index ->
                        ListItem(
                            headlineContent = { Text(entry.connects[index]) },
                            leadingContent = { Icon(Icons.Outlined.TravelExplore, contentDescription = null) },
                        )
                    }
                } else {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.scripts_confirm_connects_none),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = stringResource(R.string.scripts_confirm_connects_none_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (entry.settings.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.scripts_confirm_section_settings)) }
                    items(entry.settings.size) { index ->
                        val field = entry.settings[index]
                        SettingField(
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
                item {
                    Text(
                        text = stringResource(R.string.scripts_confirm_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            // Pinned action bar: the confirm button stays visible under a long form and rises with
            // the keyboard (imePadding) instead of being pushed off screen.
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 3.dp,
            ) {
                Column(modifier = Modifier.fillMaxWidth().imePadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Button(
                        onClick = { viewModel.confirm(entry.id, values, clearSecrets.toSet()) },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.scripts_confirm_action))
                    }
                }
            }
        }
    }
}

/**
 * The "what it can read" line: a voice script records the take and gets only that, while a text
 * script reads the selection, or the text before the cursor when nothing is selected. Both are
 * stated on the confirmation page because it is the user's only disclosure before the script runs.
 */
internal fun readsBodyRes(needsAudio: Boolean): Int =
    if (needsAudio) R.string.scripts_confirm_reads_audio else R.string.scripts_confirm_reads_body

/** One @setting or @secret of the header: a field labelled as declared, masked when secret. */
@Composable
private fun SettingField(
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        if (field.secret) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clickable { onClearSaved(!clearSaved) },
            ) {
                Checkbox(checked = clearSaved, onCheckedChange = onClearSaved)
                Text(stringResource(R.string.scripts_secret_clear))
            }
        }
    }
}
