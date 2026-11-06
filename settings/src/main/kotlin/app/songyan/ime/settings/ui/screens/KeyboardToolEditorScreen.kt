package app.songyan.ime.settings.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.songyan.ime.content.OpenCCToolConfig
import app.songyan.ime.content.ToolDefinition
import app.songyan.ime.content.ToolDefinitionLimits
import app.songyan.ime.content.ToolStep
import app.songyan.ime.settings.R
import app.songyan.ime.settings.state.ToolStepKind
import app.songyan.ime.settings.ui.KeyboardToolsViewModel
import app.songyan.ime.settings.ui.components.SectionHeader
import app.songyan.ime.settings.ui.components.SettingsScaffold
import app.songyan.ime.settings.ui.components.StateMessage
import app.songyan.ime.settings.ui.toolCaseModeText
import app.songyan.ime.settings.ui.toolNameLanguageText
import app.songyan.ime.settings.ui.toolProblemText
import app.songyan.ime.settings.ui.toolStepKindText
import app.songyan.ime.settings.ui.toolWidthModeText

/** Test tag of the save-failure row, so a UI test can read why a draft was refused. */
fun toolEditorProblemTag(): String = "tool-editor-problem"

/** Test tag of the name field for [tag], so a UI test can rename exactly that language. */
fun toolNameFieldTag(tag: String): String = "tool-name-" + tag

/**
 * The editor for one user tool: its four authored names and its steps.
 *
 * Only name and step parameters are editable; there is no script and no visual regex tester, which
 * matches what a tool can do. Saving validates through the same format model an imported file goes
 * through, so a draft that would be refused as a file is refused here with the same explanation.
 */
@Composable
fun KeyboardToolEditorScreen(
    toolId: String,
    onBack: () -> Unit,
    viewModel: KeyboardToolsViewModel = viewModel(factory = KeyboardToolsViewModel.Factory()),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val editor = state.editor
    LaunchedEffect(toolId) { viewModel.openEditor(toolId) }
    var addStepOpen by rememberSaveable { mutableStateOf(false) }
    var mapIndex by rememberSaveable { mutableStateOf<Int?>(null) }

    SettingsScaffold(
        title = stringResource(R.string.tools_editor_title),
        onBack = onBack,
        actions = {
            TextButton(
                onClick = { viewModel.saveEditor(onSaved = onBack) },
                enabled = editor != null && !editor.saving,
            ) {
                Text(stringResource(R.string.tools_editor_save))
            }
        },
    ) { innerPadding ->
        when {
            editor != null -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    editor.problem?.let { problem ->
                        item(key = "problem") {
                            Text(
                                text = toolProblemText(context, problem),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier =
                                    Modifier
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                        .testTag(toolEditorProblemTag()),
                            )
                        }
                    }
                    item(key = "names_header") { SectionHeader(stringResource(R.string.tools_editor_name_section)) }
                    items(ToolDefinition.NAME_LANGUAGE_TAGS, key = { "name-" + it }) { tag ->
                        OutlinedTextField(
                            value = editor.draft.names[tag].orEmpty(),
                            onValueChange = { value -> viewModel.updateName(tag, value) },
                            label = { Text(toolNameLanguageText(context, tag)) },
                            singleLine = true,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                                    .testTag(toolNameFieldTag(tag)),
                        )
                    }
                    item(key = "steps_header") {
                        SectionHeader(
                            text = stringResource(R.string.tools_editor_steps_section),
                            trailing =
                                stringResource(
                                    R.string.tools_editor_step_counter,
                                    editor.draft.steps.size,
                                    ToolDefinitionLimits.MAXIMUM_STEPS,
                                ),
                        )
                    }
                    itemsIndexed(editor.draft.steps) { index, step ->
                        StepEditor(
                            index = index,
                            step = step,
                            onChange = { changed -> viewModel.updateStep(index, changed) },
                            onRemove = { viewModel.removeStep(index) },
                            onEditMap = { mapIndex = index },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    item(key = "add_step") {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.tools_editor_add_step)) },
                            modifier =
                                Modifier.clickable(
                                    role = Role.Button,
                                    enabled = editor.canAddStep,
                                ) {
                                    if (editor.canAddStep) addStepOpen = true
                                },
                        )
                    }
                    if (!editor.canAddStep) {
                        item(key = "step_limit") {
                            Text(
                                text =
                                    stringResource(
                                        R.string.tools_editor_step_limit,
                                        ToolDefinitionLimits.MAXIMUM_STEPS,
                                    ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }

            state.loading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            else -> {
                StateMessage(
                    title = stringResource(R.string.tools_editor_tool_missing),
                    icon = null,
                    actionLabel = stringResource(R.string.action_back),
                    onAction = onBack,
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
    }

    if (addStepOpen) {
        AddStepDialog(
            onDismiss = { addStepOpen = false },
            onPick = { kind ->
                addStepOpen = false
                viewModel.addStep(kind)
            },
        )
    }

    mapIndex?.let { index ->
        val step = editor?.draft?.steps?.getOrNull(index) as? ToolStep.Map ?: return@let
        MapTableDialog(
            initial = step.table,
            onDismiss = { mapIndex = null },
            onConfirm = { table ->
                mapIndex = null
                viewModel.updateStep(index, ToolStep.Map(table))
            },
        )
    }
}

/** One step of the draft, with its type-specific parameters. */
@Composable
private fun StepEditor(
    index: Int,
    step: ToolStep,
    onChange: (ToolStep) -> Unit,
    onRemove: () -> Unit,
    onEditMap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val kind = toolStepKindOf(step)
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text =
                        stringResource(
                            R.string.tools_editor_step_number,
                            index + 1,
                            toolStepKindText(context, kind),
                        ),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.tools_editor_remove_step),
                    )
                }
            }
            when (step) {
                is ToolStep.OpenCC ->
                    ChoiceField(
                        label = stringResource(R.string.tools_step_opencc_config),
                        value = step.config.wireName,
                        options = OpenCCToolConfig.entries.map { it.wireName to it.wireName },
                        onSelect = { name ->
                            OpenCCToolConfig.fromWireName(name)?.let { config -> onChange(step.copy(config = config)) }
                        },
                    )

                is ToolStep.CaseTransform ->
                    ChoiceField(
                        label = stringResource(R.string.tools_step_case_mode),
                        value = toolCaseModeText(context, step.mode.wireName),
                        options =
                            ToolStep.CaseMode.entries.map {
                                it.wireName to toolCaseModeText(context, it.wireName)
                            },
                        onSelect = { name ->
                            ToolStep.CaseMode.entries
                                .firstOrNull { it.wireName == name }
                                ?.let { mode -> onChange(step.copy(mode = mode)) }
                        },
                    )

                is ToolStep.Width ->
                    ChoiceField(
                        label = stringResource(R.string.tools_step_width_mode),
                        value = toolWidthModeText(context, step.mode.wireName),
                        options =
                            ToolStep.WidthMode.entries.map {
                                it.wireName to toolWidthModeText(context, it.wireName)
                            },
                        onSelect = { name ->
                            ToolStep.WidthMode.entries
                                .firstOrNull { it.wireName == name }
                                ?.let { mode -> onChange(step.copy(mode = mode)) }
                        },
                    )

                is ToolStep.Replace -> {
                    OutlinedTextField(
                        value = step.pattern,
                        onValueChange = { value -> onChange(step.copy(pattern = value)) },
                        label = { Text(stringResource(R.string.tools_step_replace_pattern)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = step.replacement,
                        onValueChange = { value -> onChange(step.copy(replacement = value)) },
                        label = { Text(stringResource(R.string.tools_step_replace_replacement)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.tools_step_replace_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is ToolStep.Template ->
                    OutlinedTextField(
                        value = step.pattern,
                        onValueChange = { value -> onChange(step.copy(pattern = value)) },
                        label = { Text(stringResource(R.string.tools_step_template_pattern)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text(stringResource(R.string.tools_step_template_hint)) },
                    )

                is ToolStep.Map -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text =
                                stringResource(
                                    R.string.tools_step_map_summary,
                                    step.table.size,
                                ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onEditMap) {
                            Text(stringResource(R.string.tools_step_map_edit))
                        }
                    }
                    Text(
                        text = stringResource(R.string.tools_step_map_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** A read-only value with a menu; used for the closed sets a step can choose from. */
@Composable
private fun ChoiceField(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { open = true }
                    .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(text = value, style = MaterialTheme.typography.bodyLarge)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        open = false
                        onSelect(id)
                    },
                )
            }
        }
    }
}

/** The closed list of step types a new step can be. */
@Composable
private fun AddStepDialog(
    onDismiss: () -> Unit,
    onPick: (ToolStepKind) -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tools_editor_add_step)) },
        text = {
            Column {
                ToolStepKind.entries.forEach { kind ->
                    ListItem(
                        headlineContent = { Text(toolStepKindText(context, kind)) },
                        modifier = Modifier.clickable(role = Role.Button) { onPick(kind) },
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

/**
 * Edits a map step's table as one line per entry.
 *
 * A line without a separator is refused here, before the format validator runs, so the user is not
 * told only that "a step is wrong" when the fix is "add the =".
 */
@Composable
private fun MapTableDialog(
    initial: Map<String, String>,
    onDismiss: () -> Unit,
    onConfirm: (Map<String, String>) -> Unit,
) {
    var text by rememberSaveable(initial) { mutableStateOf(mapTableText(initial)) }
    var invalid by rememberSaveable(initial) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tools_step_map_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        invalid = false
                    },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.tools_step_map_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (invalid) {
                    Text(
                        text = stringResource(R.string.tools_step_map_error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val table = parseMapTable(text)
                    if (table == null) invalid = true else onConfirm(table)
                },
            ) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/** The step type of [step], used for its header and for the add-step list. */
private fun toolStepKindOf(step: ToolStep): ToolStepKind =
    when (step) {
        is ToolStep.OpenCC -> ToolStepKind.OPENCC
        is ToolStep.Replace -> ToolStepKind.REPLACE
        is ToolStep.Map -> ToolStepKind.MAP
        is ToolStep.CaseTransform -> ToolStepKind.CASE
        is ToolStep.Width -> ToolStepKind.WIDTH
        is ToolStep.Template -> ToolStepKind.TEMPLATE
    }

/** One line per entry, in a stable order, so saving without changes keeps the text as it was. */
internal fun mapTableText(table: Map<String, String>): String =
    table.keys.sorted().joinToString("\n") { key -> key + "=" + table.getValue(key) }

/** The table a multiline edit produced, or null when a line carries no "=". */
internal fun parseMapTable(text: String): Map<String, String>? {
    val table = LinkedHashMap<String, String>()
    for (line in text.lines()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        val separator = trimmed.indexOfFirst { it == '=' || it == '\uFF1D' }
        if (separator <= 0) return null
        val key = trimmed.substring(0, separator).trim()
        if (key.isEmpty()) return null
        table[key] = trimmed.substring(separator + 1).trim()
    }
    return table
}
