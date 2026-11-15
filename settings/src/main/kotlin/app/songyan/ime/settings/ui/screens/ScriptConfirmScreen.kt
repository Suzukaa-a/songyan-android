package app.songyan.ime.settings.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.songyan.ime.script.ScriptEntry
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

    // A script the user decides against is removed from here: a pending script has no detail page.
    var deleting by remember { mutableStateOf(false) }
    if (deleting && entry != null) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.scripts_delete_title)) },
            text = { Text(stringResource(R.string.scripts_delete_body, entry.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(entry.id)
                        deleting = false
                        onDone()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.scripts_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.scripts_cancel)) }
            },
        )
    }

    SettingsScaffold(
        title = stringResource(R.string.scripts_confirm_title),
        onBack = onDone,
        actions = {
            if (entry != null) {
                IconButton(onClick = { deleting = true }) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.scripts_delete))
                }
            }
        },
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
        // A url setting is a site too: the script reaches whatever address the user fills in.
        val sites = connectRows(entry, typedRes = R.string.scripts_connects_typed_below)
        // The form and the action bar sit one above the other, and the keyboard shrinks both: the
        // list's viewport ends at the bar, so a focused field scrolls into view instead of under it.
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .imePadding(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item { SectionHeader(stringResource(R.string.scripts_confirm_section_what)) }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.scripts_confirm_name)) },
                        supportingContent = { Text(entry.name) },
                        leadingContent = { Icon(Icons.Outlined.Description, contentDescription = null) },
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.scripts_confirm_reads)) },
                        supportingContent = { Text(stringResource(readsBodyRes(entry.needsAudio))) },
                        leadingContent = { Icon(Icons.Outlined.Visibility, contentDescription = null) },
                    )
                }
                // The section header is always present, like "What it is": an empty list is a
                // statement under the heading, not a site row that happens to say "none".
                item { SectionHeader(stringResource(R.string.scripts_confirm_section_connects)) }
                if (sites.isNotEmpty()) {
                    items(sites.size) { index ->
                        ListItem(
                            headlineContent = { Text(sites[index]) },
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
                entry.timeoutSeconds?.let { seconds ->
                    item {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.scripts_confirm_timeout)) },
                            supportingContent = { Text(runLimitText(seconds)) },
                            leadingContent = { Icon(Icons.Outlined.Timer, contentDescription = null) },
                        )
                    }
                }
                if (entry.settings.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.scripts_confirm_section_settings)) }
                    items(entry.settings.size) { index ->
                        val field = entry.settings[index]
                        ScriptSettingInput(
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
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
                // Only a script that asks for a secret needs to be told where secrets live.
                if (entry.settings.any { it.secret }) {
                    item {
                        Text(
                            text = stringResource(R.string.scripts_confirm_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            // The confirm button stays visible under a long form and rises with the keyboard.
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 3.dp,
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Button(
                        onClick = {
                            viewModel.confirm(
                                entry.id,
                                withChoiceDefaults(entry.settings, values),
                                clearSecrets.toSet(),
                            )
                        },
                        enabled = !state.busy && !hasUnreachableAddress(entry.settings, values),
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

/** The sites a script may reach, as the confirmation and detail pages list them: its declared
 *  hosts, then one row for the addresses typed into its url settings. */
@Composable
internal fun connectRows(
    entry: ScriptEntry,
    typedRes: Int = R.string.scripts_connects_typed,
): List<String> {
    val typed =
        if (entry.settings.any { it.kind == ScriptSettingKind.URL }) {
            listOf(stringResource(typedRes))
        } else {
            emptyList()
        }
    return entry.connects + typed
}

/** "Up to N seconds", in minutes from two minutes up. */
@Composable
internal fun runLimitText(seconds: Int): String =
    if (seconds >= 120) {
        pluralStringResource(R.plurals.scripts_run_limit_minutes, seconds / 60, seconds / 60)
    } else {
        pluralStringResource(R.plurals.scripts_run_limit_seconds, seconds, seconds)
    }

/**
 * The "what it can read" line: a voice script records the take and gets only that, while a text
 * script reads the selection, or the text before the cursor when nothing is selected. Both are
 * stated on the confirmation page because it is the user's only disclosure before the script runs.
 */
internal fun readsBodyRes(needsAudio: Boolean): Int =
    if (needsAudio) R.string.scripts_confirm_reads_audio else R.string.scripts_confirm_reads_body
