package app.songyan.ime.settings.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.songyan.ime.content.StoredTool
import app.songyan.ime.content.ToolDefinition
import app.songyan.ime.content.ToolOrigin
import app.songyan.ime.content.ToolStore
import app.songyan.ime.settings.R
import app.songyan.ime.settings.state.IncomingToolHolder
import app.songyan.ime.settings.state.KeyboardToolsMessage
import app.songyan.ime.settings.state.ProcessTextApp
import app.songyan.ime.settings.state.toolNameTags
import app.songyan.ime.settings.ui.KeyboardToolsViewModel
import app.songyan.ime.settings.ui.components.SectionHeader
import app.songyan.ime.settings.ui.components.SettingsScaffold
import app.songyan.ime.settings.ui.components.StateMessage
import app.songyan.ime.settings.ui.toolProblemText

/** Test tag of one tool row's enable switch, so a UI test can flip exactly that tool. */
fun toolSwitchTag(id: String): String = "tool-enabled-" + id

/** Test tag of one tool row. */
fun toolRowTag(id: String): String = "tool-row-" + id

/** Test tag of one tool row's actions menu button, so a UI test can open exactly that tool. */
fun toolMenuTag(id: String): String = "tool-menu-" + id

/** Test tag of one other app's enable switch, so a UI test can turn exactly that app on. */
fun processTextSwitchTag(component: String): String = "process-text-enabled-" + component

/** Test tag of one other app's row. */
fun processTextRowTag(component: String): String = "process-text-row-" + component

/**
 * 键盘工具: the tools the keyboard offers, with enable, pin-to-toolbar, order, delete-with-undo,
 * import/export and the editor entry for the user's own tools.
 *
 * The list is read from the same store the keyboard uses, so a change here is the keyboard's next
 * read. New tools start as a copy of one of the three examples; the file format is not exposed for
 * hand editing beyond the name and step parameters.
 */
@Composable
fun KeyboardToolsScreen(
    onBack: (() -> Unit)?,
    onEditTool: (String) -> Unit,
    viewModel: KeyboardToolsViewModel = viewModel(factory = KeyboardToolsViewModel.Factory()),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pendingImport by IncomingToolHolder.uri.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    val tags = remember(configuration) { toolNameTags(configuration.locales) }
    val resolver = context.contentResolver
    var examplesOpen by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf<String?>(null) }
    var exporting by rememberSaveable { mutableStateOf<String?>(null) }

    // The tools live in this process next to the keyboard; re-read them whenever the page returns,
    // because the keyboard panel can change them while this screen is in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    val importer =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.importFile(resolver, uri)
        }
    val exporter =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val id = exporting
            exporting = null
            if (uri != null && id != null) viewModel.export(id, resolver, uri)
        }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message, resources, tags) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.currentSnackbarData?.dismiss()
        when (message) {
            is KeyboardToolsMessage.Deleted -> {
                val result =
                    snackbar.showSnackbar(
                        message = resources.getString(R.string.tools_deleted, message.definition.displayName(tags)),
                        actionLabel = resources.getString(R.string.tools_undo),
                        duration = SnackbarDuration.Long,
                    )
                if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete()
            }

            is KeyboardToolsMessage.Imported ->
                snackbar.showSnackbar(
                    resources.getString(R.string.tools_imported, message.definition.displayName(tags)),
                )

            is KeyboardToolsMessage.Exported ->
                snackbar.showSnackbar(
                    resources.getString(R.string.tools_exported, message.definition.displayName(tags)),
                )

            is KeyboardToolsMessage.Failed -> snackbar.showSnackbar(toolProblemText(context, message.problem))
        }
        viewModel.consumeMessage()
    }

    if (pendingImport != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissPendingImport,
            title = { Text(stringResource(R.string.tools_shared_title)) },
            text = { Text(stringResource(R.string.tools_shared_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::importPending) {
                    Text(stringResource(R.string.tools_import_action))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissPendingImport) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (examplesOpen) {
        ExamplePickerDialog(
            examples = state.builtinExamples,
            onDismiss = { examplesOpen = false },
            onPick = { example ->
                examplesOpen = false
                viewModel.copyExample(example) { id -> onEditTool(id) }
            },
        )
    }

    confirmDelete?.let { id ->
        val tool = state.tools.firstOrNull { it.id == id }
        if (tool != null) {
            AlertDialog(
                onDismissRequest = { confirmDelete = null },
                title = {
                    Text(
                        stringResource(
                            R.string.tools_delete_title,
                            tool.definition.displayName(tags),
                        ),
                    )
                },
                text = { Text(stringResource(R.string.tools_delete_body)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmDelete = null
                            viewModel.delete(id)
                        },
                    ) {
                        Text(stringResource(R.string.tools_delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
        }
    }

    SettingsScaffold(
        title = stringResource(R.string.tools_title),
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        if (state.loading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@SettingsScaffold
        }
        if (state.tools.isEmpty()) {
            StateMessage(
                title = stringResource(R.string.tools_load_failed),
                icon = Icons.Outlined.ErrorOutline,
                actionLabel = stringResource(R.string.action_retry),
                onAction = viewModel::refresh,
                modifier = Modifier.padding(innerPadding),
            )
            return@SettingsScaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                SectionHeader(
                    text = stringResource(R.string.tools_section_list),
                    trailing =
                        stringResource(
                            R.string.tools_pinned_counter,
                            state.pinnedCount,
                            ToolStore.MAXIMUM_PINNED,
                        ),
                )
            }
            items(state.tools, key = { it.id }) { tool ->
                ToolListItem(
                    tool = tool,
                    name = tool.definition.displayName(tags),
                    canMoveEarlier = state.tools.indexOfFirst { it.id == tool.id } > 0,
                    canMoveLater = state.tools.indexOfFirst { it.id == tool.id } < state.tools.lastIndex,
                    onEnabledChange = { enabled -> viewModel.setEnabled(tool.id, enabled) },
                    onEdit = { onEditTool(tool.id) },
                    onPin = { viewModel.setPinned(tool.id, true) },
                    onUnpin = { viewModel.setPinned(tool.id, false) },
                    onMoveEarlier = { viewModel.moveEarlier(tool.id) },
                    onMoveLater = { viewModel.moveLater(tool.id) },
                    onExport = {
                        exporting = tool.id
                        exporter.launch(tool.id + TOOL_FILE_SUFFIX)
                    },
                    onCopy = {
                        viewModel.copyExample(tool.definition) { id -> onEditTool(id) }
                    },
                    onDelete = { confirmDelete = tool.id },
                )
            }

            val processTextApps = state.processTextApps
            if (processTextApps != null) {
                item { SectionHeader(stringResource(R.string.tools_process_text_section)) }
                item {
                    SectionDescription(stringResource(R.string.tools_process_text_body))
                }
                when {
                    state.processTextUnavailable ->
                        item { SectionDescription(stringResource(R.string.tools_process_text_unavailable)) }

                    processTextApps.isEmpty() ->
                        item { SectionDescription(stringResource(R.string.tools_process_text_empty)) }

                    else ->
                        items(processTextApps, key = { "process-text-" + it.component }) { app ->
                            ProcessTextAppListItem(
                                app = app,
                                enabled = app.component in state.processTextEnabled,
                                onEnabledChange = { enabled ->
                                    viewModel.setProcessTextEnabled(app.component, enabled)
                                },
                            )
                        }
                }
            }

            item { SectionHeader(stringResource(R.string.tools_section_manage)) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.tools_add_from_example)) },
                    supportingContent = { Text(stringResource(R.string.tools_add_from_example_body)) },
                    leadingContent = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    modifier =
                        Modifier.clickable(role = Role.Button) {
                            examplesOpen = true
                        },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.tools_import)) },
                    supportingContent = { Text(stringResource(R.string.tools_import_body)) },
                    leadingContent = { Icon(Icons.Outlined.Build, contentDescription = null) },
                    modifier =
                        Modifier.clickable(role = Role.Button) {
                            importer.launch(IMPORT_TYPES)
                        },
                )
            }
        }
    }
}

/** One tool: its state, the enable switch, and the actions menu (pin, order, edit, export, delete). */
@Composable
private fun ToolListItem(
    tool: StoredTool,
    name: String,
    canMoveEarlier: Boolean,
    canMoveLater: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onPin: () -> Unit,
    onUnpin: () -> Unit,
    onMoveEarlier: () -> Unit,
    onMoveLater: () -> Unit,
    onExport: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val origin = stringResource(toolOriginText(tool.origin))
    val pinned = if (tool.metadata.pinned) stringResource(R.string.tools_pinned) else null
    val steps = stringResource(R.string.tools_steps_count, tool.definition.steps.size)
    val subtitle = listOfNotNull(pinned, origin, steps).joinToString(" · ")
    ListItem(
        headlineContent = { Text(name) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(Icons.Outlined.Build, contentDescription = null) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.testTag(toolMenuTag(tool.id)),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = stringResource(R.string.tools_more),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (tool.origin == ToolOrigin.USER) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.tools_edit)) },
                                onClick = {
                                    menuOpen = false
                                    onEdit()
                                },
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.tools_copy)) },
                                onClick = {
                                    menuOpen = false
                                    onCopy()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                val label = if (tool.metadata.pinned) R.string.tools_unpin else R.string.tools_pin
                                Text(stringResource(label))
                            },
                            onClick = {
                                menuOpen = false
                                if (tool.metadata.pinned) onUnpin() else onPin()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.tools_move_up)) },
                            enabled = canMoveEarlier,
                            onClick = {
                                menuOpen = false
                                onMoveEarlier()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.tools_move_down)) },
                            enabled = canMoveLater,
                            onClick = {
                                menuOpen = false
                                onMoveLater()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.tools_export)) },
                            onClick = {
                                menuOpen = false
                                onExport()
                            },
                        )
                        if (tool.origin == ToolOrigin.USER) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.tools_delete)) },
                                onClick = {
                                    menuOpen = false
                                    onDelete()
                                },
                            )
                        }
                    }
                }
                Switch(
                    checked = tool.metadata.enabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier.testTag(toolSwitchTag(tool.id)),
                )
            }
        },
        modifier =
            Modifier
                .testTag(toolRowTag(tool.id))
                .clickable(role = Role.Button) {
                    if (tool.origin == ToolOrigin.USER) onEdit() else menuOpen = true
                },
    )
}

/** One muted sentence under a section heading: what the group below does. */
@Composable
private fun SectionDescription(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * One other app: its icon, its name and the switch that lists it in the keyboard's tools panel.
 *
 * None is on by default; turning one on is the user's explicit choice, and the keyboard only then
 * offers it beside its own tools.
 */
@Composable
private fun ProcessTextAppListItem(
    app: ProcessTextApp,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val icon = app.icon
    val bitmap =
        remember(app.component, density) {
            icon?.let { drawable ->
                val pixels = with(density) { PROCESS_TEXT_ICON.roundToPx() }
                runCatching { drawable.toBitmap(pixels, pixels).asImageBitmap() }.getOrNull()
            }
        }
    val switchLabel = stringResource(R.string.tools_process_text_switch, app.label)
    ListItem(
        headlineContent = { Text(app.label) },
        leadingContent = {
            if (bitmap != null) {
                Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(PROCESS_TEXT_ICON))
            } else {
                Icon(Icons.Outlined.Build, contentDescription = null, modifier = Modifier.size(PROCESS_TEXT_ICON))
            }
        },
        trailingContent = {
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                modifier =
                    Modifier
                        .testTag(processTextSwitchTag(app.component))
                        .semantics { contentDescription = switchLabel },
            )
        },
        modifier = Modifier.testTag(processTextRowTag(app.component)),
    )
}

/** The three examples a new tool can start from. */
@Composable
private fun ExamplePickerDialog(
    examples: List<ToolDefinition>,
    onDismiss: () -> Unit,
    onPick: (ToolDefinition) -> Unit,
) {
    val configuration = LocalConfiguration.current
    val tags = remember(configuration) { toolNameTags(configuration.locales) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tools_examples_title)) },
        text = {
            Column {
                examples.forEach { example ->
                    ListItem(
                        headlineContent = { Text(example.displayName(tags)) },
                        supportingContent = {
                            Text(stringResource(R.string.tools_steps_count, example.steps.size))
                        },
                        modifier = Modifier.clickable(role = Role.Button) { onPick(example) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/** Where a listed tool came from. */
private fun toolOriginText(origin: ToolOrigin): Int =
    when (origin) {
        ToolOrigin.BUILTIN -> R.string.tools_origin_builtin
        ToolOrigin.USER -> R.string.tools_origin_user
    }

private const val TOOL_FILE_SUFFIX = ".songyantool.json"

/** Size of one other app's icon in the list; the Material list's own leading icon size. */
private val PROCESS_TEXT_ICON = 24.dp

/** MIME types a tool file can arrive with from a file manager or a chat app. */
private val IMPORT_TYPES =
    arrayOf("application/json", "application/octet-stream", "text/plain")
