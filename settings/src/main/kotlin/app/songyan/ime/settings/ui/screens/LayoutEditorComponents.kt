package app.songyan.ime.settings.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutAuxiliaryArea
import app.songyan.ime.layout.LayoutInput
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutKeyTitleMode
import app.songyan.ime.layout.LayoutLanguageSwitchBehavior
import app.songyan.ime.layout.LayoutOption
import app.songyan.ime.layout.LayoutPageJump
import app.songyan.ime.layout.LayoutPageTarget
import app.songyan.ime.layout.LayoutResourceBudget
import app.songyan.ime.layout.LayoutRow
import app.songyan.ime.layout.LayoutSplitConfiguration
import app.songyan.ime.layout.PhoneKeypadRailPolicy
import app.songyan.ime.settings.R
import app.songyan.ime.settings.ui.JumpKeyEditing
import app.songyan.ime.settings.ui.KeyActionChoice
import app.songyan.ime.settings.ui.KeyGridEditing
import app.songyan.ime.settings.ui.KeySelection
import app.songyan.ime.settings.ui.RailSelection
import app.songyan.ime.settings.ui.components.RadioRow
import app.songyan.ime.settings.ui.components.SectionHeader
import kotlin.math.roundToInt

/** The key area as the user will see it: rows of caps with relative widths; tap one to edit it. */
@Composable
fun KeyGrid(
    rows: List<LayoutRow>,
    selection: KeySelection?,
    onSelect: (KeySelection) -> Unit,
    modifier: Modifier = Modifier,
    /** Draws every automatic literal as the Chinese (false) or the English (true) mark; null keeps the saved label. */
    asciiPreview: Boolean? = null,
) {
    Column(modifier = modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEachIndexed { rowIndex, row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.keys.forEachIndexed { keyIndex, key ->
                    val at = KeySelection(rowIndex, keyIndex)
                    val selected = at == selection
                    val face = if (asciiPreview == null) key.label else key.face(asciiPreview)
                    val description =
                        stringResource(R.string.layout_editor_key_description, rowIndex + 1, keyIndex + 1, face)
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color =
                            if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                        modifier =
                            Modifier
                                .weight(key.widthWeight.toFloat().coerceAtLeast(MIN_KEY_WEIGHT))
                                .height(KEY_HEIGHT)
                                .semantics { contentDescription = description }
                                .selectable(selected = selected, onClick = { onSelect(at) }),
                    ) {
                        Text(
                            text = face,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier.padding(horizontal = 2.dp).wrapCentre(),
                        )
                    }
                }
            }
        }
    }
}

/** A title typed by hand wins over the automatic one, or the field would not change what the key shows. */
internal fun keyWithTypedLabel(
    key: LayoutKey,
    label: String,
): LayoutKey = if (key.titleMode == LayoutKeyTitleMode.AUTOMATIC) key.withLabel(label) else key.copy(label = label)

/** A key with two variants shows the one it types in the current branch, until its title is typed by hand. */
internal fun keyWithAsciiText(
    key: LayoutKey,
    text: String,
): LayoutKey {
    val ascii = text.ifEmpty { null }
    val title = if (ascii != null && key.titleMode == null) LayoutKeyTitleMode.AUTOMATIC else key.titleMode
    return key.copy(action = key.action.withAsciiValue(ascii), titleMode = title)
}

private val KEY_HEIGHT = 44.dp
private const val MIN_KEY_WEIGHT = 0.3f

private fun Modifier.wrapCentre(): Modifier = this.fillMaxWidth().padding(top = 11.dp)

/** What the grid toolbar can do for the selected key; a disabled action is greyed out. */
data class GridActions(
    val canMoveLeft: Boolean,
    val canMoveRight: Boolean,
    val canMoveRowUp: Boolean,
    val canMoveRowDown: Boolean,
    val canAddKey: Boolean,
    val canAddRow: Boolean,
    val canRemoveKey: Boolean,
) {
    companion object {
        fun of(
            rows: List<LayoutRow>,
            at: KeySelection?,
        ): GridActions {
            val template = LayoutKey("x", action = LayoutAction.literal("x"))
            return GridActions(
                canMoveLeft = at != null && KeyGridEditing.moveKey(rows, at, -1) != null,
                canMoveRight = at != null && KeyGridEditing.moveKey(rows, at, +1) != null,
                canMoveRowUp = at != null && KeyGridEditing.moveRow(rows, at, -1) != null,
                canMoveRowDown = at != null && KeyGridEditing.moveRow(rows, at, +1) != null,
                canAddKey = at != null && KeyGridEditing.addKeyAfter(rows, at, template) != null,
                canAddRow = KeyGridEditing.addRow(rows, template) != null,
                canRemoveKey = at != null && KeyGridEditing.removeKey(rows, at) != null,
            )
        }
    }
}

/** The row of buttons under the grid: move the selected key or row, add a key or a row, remove the key. */
@Composable
fun GridToolbar(
    actions: GridActions,
    onMoveKey: (Int) -> Unit,
    onMoveRow: (Int) -> Unit,
    onAddKey: () -> Unit,
    onRemoveKey: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        ToolbarButton(Icons.AutoMirrored.Filled.ArrowBack, R.string.layout_editor_move_left, actions.canMoveLeft) {
            onMoveKey(-1)
        }
        ToolbarButton(Icons.AutoMirrored.Filled.ArrowForward, R.string.layout_editor_move_right, actions.canMoveRight) {
            onMoveKey(+1)
        }
        ToolbarButton(
            Icons.Filled.ArrowUpward,
            R.string.layout_editor_move_row_up,
            actions.canMoveRowUp,
        ) { onMoveRow(-1) }
        ToolbarButton(Icons.Filled.ArrowDownward, R.string.layout_editor_move_row_down, actions.canMoveRowDown) {
            onMoveRow(+1)
        }
        ToolbarButton(Icons.Filled.Add, R.string.layout_editor_add_key, actions.canAddKey, onAddKey)
        ToolbarButton(Icons.Filled.Delete, R.string.layout_editor_remove_key, actions.canRemoveKey, onRemoveKey)
    }
}

@Composable
internal fun ToolbarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(imageVector = icon, contentDescription = stringResource(label))
    }
}

/** The fields of the selected key: titles, width, what a tap does, and the long-press choices. */
@Composable
fun KeyFields(
    key: LayoutKey,
    allowCode: Boolean,
    onChange: (LayoutKey) -> Unit,
    /** The key sits in a side column: its width is a relative height there. */
    span: Boolean = false,
    /** The key only types text (a category key); the function actions are not offered. */
    literalOnly: Boolean = false,
    /** The key may jump to another layout's page (a page's key, not a main layout's). */
    allowJump: Boolean = false,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = key.label,
            // A title typed by hand wins over the automatic one, or the field would not change what the key shows.
            onValueChange = { onChange(keyWithTypedLabel(key, it)) },
            label = { Text(stringResource(R.string.layout_editor_key_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = key.secondaryLabel.orEmpty(),
            onValueChange = { onChange(key.copy(secondaryLabel = it.ifEmpty { null })) },
            label = { Text(stringResource(R.string.layout_editor_key_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        WidthField(key.width, span) { onChange(key.copy(width = it)) }
        ActionFields(key, allowCode, literalOnly, allowJump, onChange)
        LongPressFields(key, onChange)
    }
}

/** The relative width, a positive number or empty for the standard width; a bad entry is flagged, not applied. */
@Composable
private fun WidthField(
    width: Double?,
    span: Boolean,
    onChange: (Double?) -> Unit,
) {
    var text by remember(width) { mutableStateOf(width?.let(::formatWidth).orEmpty()) }
    val parsed = if (text.isBlank()) null else text.toDoubleOrNull()
    val valid = text.isBlank() || (parsed != null && parsed > 0.0 && parsed.isFinite())
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            val value = if (it.isBlank()) null else it.toDoubleOrNull()
            if (it.isBlank() || (value != null && value > 0.0 && value.isFinite())) onChange(value)
        },
        label = {
            Text(
                stringResource(if (span) R.string.layout_editor_key_span else R.string.layout_editor_key_width),
            )
        },
        supportingText = {
            Text(
                stringResource(
                    when {
                        !valid -> R.string.layout_editor_key_width_invalid
                        span -> R.string.layout_editor_key_span_hint
                        else -> R.string.layout_editor_key_width_hint
                    },
                ),
            )
        },
        isError = !valid,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun formatWidth(value: Double): String =
    if (value ==
        value.toLong().toDouble()
    ) {
        value.toLong().toString()
    } else {
        value.toString()
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionFields(
    key: LayoutKey,
    allowCode: Boolean,
    literalOnly: Boolean,
    allowJump: Boolean,
    onChange: (LayoutKey) -> Unit,
) {
    val choices = if (literalOnly) listOf(KeyActionChoice.LITERAL) else KeyActionChoice.allowed(allowCode, allowJump)
    val current = KeyActionChoice.of(key.action)
    val pageLabels = jumpPageLabels()
    Dropdown(
        label = stringResource(R.string.layout_editor_key_action),
        selectedText = current?.let { stringResource(actionLabel(it)) } ?: key.action.type.name,
        options = choices.map { stringResource(actionLabel(it)) },
        onSelect = { index ->
            val choice = choices[index]
            onChange(
                if (choice == KeyActionChoice.JUMP && current != KeyActionChoice.JUMP) {
                    // A new jump key is named after the page it opens, like the page's own switch.
                    val target = choice.actionFor(key).layoutPageJump ?: LayoutPageJump.all.first()
                    JumpKeyEditing.withTarget(key.copy(label = ""), target, pageLabels)
                } else {
                    key.copy(action = choice.actionFor(key))
                },
            )
        },
    )
    when (current) {
        KeyActionChoice.CODE ->
            OutlinedTextField(
                value = key.action.value.orEmpty(),
                onValueChange = { onChange(key.copy(action = LayoutAction.code(it))) },
                label = { Text(stringResource(R.string.layout_editor_action_code)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        KeyActionChoice.LITERAL -> {
            OutlinedTextField(
                value = key.action.value.orEmpty(),
                onValueChange = { onChange(key.copy(action = key.action.copy(value = it))) },
                label = { Text(stringResource(R.string.layout_editor_action_text)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = key.action.asciiValue.orEmpty(),
                onValueChange = { onChange(keyWithAsciiText(key, it)) },
                label = { Text(stringResource(R.string.layout_editor_action_ascii)) },
                supportingText = { Text(stringResource(R.string.layout_editor_action_ascii_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        KeyActionChoice.PAGE_SWITCH -> {
            val targets = LayoutPageTarget.entries
            Dropdown(
                label = stringResource(R.string.layout_editor_action_page),
                selectedText = stringResource(pageTargetLabel(key.action.pageSwitchTarget ?: LayoutPageTarget.NUMERIC)),
                options = targets.map { stringResource(pageTargetLabel(it)) },
                onSelect = { onChange(key.copy(action = LayoutAction.pageSwitch(targets[it]))) },
            )
        }
        KeyActionChoice.JUMP -> {
            val targets = LayoutPageJump.all
            val names = targets.map { jumpTargetName(it) }
            val selected = key.action.layoutPageJump
            Dropdown(
                label = stringResource(R.string.layout_editor_action_jump),
                selectedText = selected?.let { jumpTargetName(it) }.orEmpty(),
                options = names,
                onSelect = { onChange(JumpKeyEditing.withTarget(key, targets[it], pageLabels)) },
            )
            Text(
                stringResource(R.string.layout_editor_action_jump_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        KeyActionChoice.LANGUAGE_SWITCH -> {
            val behaviors = listOf<LayoutLanguageSwitchBehavior?>(null) + LayoutLanguageSwitchBehavior.entries
            Dropdown(
                label = stringResource(R.string.layout_editor_action_language),
                selectedText = stringResource(languageBehaviorLabel(key.action.languageSwitchBehavior)),
                options = behaviors.map { stringResource(languageBehaviorLabel(it)) },
                onSelect = { onChange(key.copy(action = LayoutAction.languageSwitch(behaviors[it]))) },
            )
        }
        KeyActionChoice.GLOBE ->
            // iOS decides a globe key's visibility itself, so the stored layout carries no flag for it.
            Text(
                stringResource(R.string.layout_action_globe_hint, stringResource(R.string.layouts_switch_key_title)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        else -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dropdown(
    label: String,
    selectedText: String,
    options: List<String>,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedText,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        expanded = false
                        onSelect(index)
                    },
                )
            }
        }
    }
}

private fun actionLabel(choice: KeyActionChoice): Int =
    when (choice) {
        KeyActionChoice.CODE -> R.string.layout_action_code
        KeyActionChoice.LITERAL -> R.string.layout_action_literal
        KeyActionChoice.SPACE -> R.string.layout_action_space
        KeyActionChoice.BACKSPACE -> R.string.layout_action_backspace
        KeyActionChoice.ENTER -> R.string.layout_action_enter
        KeyActionChoice.SHIFT -> R.string.layout_action_shift
        KeyActionChoice.LANGUAGE_SWITCH -> R.string.layout_action_language
        KeyActionChoice.PAGE_SWITCH -> R.string.layout_action_page
        KeyActionChoice.JUMP -> R.string.layout_action_jump
        KeyActionChoice.SYMBOL_LOCK -> R.string.layout_action_symbol_lock
        KeyActionChoice.CLEAR_INPUT -> R.string.layout_action_clear
        KeyActionChoice.MODE_SWITCH -> R.string.layout_action_next_layout
        KeyActionChoice.GLOBE -> R.string.layout_action_globe
    }

private fun pageTargetLabel(target: LayoutPageTarget): Int =
    when (target) {
        LayoutPageTarget.LETTERS -> R.string.layout_page_letters
        LayoutPageTarget.NUMERIC -> R.string.layout_page_numeric
        LayoutPageTarget.SYMBOLS -> R.string.layout_page_symbols
    }

/** The label a jump key gets for each page it can open: the page's own switch label. */
@Composable
private fun jumpPageLabels(): (LayoutPageTarget) -> String {
    val numeric = stringResource(R.string.layout_page_numeric)
    val symbols = stringResource(R.string.layout_page_symbols)
    val letters = stringResource(R.string.layout_page_letters)
    return { page ->
        when (page) {
            LayoutPageTarget.NUMERIC -> numeric
            LayoutPageTarget.SYMBOLS -> symbols
            LayoutPageTarget.LETTERS -> letters
        }
    }
}

/** "9 键 · 123": the layout by its user-visible name, then the page. */
@Composable
private fun jumpTargetName(target: LayoutPageJump): String =
    stringResource(
        R.string.layout_jump_target,
        stringResource(layoutNameRes(target.layout)),
        stringResource(pageTargetLabel(target.page)),
    )

private fun layoutNameRes(layout: LayoutInput.Builtin): Int =
    when (layout) {
        LayoutInput.Builtin.QWERTY26 -> R.string.layout_name_26
        LayoutInput.Builtin.MERGED14 -> R.string.layout_name_14
        LayoutInput.Builtin.MERGED18 -> R.string.layout_name_18
        LayoutInput.Builtin.PHONE9 -> R.string.layout_name_9
    }

private fun languageBehaviorLabel(behavior: LayoutLanguageSwitchBehavior?): Int =
    when (behavior) {
        null -> R.string.layout_language_follow
        LayoutLanguageSwitchBehavior.SYMBOLS -> R.string.layout_language_symbols
        LayoutLanguageSwitchBehavior.LETTERS -> R.string.layout_language_letters
    }

/** The long-press choices of a key: each is a text the key types when it is picked. */
@Composable
private fun LongPressFields(
    key: LayoutKey,
    onChange: (LayoutKey) -> Unit,
) {
    val options = key.longPress.orEmpty()
    Column {
        Text(
            text = stringResource(R.string.layout_editor_long_press),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        options.forEachIndexed { index, option ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = option.label,
                    onValueChange = { text ->
                        onChange(
                            key.copy(
                                longPress =
                                    options.toMutableList().also {
                                        it[index] =
                                            LayoutOption(text, LayoutAction.literal(text))
                                    },
                            ),
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f).padding(vertical = 2.dp),
                )
                IconButton(
                    onClick = {
                        val rest = options.filterIndexed { i, _ -> i != index }
                        onChange(key.copy(longPress = rest.ifEmpty { null }))
                    },
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.layout_editor_long_press_remove),
                    )
                }
            }
        }
        TextButton(
            onClick = { onChange(key.copy(longPress = options + LayoutOption(" ", LayoutAction.literal(" ")))) },
            enabled = options.size < MAX_LONG_PRESS,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.layout_editor_long_press_add), modifier = Modifier.padding(start = 4.dp))
        }
    }
}

private const val MAX_LONG_PRESS = 16

/**
 * How the layout or page splits on a wide surface: when, how wide the gutter is, and after which key
 * each row is cut. [rows] are the rows being edited, so the cuts line up with what the grid shows.
 */
@Composable
fun SplitEditor(
    rows: List<LayoutRow>,
    split: LayoutSplitConfiguration?,
    onChange: (LayoutSplitConfiguration?) -> Unit,
) {
    val activation = split?.activation ?: LayoutSplitConfiguration.Activation.OFF
    Column {
        SectionHeader(stringResource(R.string.layout_editor_split_section))
        listOf(
            LayoutSplitConfiguration.Activation.OFF to R.string.layout_editor_split_off,
            LayoutSplitConfiguration.Activation.AUTOMATIC to R.string.layout_editor_split_automatic,
            LayoutSplitConfiguration.Activation.ALWAYS to R.string.layout_editor_split_always,
        ).forEach { (value, label) ->
            RadioRow(
                title = stringResource(label),
                selected = activation == value,
                onClick = {
                    // Off keeps an existing split (its cuts and spacing) as an explicit off; a layout
                    // that never had one stays without.
                    val next =
                        when {
                            value == LayoutSplitConfiguration.Activation.OFF && split == null -> null
                            else -> (split ?: LayoutSplitConfiguration()).copy(activation = value)
                        }
                    onChange(next)
                },
            )
        }
        if (split != null && activation != LayoutSplitConfiguration.Activation.OFF) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.layout_editor_split_gap_automatic)) },
                trailingContent = {
                    Switch(checked = split.automaticGap, onCheckedChange = { onChange(split.copy(automaticGap = it)) })
                },
                modifier = Modifier.clickable { onChange(split.copy(automaticGap = !split.automaticGap)) },
            )
            if (!split.automaticGap) {
                var gap by remember(split.gapFraction) { mutableStateOf(split.gapFraction.toFloat()) }
                ListItem(
                    headlineContent = {
                        Text(
                            stringResource(R.string.layout_editor_split_gap, (gap * 100).roundToInt()),
                        )
                    },
                    supportingContent = {
                        Slider(
                            value = gap,
                            onValueChange = { gap = it },
                            onValueChangeFinished = { onChange(split.copy(gapFraction = gap.toDouble())) },
                            valueRange = 0f..MAX_GAP,
                        )
                    },
                )
            }
            rows.forEachIndexed { index, row ->
                if (row.keys.size < 2) return@forEachIndexed
                val cut = split.breakIndex(index, row.keys.size)
                ListItem(
                    headlineContent = { Text(stringResource(R.string.layout_editor_split_row, index + 1, cut)) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { onChange(withCut(split, rows, index, cut - 1)) },
                                enabled = cut > 1,
                            ) {
                                Icon(
                                    Icons.Filled.Remove,
                                    contentDescription = stringResource(R.string.layout_editor_split_fewer),
                                )
                            }
                            IconButton(
                                onClick = { onChange(withCut(split, rows, index, cut + 1)) },
                                enabled = cut < row.keys.size - 1,
                            ) {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = stringResource(R.string.layout_editor_split_more),
                                )
                            }
                        }
                    },
                )
            }
        }
    }
}

private const val MAX_GAP = 0.85f

private fun withCut(
    split: LayoutSplitConfiguration,
    rows: List<LayoutRow>,
    row: Int,
    cut: Int,
): LayoutSplitConfiguration {
    // Every row keeps its own cut: store the middle one where none was set, then move this row's.
    val breaks = rows.indices.map { split.breakIndex(it, rows[it].keys.size) }.toMutableList()
    breaks[row] = cut
    return split.copy(rowBreaks = breaks)
}

/** Whether the selected key is one the editor can describe; a key of an unknown kind keeps its raw fields. */
internal fun LayoutKey.isEditableKind(): Boolean = KeyActionChoice.of(action) != null

/**
 * The side columns of a nine-key layout. While the layout still declares the preset's columns the
 * keyboard draws its own and this says so; declaring them makes every key editable here.
 */
@Composable
fun RailSections(
    usesBundledRails: Boolean,
    leading: List<LayoutKey>,
    trailing: List<LayoutKey>,
    selected: RailSelection?,
    onSelect: (RailSelection) -> Unit,
    onDeclare: () -> Unit,
    onUseBundled: () -> Unit,
    onAdd: (LayoutAuxiliaryArea.Position) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    SectionHeader(stringResource(R.string.layout_editor_rails_section))
    if (usesBundledRails) {
        Text(
            text = stringResource(R.string.layout_editor_rails_bundled_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        TextButton(onClick = onDeclare, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(stringResource(R.string.layout_editor_rails_customize))
        }
        return
    }
    RailKeyEditor(
        leading = leading,
        trailing = trailing,
        selected = selected,
        maxKeys = PhoneKeypadRailPolicy.MAX_RAIL_KEYS,
        onSelect = onSelect,
        onAdd = onAdd,
        onMove = onMove,
        onRemove = onRemove,
    )
    TextButton(onClick = onUseBundled, modifier = Modifier.padding(horizontal = 8.dp)) {
        Text(stringResource(R.string.layout_editor_rails_use_bundled))
    }
}

/**
 * The side columns of an authored page: the keys of both columns, the width of each and whether the
 * right column also covers the bottom row.
 */
@Composable
fun PageRailSections(
    leading: List<LayoutKey>,
    trailing: List<LayoutKey>,
    leadingWidth: Double?,
    trailingWidth: Double?,
    trailingSpansBottom: Boolean,
    selected: RailSelection?,
    onSelect: (RailSelection) -> Unit,
    onAdd: (LayoutAuxiliaryArea.Position) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
    onWidth: (LayoutAuxiliaryArea.Position, Double?) -> Unit,
    onSpansBottom: (Boolean) -> Unit,
) {
    SectionHeader(stringResource(R.string.layout_editor_rails_section))
    RailKeyEditor(
        leading = leading,
        trailing = trailing,
        selected = selected,
        maxKeys = LayoutResourceBudget.KEYS_PER_REGION,
        onSelect = onSelect,
        onAdd = onAdd,
        onMove = onMove,
        onRemove = onRemove,
    )
    Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (leading.isNotEmpty()) {
            RailWidthField(R.string.page_editor_rail_width_leading, leadingWidth) {
                onWidth(LayoutAuxiliaryArea.Position.LEADING, it)
            }
        }
        if (trailing.isNotEmpty()) {
            RailWidthField(R.string.page_editor_rail_width_trailing, trailingWidth) {
                onWidth(LayoutAuxiliaryArea.Position.TRAILING, it)
            }
            val label = stringResource(R.string.page_editor_rail_spans_bottom)
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = trailingSpansBottom, role = Role.Switch, onValueChange = onSpansBottom),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = trailingSpansBottom, onCheckedChange = null)
            }
        }
    }
}

/** A column width in dp, a positive number or empty to follow the keyboard; a bad entry is flagged, not applied. */
@Composable
private fun RailWidthField(
    label: Int,
    width: Double?,
    onChange: (Double?) -> Unit,
) {
    var text by remember(width) { mutableStateOf(width?.let(::formatWidth).orEmpty()) }
    val parsed = if (text.isBlank()) null else text.toDoubleOrNull()
    val valid = text.isBlank() || (parsed != null && parsed > 0.0 && parsed.isFinite())
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            val value = if (it.isBlank()) null else it.toDoubleOrNull()
            if (it.isBlank() || (value != null && value > 0.0 && value.isFinite())) onChange(value)
        },
        label = { Text(stringResource(label)) },
        supportingText = {
            Text(
                stringResource(
                    if (valid) R.string.page_editor_rail_width_hint else R.string.layout_editor_key_width_invalid,
                ),
            )
        },
        isError = !valid,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Both columns' keys with the move and remove buttons of the selected one. */
@Composable
private fun RailKeyEditor(
    leading: List<LayoutKey>,
    trailing: List<LayoutKey>,
    selected: RailSelection?,
    maxKeys: Int,
    onSelect: (RailSelection) -> Unit,
    onAdd: (LayoutAuxiliaryArea.Position) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    RailRow(LayoutAuxiliaryArea.Position.LEADING, leading, selected, maxKeys, onSelect, onAdd)
    RailRow(LayoutAuxiliaryArea.Position.TRAILING, trailing, selected, maxKeys, onSelect, onAdd)
    if (selected != null) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            val count = (if (selected.position == LayoutAuxiliaryArea.Position.LEADING) leading else trailing).size
            ToolbarButton(Icons.Filled.ArrowUpward, R.string.layout_editor_rail_move_up, selected.index > 0) {
                onMove(-1)
            }
            ToolbarButton(
                Icons.Filled.ArrowDownward,
                R.string.layout_editor_rail_move_down,
                selected.index < count - 1,
            ) { onMove(+1) }
            ToolbarButton(Icons.Filled.Delete, R.string.layout_editor_rail_remove, true, onRemove)
        }
    }
}

@Composable
private fun RailRow(
    position: LayoutAuxiliaryArea.Position,
    keys: List<LayoutKey>,
    selected: RailSelection?,
    maxKeys: Int,
    onSelect: (RailSelection) -> Unit,
    onAdd: (LayoutAuxiliaryArea.Position) -> Unit,
) {
    val leading = position == LayoutAuxiliaryArea.Position.LEADING
    val name =
        stringResource(if (leading) R.string.layout_editor_rail_leading else R.string.layout_editor_rail_trailing)
    Text(
        text = name,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    val emptyNote =
        if (leading) R.string.layout_editor_rail_empty_leading else R.string.layout_editor_rail_empty_trailing
    val addLabel = if (leading) R.string.layout_editor_rail_add_leading else R.string.layout_editor_rail_add_trailing
    if (keys.isEmpty()) {
        Text(
            text = stringResource(emptyNote),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        keys.forEachIndexed { index, key ->
            val at = RailSelection(position, index)
            val isSelected = at == selected
            val description = stringResource(R.string.layout_editor_rail_key_description, name, index + 1, key.label)
            Surface(
                shape = RoundedCornerShape(6.dp),
                color =
                    if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                modifier =
                    Modifier
                        .weight(1f)
                        .height(KEY_HEIGHT)
                        .semantics { contentDescription = description }
                        .selectable(selected = isSelected, onClick = { onSelect(at) }),
            ) {
                Text(
                    text = key.label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.padding(horizontal = 2.dp).wrapCentre(),
                )
            }
        }
        IconButton(
            onClick = { onAdd(position) },
            enabled = keys.size < maxKeys,
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = stringResource(addLabel),
            )
        }
    }
}
