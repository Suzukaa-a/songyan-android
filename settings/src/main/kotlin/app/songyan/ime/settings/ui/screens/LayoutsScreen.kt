package app.songyan.ime.settings.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.songyan.ime.keyboard.engine.LayoutFamilyContext
import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.KeyboardCompositionKey
import app.songyan.ime.layout.KeyboardPageDefinition
import app.songyan.ime.layout.KeyboardPageKind
import app.songyan.ime.layout.KeyboardSymbolPresentation
import app.songyan.ime.layout.LayoutDefinition
import app.songyan.ime.layout.LayoutRegistry
import app.songyan.ime.settings.R
import app.songyan.ime.settings.state.KeyboardLayoutOption
import app.songyan.ime.settings.state.layoutDisplayName
import app.songyan.ime.settings.state.pageDisplayName
import app.songyan.ime.settings.ui.LayoutLibraryViewModel
import app.songyan.ime.settings.ui.LibraryEvent
import app.songyan.ime.settings.ui.components.LoadingScreen
import app.songyan.ime.settings.ui.components.SectionHeader
import app.songyan.ime.settings.ui.components.SettingsRow
import app.songyan.ime.settings.ui.components.SettingsScaffold
import app.songyan.ime.settings.ui.components.SwitchRow
import app.songyan.ime.settings.ui.isEditablePage
import app.songyan.ime.settings.ui.text

/** The sentence that reports what a library action did. */
@Composable
internal fun libraryEventText(event: LibraryEvent): String =
    when (event) {
        is LibraryEvent.Imported ->
            stringResource(
                if (event.copied) R.string.layouts_import_copied else R.string.layouts_import_done,
                event.name,
            )
        LibraryEvent.Exported -> stringResource(R.string.layouts_export_done)
        LibraryEvent.ExportFailed -> stringResource(R.string.layouts_export_failed)
        is LibraryEvent.Failed -> layoutErrorMessage(event.error)
    }

/** Shows each library event once in a snackbar. */
@Composable
internal fun LibraryEvents(
    viewModel: LayoutLibraryViewModel,
    snackbar: SnackbarHostState,
) {
    val event by viewModel.event.collectAsStateWithLifecycle()
    val text = event?.let { libraryEventText(it) }
    LaunchedEffect(text) {
        if (text != null) {
            snackbar.showSnackbar(text)
            viewModel.consumeEvent()
        }
    }
}

/**
 * 键盘布局: the bundled layouts and the user's own. A layout can be edited, copied, exported and (the
 * user's own) deleted; a bundled one that was edited can be put back. A layout file from the iOS app
 * imports here, and one exported here imports there.
 */
@Composable
fun LayoutsScreen(
    onBack: (() -> Unit)?,
    onEdit: (String) -> Unit,
    onOpenPages: () -> Unit,
    showSwitchKey: Boolean = false,
    onShowSwitchKeyChange: (Boolean) -> Unit = {},
    switchKeyEnabled: Boolean = true,
    onUse: (String) -> Unit = {},
    viewModel: LayoutLibraryViewModel = viewModel(factory = LayoutLibraryViewModel.Factory()),
) {
    val registry by viewModel.registry.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<LayoutDefinition?>(null) }
    val copySuffix = stringResource(R.string.layout_copy_suffix)
    val importer =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.importLayout(uri)
        }
    val exporter =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val id = exportId
            exportId = null
            if (uri != null && id != null) viewModel.exportLayout(id, uri)
        }
    LaunchedEffect(Unit) { viewModel.refresh() }
    LibraryEvents(viewModel, snackbar)

    val current = registry
    if (current == null) {
        LoadingScreen(stringResource(R.string.layouts_title), onBack)
        return
    }
    SettingsScaffold(
        title = stringResource(R.string.layouts_title),
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
                        padding,
                    ).verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp),
        ) {
            SectionHeader(stringResource(R.string.layouts_section))
            current.layouts.forEach { layout ->
                val builtIn = BuiltinLayouts.byId(layout.id) != null
                val edited = builtIn && layout != BuiltinLayouts.byId(layout.id)
                val layoutName = layoutDisplayName(layout, KeyboardLayoutOption.fromId(layout.id)).text()
                ListItem(
                    modifier =
                        Modifier.clickable(onClickLabel = stringResource(R.string.layouts_action_edit)) {
                            onEdit(layout.id)
                        },
                    headlineContent = { Text(layoutName) },
                    supportingContent = {
                        Text(
                            stringResource(
                                when {
                                    edited -> R.string.layouts_note_edited
                                    builtIn -> R.string.layouts_note_builtin
                                    else -> R.string.layouts_note_mine
                                },
                            ),
                        )
                    },
                    trailingContent = {
                        LayoutMenu(
                            name = layoutName,
                            canEdit = true,
                            canCopy = true,
                            canDelete = !builtIn,
                            canRestore = edited,
                            onUse = { onUse(layout.id) },
                            onEdit = { onEdit(layout.id) },
                            onCopy = { viewModel.duplicateLayout(layout.id, layoutName + copySuffix) },
                            onExport = {
                                exportId = layout.id
                                exporter.launch(viewModel.exportFileName(layout.id))
                            },
                            onDelete = { deleting = layout },
                            onRestore = { viewModel.restorePreset(layout.id) },
                        )
                    },
                )
            }
            SectionHeader(stringResource(R.string.layouts_keys_section))
            SwitchRow(
                title = stringResource(R.string.layouts_switch_key_title),
                subtitle = stringResource(R.string.layouts_switch_key_body),
                checked = showSwitchKey,
                onCheckedChange = onShowSwitchKeyChange,
                enabled = switchKeyEnabled,
            )
            SectionHeader(stringResource(R.string.layouts_files_section))
            SettingsRow(
                title = stringResource(R.string.layouts_import_title),
                subtitle = stringResource(R.string.layouts_import_body),
                onClick = { importer.launch(arrayOf("*/*")) },
            )
            SettingsRow(
                title = stringResource(R.string.pages_title),
                subtitle = stringResource(R.string.pages_entry_body),
                onClick = onOpenPages,
            )
        }
    }
    deleting?.let { layout ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.layouts_delete_title)) },
            text = { Text(stringResource(R.string.layouts_delete_body, layout.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteLayout(layout.id)
                        deleting = null
                    },
                ) { Text(stringResource(R.string.layouts_action_delete)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { deleting = null },
                ) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun LayoutMenu(
    name: String,
    canEdit: Boolean,
    canCopy: Boolean,
    canDelete: Boolean,
    canRestore: Boolean,
    onUse: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.layouts_more_actions, name))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuItem(R.string.layouts_action_use, onUse) { open = false }
            if (canEdit) MenuItem(R.string.layouts_action_edit, onEdit) { open = false }
            if (canCopy) MenuItem(R.string.layouts_action_copy, onCopy) { open = false }
            MenuItem(R.string.layouts_action_export, onExport) { open = false }
            if (canRestore) MenuItem(R.string.layouts_action_restore, onRestore) { open = false }
            if (canDelete) MenuItem(R.string.layouts_action_delete, onDelete) { open = false }
        }
    }
}

@Composable
private fun MenuItem(
    label: Int,
    action: () -> Unit,
    close: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        onClick = {
            close()
            action()
        },
    )
}

/**
 * 页面库: the 123, symbol and ABC pages a keyboard can use. A bundled page is copied before it is
 * edited; the copy is the user's own and can be edited and deleted.
 */
@Composable
fun PagesScreen(
    onBack: (() -> Unit)?,
    onEdit: (String) -> Unit,
    /** Names of the input methods a pairing may belong to, by id; one without a name is named by its layout. */
    inputMethodNames: Map<String, String> = BuiltinTemplateSchemas.NAMES,
    /** The families the settings app state already resolved; unknown ids keep the id-only rule. */
    families: LayoutFamilyContext = LayoutFamilyContext.UNKNOWN,
    onUse: (String) -> Unit = {},
    viewModel: LayoutLibraryViewModel = viewModel(factory = LayoutLibraryViewModel.Factory()),
) {
    val registry by viewModel.registry.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var deleting by remember { mutableStateOf<KeyboardPageDefinition?>(null) }
    val copySuffix = stringResource(R.string.layout_copy_suffix)
    LaunchedEffect(Unit) { viewModel.refresh() }
    LibraryEvents(viewModel, snackbar)

    val current = registry
    if (current == null) {
        LoadingScreen(stringResource(R.string.pages_title), onBack)
        return
    }
    SettingsScaffold(
        title = stringResource(R.string.pages_title),
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
                        padding,
                    ).verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp),
        ) {
            for (kind in KeyboardPageKind.entries) {
                SectionHeader(stringResource(pageKindLabel(kind)))
                current.pages.filter { it.kind == kind }.forEach { page ->
                    val preset = isPresetPage(page)
                    val usage = keyboardUsage(current.keyboardsUsingPage(page.id), inputMethodNames, current, families)
                    val pageName = pageDisplayName(page).text()
                    ListItem(
                        headlineContent = { Text(pageName) },
                        supportingContent = {
                            val note =
                                stringResource(
                                    when {
                                        !page.isEditablePage && !preset -> R.string.pages_note_fixed
                                        preset -> R.string.pages_note_builtin
                                        else -> R.string.pages_note_mine
                                    },
                                )
                            Text(if (usage == null) note else note + "\n" + usage)
                        },
                        trailingContent = {
                            PageMenu(
                                name = pageName,
                                canEdit = !preset && page.isEditablePage,
                                canCopy = page.symbolPresentation != KeyboardSymbolPresentation.AUTOMATIC,
                                canDelete = !preset,
                                onUse = { onUse(page.id) },
                                onEdit = { onEdit(page.id) },
                                onCopy = { viewModel.duplicatePage(page.id, pageName + copySuffix) },
                                onDelete = { deleting = page },
                            )
                        },
                    )
                }
            }
        }
    }
    deleting?.let { page ->
        // A page some keyboard still uses cannot go; the dialog says which keyboards, since the
        // pairing screen only shows the keyboard in use.
        val usage = keyboardUsage(current.keyboardsUsingPage(page.id), inputMethodNames, current, families)
        if (usage != null) {
            AlertDialog(
                onDismissRequest = { deleting = null },
                title = { Text(stringResource(R.string.pages_delete_in_use_title)) },
                text = { Text(stringResource(R.string.pages_delete_in_use_body, page.name, usage)) },
                confirmButton = {
                    TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_ok)) }
                },
            )
            return
        }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.pages_delete_title)) },
            text = { Text(stringResource(R.string.pages_delete_body, page.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deletePage(page.id)
                        deleting = null
                    },
                ) { Text(stringResource(R.string.layouts_action_delete)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { deleting = null },
                ) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** "Used by 「…」" for the keyboards in [keys], or null when none uses the page. */
@Composable
private fun keyboardUsage(
    keys: List<KeyboardCompositionKey>,
    inputMethodNames: Map<String, String>,
    registry: LayoutRegistry,
    families: LayoutFamilyContext,
): String? {
    val first = keys.firstOrNull() ?: return null
    val shape = KeyboardLayoutOption.fromId(first.layout.wire)
    // A keyboard that draws a physical board is named by that board, not by the shape it shares.
    val mainLayout = registry.resolvedLayout(registry.composition(first, families).mainLayoutID)
    val layout = layoutDisplayName(mainLayout, shape).text()
    val title =
        inputMethodNames[first.inputMethodId]?.let { stringResource(R.string.keyboard_title, it, layout) } ?: layout
    return if (keys.size == 1) {
        stringResource(R.string.pages_used_by_one, title)
    } else {
        stringResource(R.string.pages_used_by_many, title, keys.size)
    }
}

private fun isPresetPage(page: KeyboardPageDefinition): Boolean =
    app.songyan.ime.layout.KeyboardPagePresets.all
        .any { it.id == page.id }

internal fun pageKindLabel(kind: KeyboardPageKind): Int =
    when (kind) {
        KeyboardPageKind.NUMERIC -> R.string.pages_kind_numeric
        KeyboardPageKind.SYMBOLS -> R.string.pages_kind_symbols
        KeyboardPageKind.ENGLISH -> R.string.pages_kind_english
    }

@Composable
private fun PageMenu(
    name: String,
    canEdit: Boolean,
    canCopy: Boolean,
    canDelete: Boolean,
    onUse: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.layouts_more_actions, name))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuItem(R.string.layouts_action_use, onUse) { open = false }
            if (canEdit) MenuItem(R.string.layouts_action_edit, onEdit) { open = false }
            if (canCopy) MenuItem(R.string.layouts_action_copy, onCopy) { open = false }
            if (canDelete) MenuItem(R.string.layouts_action_delete, onDelete) { open = false }
        }
    }
}
