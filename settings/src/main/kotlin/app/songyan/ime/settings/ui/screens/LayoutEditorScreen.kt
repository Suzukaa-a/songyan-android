package app.songyan.ime.settings.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.songyan.ime.layout.KeyboardNumericMarks
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutAuxiliaryArea
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutRow
import app.songyan.ime.layout.LayoutSplitConfiguration
import app.songyan.ime.settings.R
import app.songyan.ime.settings.ui.GridEdit
import app.songyan.ime.settings.ui.KeyGridEditing
import app.songyan.ime.settings.ui.KeySelection
import app.songyan.ime.settings.ui.LayoutEditorViewModel
import app.songyan.ime.settings.ui.PageEditorViewModel
import app.songyan.ime.settings.ui.SaveOutcome
import app.songyan.ime.settings.ui.components.LoadingScreen
import app.songyan.ime.settings.ui.components.SectionHeader
import app.songyan.ime.settings.ui.components.SettingsScaffold

/** 编辑键盘布局: the letter rows of one main layout, key by key. Nothing is stored until 保存. */
@Composable
fun LayoutEditorScreen(
    layoutId: String,
    onClose: () -> Unit,
    viewModel: LayoutEditorViewModel =
        viewModel(key = "layout-$layoutId", factory = LayoutEditorViewModel.Factory(layoutId)),
) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val outcome by viewModel.outcome.collectAsStateWithLifecycle()
    val current = draft
    if (current == null) {
        LoadingScreen(stringResource(R.string.layout_editor_title), onClose)
        return
    }
    val copySuffix = stringResource(R.string.layout_copy_suffix)
    EditorFrame(
        title = stringResource(R.string.layout_editor_title),
        name = current.layout.name,
        hasChanges = current.hasChanges,
        outcome = outcome,
        onOutcomeShown = viewModel::consumeOutcome,
        onClose = onClose,
        onRename = { name -> viewModel.update { it.rename(name) } },
        onSave = viewModel::save,
        onSaveAsCopy = { viewModel.saveAsCopy(current.layout.name + copySuffix) },
    ) {
        GridSections(
            rows = current.letterRows,
            selection = current.selection,
            selectedKey = current.selectedKey,
            allowCode = true,
            split = current.layout.split,
            onSelect = { at -> viewModel.update { it.select(at) } },
            onEdit = { edit -> viewModel.update { it.apply(edit) } },
            onKey = { key -> viewModel.update { it.replaceSelectedKey(key) } },
            onSplit = { split -> viewModel.update { it.withSplit(split) } },
            // A nine-key layout is a fixed 3x3 grid with side columns instead of rows to rearrange.
            fixedStructure = current.isNineKey,
            selectionToken = current.rail ?: current.selection,
            spanKey = current.rail != null,
            beforeFields = {
                if (current.isNineKey) {
                    Text(
                        text = stringResource(R.string.layout_editor_nine_grid_note),
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    RailSections(
                        usesBundledRails = current.usesBundledRails,
                        leading = current.railKeys(LayoutAuxiliaryArea.Position.LEADING),
                        trailing = current.railKeys(LayoutAuxiliaryArea.Position.TRAILING),
                        selected = current.rail,
                        onSelect = { at -> viewModel.update { it.selectRail(at) } },
                        onDeclare = { viewModel.update { it.declareRails() } },
                        onUseBundled = { viewModel.update { it.useBundledRails() } },
                        onAdd = { position ->
                            viewModel.update {
                                it.addRailKey(
                                    position,
                                    LayoutKey("·", action = LayoutAction.literal("·")),
                                )
                            }
                        },
                        onMove = { delta -> viewModel.update { it.moveSelectedRailKey(delta) } },
                        onRemove = { viewModel.update { it.removeSelectedRailKey() } },
                    )
                }
            },
        )
        Text(
            text = stringResource(R.string.layout_editor_control_row_note),
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** 编辑页面: the content rows of one page of the page library. */
@Composable
fun PageEditorScreen(
    pageId: String,
    onClose: () -> Unit,
    viewModel: PageEditorViewModel = viewModel(key = "page-$pageId", factory = PageEditorViewModel.Factory(pageId)),
) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val outcome by viewModel.outcome.collectAsStateWithLifecycle()
    val current = draft
    if (current == null) {
        LoadingScreen(stringResource(R.string.page_editor_title), onClose)
        return
    }
    val copySuffix = stringResource(R.string.layout_copy_suffix)
    EditorFrame(
        title = stringResource(R.string.page_editor_title),
        name = current.page.name,
        hasChanges = current.hasChanges,
        outcome = outcome,
        onOutcomeShown = viewModel::consumeOutcome,
        onClose = onClose,
        onRename = { name -> viewModel.update { it.rename(name) } },
        onSave = viewModel::save,
        onSaveAsCopy = { viewModel.saveAsCopy(current.page.name + copySuffix) },
    ) {
        if (current.isCategorized) {
            val newName = stringResource(R.string.page_editor_category_new)
            val copySuffixText = stringResource(R.string.layout_copy_suffix)
            CategorySections(
                categories = current.categories,
                index = current.categoryIndex,
                keyIndex = current.categoryKey,
                actions =
                    CategoryActions(
                        onSelectCategory = { at -> viewModel.update { it.selectCategory(at) } },
                        onAdd = { viewModel.update { it.addCategory(viewModel.newCategoryId(), newName) } },
                        onDuplicate = {
                            viewModel.update {
                                it.duplicateCategory(
                                    viewModel.newCategoryId(),
                                    (it.category?.name ?: "") + copySuffixText,
                                )
                            }
                        },
                        onRemove = { viewModel.update { it.removeCategory() } },
                        onMove = { delta -> viewModel.update { it.moveCategory(delta) } },
                        onName = { name -> viewModel.update { it.renameCategory(name) } },
                        onColumns = { columns -> viewModel.update { it.setCategoryColumns(columns) } },
                        onSource = { source -> viewModel.update { it.setCategorySource(source) } },
                        onSelectKey = { at -> viewModel.update { it.selectCategoryKey(at) } },
                        onMoveKey = { delta -> viewModel.update { it.moveCategoryKey(delta) } },
                        onAddKey = { viewModel.update { it.addCategoryKey() } },
                        onRemoveKey = { viewModel.update { it.removeCategoryKey() } },
                        onKey = { key -> viewModel.update { it.replaceSelectedCategoryKey(key) } },
                    ),
            )
        } else {
            var previewAscii by remember { mutableStateOf(current.openingMarks == KeyboardNumericMarks.ASCII) }
            if (current.choosesOpeningMarks) {
                OpeningMarksSection(
                    opening = current.openingMarks,
                    previewAscii = previewAscii,
                    onOpening = { marks ->
                        viewModel.update { it.withOpeningMarks(marks) }
                        if (marks != null) previewAscii = marks == KeyboardNumericMarks.ASCII
                    },
                    onPreview = { previewAscii = it },
                )
            }
            GridSections(
                asciiPreview = if (current.choosesOpeningMarks) previewAscii else null,
                rows = current.rows,
                selection = current.selection,
                selectedKey = current.selectedKey,
                allowCode = false,
                allowJump = true,
                split = current.page.split,
                onSelect = { at -> viewModel.update { it.select(at) } },
                onEdit = { edit -> viewModel.update { it.apply(edit) } },
                onKey = { key -> viewModel.update { it.replaceSelectedKey(key) } },
                onSplit = { split -> viewModel.update { it.withSplit(split) } },
                selectionToken = current.rail ?: current.selection,
                spanKey = current.rail != null,
                beforeFields = {
                    PageRailSections(
                        leading = current.railKeys(LayoutAuxiliaryArea.Position.LEADING),
                        trailing = current.railKeys(LayoutAuxiliaryArea.Position.TRAILING),
                        leadingWidth = current.railWidth(LayoutAuxiliaryArea.Position.LEADING),
                        trailingWidth = current.railWidth(LayoutAuxiliaryArea.Position.TRAILING),
                        trailingSpansBottom = current.trailingSpansBottom,
                        selected = current.rail,
                        onSelect = { at -> viewModel.update { it.selectRail(at) } },
                        onAdd = { position ->
                            viewModel.update {
                                it.addRailKey(position, LayoutKey("·", action = LayoutAction.literal("·")))
                            }
                        },
                        onMove = { delta -> viewModel.update { it.moveSelectedRailKey(delta) } },
                        onRemove = { viewModel.update { it.removeSelectedRailKey() } },
                        onWidth = { position, width -> viewModel.update { it.setRailWidth(position, width) } },
                        onSpansBottom = { value -> viewModel.update { it.setTrailingSpansBottom(value) } },
                    )
                },
            )
        }
        Text(
            text = stringResource(R.string.layout_editor_control_row_note),
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/**
 * The frame both editors share: the name, the save buttons, the discard question and the reason a
 * save was refused. A save that succeeds closes the editor; one that is refused keeps the draft.
 */
@Composable
private fun EditorFrame(
    title: String,
    name: String,
    hasChanges: Boolean,
    outcome: SaveOutcome?,
    onOutcomeShown: () -> Unit,
    onClose: () -> Unit,
    onRename: (String) -> Unit,
    onSave: () -> Unit,
    onSaveAsCopy: () -> Unit,
    content: @Composable () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    var confirmingDiscard by remember { mutableStateOf(false) }
    val failureText = (outcome as? SaveOutcome.Failed)?.let { layoutErrorMessage(it.error) }
    LaunchedEffect(outcome) {
        when (outcome) {
            is SaveOutcome.Saved -> {
                onOutcomeShown()
                onClose()
            }
            else -> Unit
        }
    }
    LaunchedEffect(failureText) {
        if (failureText != null) {
            snackbar.showSnackbar(failureText)
            onOutcomeShown()
        }
    }
    val back = { if (hasChanges) confirmingDiscard = true else onClose() }
    BackHandler(onBack = back)
    SettingsScaffold(
        title = title,
        onBack = back,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = onRename,
                label = { Text(stringResource(R.string.layout_editor_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            content()
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                androidx.compose.material3.Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.layout_editor_save))
                }
                OutlinedButton(onClick = onSaveAsCopy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(stringResource(R.string.layout_editor_save_copy))
                }
            }
        }
    }
    if (confirmingDiscard) {
        AlertDialog(
            onDismissRequest = { confirmingDiscard = false },
            title = { Text(stringResource(R.string.layout_editor_discard_title)) },
            text = { Text(stringResource(R.string.layout_editor_discard_body)) },
            confirmButton = {
                TextButton(onClick = onClose) { Text(stringResource(R.string.layout_editor_discard)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmingDiscard = false },
                ) { Text(stringResource(R.string.layout_editor_keep_editing)) }
            },
        )
    }
}

/** The key grid, the selected key's fields and the split settings: the part both editors share. */
@Composable
private fun GridSections(
    rows: List<LayoutRow>,
    selection: KeySelection?,
    selectedKey: LayoutKey?,
    allowCode: Boolean,
    split: LayoutSplitConfiguration?,
    onSelect: (KeySelection) -> Unit,
    onEdit: (GridEdit?) -> Unit,
    onKey: (LayoutKey) -> Unit,
    onSplit: (LayoutSplitConfiguration?) -> Unit,
    /** The grid cannot gain, lose or move keys (the nine-key grid): only what a key shows and does is edited. */
    fixedStructure: Boolean = false,
    /** Moving this resets the selected key's text fields. */
    selectionToken: Any? = selection,
    /** The selected key sits in a side column, where its width is a relative height. */
    spanKey: Boolean = false,
    /** Shown between the grid and the selected key's fields. */
    beforeFields: @Composable () -> Unit = {},
    /** Keys may jump to another layout's page: a page's keys may, a main layout's may not. */
    allowJump: Boolean = false,
    /** A page with Chinese and English marks draws the branch being previewed; null draws the saved labels. */
    asciiPreview: Boolean? = null,
) {
    val template = LayoutKey("·", action = LayoutAction.literal("·"))
    SectionHeader(stringResource(R.string.layout_editor_keys_section))
    KeyGrid(rows = rows, selection = selection, onSelect = onSelect, asciiPreview = asciiPreview)
    if (!fixedStructure) {
        GridToolbar(
            actions = GridActions.of(rows, selection),
            onMoveKey = { delta -> selection?.let { onEdit(KeyGridEditing.moveKey(rows, it, delta)) } },
            onMoveRow = { delta -> selection?.let { onEdit(KeyGridEditing.moveRow(rows, it, delta)) } },
            onAddKey = { selection?.let { onEdit(KeyGridEditing.addKeyAfter(rows, it, template)) } },
            onRemoveKey = { selection?.let { onEdit(KeyGridEditing.removeKey(rows, it)) } },
        )
        androidx.compose.material3.TextButton(
            onClick = { onEdit(KeyGridEditing.addRow(rows, template)) },
            enabled = GridActions.of(rows, selection).canAddRow,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) { Text(stringResource(R.string.layout_editor_add_row)) }
    }
    beforeFields()
    if (selectedKey != null && selectionToken != null) {
        SectionHeader(stringResource(R.string.layout_editor_selected_section, selectedKey.label))
        // The key's own state is keyed by its place, so moving the selection resets the text fields.
        androidx.compose.runtime.key(selectionToken) {
            KeyFields(key = selectedKey, allowCode = allowCode, onChange = onKey, span = spanKey, allowJump = allowJump)
        }
    }
    if (!fixedStructure) SplitEditor(rows = rows, split = split, onChange = onSplit)
}

/**
 * Where a page with Chinese and English marks opens, and a preview of both faces. The saved choice
 * (跟随输入 / 中文 / 英文) is what the keyboard uses; the preview only changes what the grid draws.
 */
@Composable
private fun OpeningMarksSection(
    opening: KeyboardNumericMarks?,
    previewAscii: Boolean,
    onOpening: (KeyboardNumericMarks?) -> Unit,
    onPreview: (Boolean) -> Unit,
) {
    SectionHeader(stringResource(R.string.page_editor_opening_marks))
    Row(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = opening == null,
            onClick = { onOpening(null) },
            label = { Text(stringResource(R.string.page_editor_marks_follow)) },
        )
        FilterChip(
            selected = opening == KeyboardNumericMarks.CHINESE,
            onClick = { onOpening(KeyboardNumericMarks.CHINESE) },
            label = { Text(stringResource(R.string.page_editor_marks_chinese)) },
        )
        FilterChip(
            selected = opening == KeyboardNumericMarks.ASCII,
            onClick = { onOpening(KeyboardNumericMarks.ASCII) },
            label = { Text(stringResource(R.string.page_editor_marks_english)) },
        )
    }
    SectionHeader(stringResource(R.string.page_editor_preview))
    Row(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = !previewAscii,
            onClick = { onPreview(false) },
            label = { Text(stringResource(R.string.page_editor_marks_chinese)) },
        )
        FilterChip(
            selected = previewAscii,
            onClick = { onPreview(true) },
            label = { Text(stringResource(R.string.page_editor_marks_english)) },
        )
    }
}
