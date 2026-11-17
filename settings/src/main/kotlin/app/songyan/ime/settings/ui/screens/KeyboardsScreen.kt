package app.songyan.ime.settings.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.songyan.ime.keyboard.engine.KeyboardResourceSet
import app.songyan.ime.keyboard.engine.PublishedKeyboard
import app.songyan.ime.layout.LayoutDefinition
import app.songyan.ime.settings.R
import app.songyan.ime.settings.state.KeyboardGroup
import app.songyan.ime.settings.state.KeyboardLayoutOption
import app.songyan.ime.settings.state.KeyboardList
import app.songyan.ime.settings.state.KeyboardStatus
import app.songyan.ime.settings.state.SettingsPreferences
import app.songyan.ime.settings.state.SettingsPreferencesHolder
import app.songyan.ime.settings.state.StoredSourceInfo
import app.songyan.ime.settings.state.generatedLayout
import app.songyan.ime.settings.state.layoutDisplayName
import app.songyan.ime.settings.ui.components.RadioRow
import app.songyan.ime.settings.ui.components.SectionHeader
import app.songyan.ime.settings.ui.components.SettingsRow
import app.songyan.ime.settings.ui.components.SettingsScaffold
import app.songyan.ime.settings.ui.text
import kotlinx.coroutines.launch

/**
 * 我的键盘: the keyboards the keyboard menu switches between, grouped by input method. A keyboard is
 * an input method on one layout; picking a row switches to it, the button at its end takes it out
 * of the menu, and each input method can be given more layouts. The keyboard in use cannot be
 * removed, so the menu always shows where the keyboard is. The input methods of the other input
 * schemes follow under their scheme's name; the ones left on can be switched to from the menu.
 */
@Composable
fun KeyboardsScreen(
    list: KeyboardList,
    onUse: (PublishedKeyboard) -> Unit,
    onAdd: (PublishedKeyboard, LayoutDefinition?) -> Unit,
    onRemove: (PublishedKeyboard) -> Unit,
    onBack: (() -> Unit)?,
    otherSources: List<StoredSourceInfo> = emptyList(),
    preferences: SettingsPreferences = SettingsPreferencesHolder.current,
    onOpenLayouts: () -> Unit = {},
    onOpenPages: () -> Unit = {},
    onOpenComposition: () -> Unit = {},
    onOpenSchemes: () -> Unit = {},
) {
    var addingTo by rememberSaveable { mutableStateOf<String?>(null) }
    val hiddenSchemas by preferences.menuHiddenSchemas.collectAsStateWithLifecycle(emptySet())
    val scope = rememberCoroutineScope()
    val inUse = stringResource(R.string.keyboard_menu_in_use)
    SettingsScaffold(
        title = stringResource(R.string.row_keyboards_title),
        onBack = onBack,
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp),
        ) {
            Text(
                text =
                    stringResource(
                        if (list.groups.isEmpty()) R.string.keyboards_current_none else R.string.keyboards_body,
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            for (group in list.groups) {
                SectionHeader(group.schemeName.ifEmpty { group.schemeId })
                if (group.unknownCodes) {
                    Text(
                        text = stringResource(R.string.keyboards_unknown_codes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                Column(modifier = Modifier.selectableGroup()) {
                    for (row in group.keyboards) {
                        val name = row.layoutName.text()
                        val missing =
                            if (row.missingCodes.isEmpty()) {
                                null
                            } else {
                                stringResource(R.string.keyboards_missing_codes, row.missingCodes.joinToString(" "))
                            }
                        val state =
                            when (row.status) {
                                KeyboardStatus.READY -> null
                                KeyboardStatus.PREPARING -> stringResource(R.string.keyboards_preparing)
                                KeyboardStatus.UNAVAILABLE -> stringResource(R.string.keyboards_unavailable)
                            }
                        RadioRow(
                            title = name,
                            enabled = row.status == KeyboardStatus.READY,
                            subtitle =
                                listOfNotNull(
                                    inUse.takeIf { row.inUse },
                                    state,
                                    missing,
                                ).joinToString("\n").ifEmpty { null },
                            selected = row.inUse,
                            onClick = { onUse(row.keyboard) },
                            trailing = {
                                // The keyboard in use stays in the menu, so it has nothing to remove.
                                if (!row.inUse) {
                                    IconButton(onClick = { onRemove(row.keyboard) }) {
                                        Icon(
                                            Icons.Outlined.Close,
                                            contentDescription =
                                                stringResource(
                                                    R.string.keyboards_remove,
                                                    stringResource(R.string.keyboard_title, group.schemeName, name),
                                                ),
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
                if (group.addable.isNotEmpty()) {
                    SettingsRow(
                        title = stringResource(R.string.keyboards_add),
                        subtitle =
                            if (group.keyboards.isEmpty()) stringResource(R.string.keyboards_none_for_method) else null,
                        icon = Icons.Outlined.Add,
                        onClick = { addingTo = group.schemeId },
                    )
                }
            }
            for (source in otherSources) {
                OtherSourceKeyboards(
                    source = source,
                    hidden = hiddenSchemas,
                    onChange = { shown, id ->
                        scope.launch {
                            preferences.setMenuHiddenSchemas(
                                if (shown) {
                                    hiddenSchemas - id
                                } else {
                                    hiddenSchemas +
                                        id
                                },
                            )
                        }
                    },
                )
            }

            SectionHeader(stringResource(R.string.keyboards_pages_section))
            SettingsRow(
                title = stringResource(R.string.composition_title),
                subtitle =
                    stringResource(
                        if (list.current ==
                            null
                        ) {
                            R.string.composition_needs_scheme
                        } else {
                            R.string.composition_entry_body
                        },
                    ),
                onClick = if (list.current == null) onOpenSchemes else onOpenComposition,
            )
            SettingsRow(
                title = stringResource(R.string.layouts_title),
                subtitle = stringResource(R.string.layouts_entry_body),
                onClick = onOpenLayouts,
            )
            SettingsRow(
                title = stringResource(R.string.pages_title),
                subtitle = stringResource(R.string.pages_entry_body),
                onClick = onOpenPages,
            )
        }
    }
    list.groups.firstOrNull { it.schemeId == addingTo }?.let { group ->
        AddKeyboardDialog(
            group = group,
            onAdd = { keyboard, layout ->
                onAdd(keyboard, layout)
                addingTo = null
            },
            onDismiss = { addingTo = null },
        )
    }
}

/** The layouts an input method can still be given a keyboard on; picking one adds it. */
@Composable
internal fun AddKeyboardDialog(
    group: KeyboardGroup,
    onAdd: (PublishedKeyboard, LayoutDefinition?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.keyboards_add_title, group.schemeName.ifEmpty { group.schemeId })) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                for (row in group.addable) {
                    val title = row.layoutName.text()
                    SettingsRow(
                        title = title,
                        subtitle =
                            row.missingCodes.takeIf { it.isNotEmpty() }?.let {
                                stringResource(R.string.keyboards_missing_codes, it.joinToString(" "))
                            },
                        onClick = { onAdd(row.keyboard, group.generatedLayout(row, title)) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.schemes_dictionary_cancel)) }
        },
    )
}

/**
 * The input methods of another input scheme as keyboards. Their layouts are not known until that
 * scheme is in use, so each is listed on the 26-key keyboard; taking one out hides it from the
 * keyboard menu, and the ones taken out can be added back.
 */
@Composable
private fun OtherSourceKeyboards(
    source: StoredSourceInfo,
    hidden: Set<String>,
    onChange: (shown: Boolean, schemeId: String) -> Unit,
) {
    val schemes = source.schemes.filterNot { KeyboardResourceSet.EncodingAdapter.isGeneratedID(it.id) }
    if (schemes.isEmpty()) return
    var adding by rememberSaveable(source.name) { mutableStateOf(false) }
    val layout = layoutDisplayName(null, KeyboardLayoutOption.QWERTY26).text()
    SectionHeader(source.name)
    for (scheme in schemes.filter { it.id !in hidden }) {
        SettingsRow(
            title = scheme.name,
            subtitle = layout,
            onClick = {},
            trailing = {
                IconButton(onClick = { onChange(false, scheme.id) }) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription =
                            stringResource(
                                R.string.keyboards_remove,
                                stringResource(R.string.keyboard_title, scheme.name, layout),
                            ),
                    )
                }
            },
        )
    }
    val removed = schemes.filter { it.id in hidden }
    if (removed.isNotEmpty()) {
        SettingsRow(
            title = stringResource(R.string.keyboards_add),
            subtitle = null,
            icon = Icons.Outlined.Add,
            onClick = { adding = true },
        )
    }
    if (adding && removed.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.keyboards_add_title, source.name)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    for (scheme in removed) {
                        SettingsRow(
                            title = scheme.name,
                            subtitle = layout,
                            onClick = {
                                onChange(true, scheme.id)
                                adding = false
                            },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { adding = false }) { Text(stringResource(R.string.schemes_dictionary_cancel)) }
            },
        )
    }
}
