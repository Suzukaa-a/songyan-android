package app.songyan.ime.settings.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.KeyboardNumericMarks
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutAuxiliaryArea
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutRow
import app.songyan.ime.layout.LayoutSplitConfiguration
import app.songyan.ime.layout.T9FirstKeyBehavior
import app.songyan.ime.layout.T9QuickSymbolStore
import app.songyan.ime.settings.R
import app.songyan.ime.settings.ui.EditorNavigationConfirmation
import app.songyan.ime.settings.ui.EditorNavigationController
import app.songyan.ime.settings.ui.EditorNavigationGuard
import app.songyan.ime.settings.ui.GridEdit
import app.songyan.ime.settings.ui.KeyGridEditing
import app.songyan.ime.settings.ui.KeySelection
import app.songyan.ime.settings.ui.LayoutEditorViewModel
import app.songyan.ime.settings.ui.LocalEditorNavigationGuard
import app.songyan.ime.settings.ui.PageEditorViewModel
import app.songyan.ime.settings.ui.SaveOutcome
import app.songyan.ime.settings.ui.T9QuickSymbolsDraft
import app.songyan.ime.settings.ui.T9QuickSymbolsProblem
import app.songyan.ime.settings.ui.components.LoadingScreen
import app.songyan.ime.settings.ui.components.RadioRow
import app.songyan.ime.settings.ui.components.SectionHeader
import app.songyan.ime.settings.ui.components.SettingsScaffold

/** 编辑键盘布局: the letter rows of one main layout, key by key. Nothing is stored until 保存. */
@Composable
fun LayoutEditorScreen(
    layoutId: String,
    onClose: () -> Unit,
    onUse: (String) -> Unit = {},
    viewModel: LayoutEditorViewModel =
        viewModel(key = "layout-$layoutId", factory = LayoutEditorViewModel.Factory(layoutId)),
) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val outcome by viewModel.outcome.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val inheritedSymbols by viewModel.inheritedQuickSymbols.collectAsStateWithLifecycle()
    val current = draft
    if (current == null || (current.isNineKey && inheritedSymbols == null)) {
        LoadingScreen(stringResource(R.string.layout_editor_title), onClose)
        return
    }
    val copySuffix = stringResource(R.string.layout_copy_suffix)
    // The gap size while its slider moves, so the split view's preview follows it before it is saved.
    var liveGap by remember { mutableStateOf<Float?>(null) }
    EditorFrame(
        title = stringResource(R.string.layout_editor_title),
        name = current.layout.name,
        hasChanges = current.hasChanges,
        saving = saving,
        onDiscard = viewModel::discardDraft,
        outcome = outcome,
        onOutcomeShown = viewModel::consumeOutcome,
        onClose = onClose,
        onRename = { name -> viewModel.update { it.rename(name) } },
        onSave = viewModel::save,
        onSaveAsCopy = { viewModel.saveAsCopy(current.layout.name + copySuffix) },
        onUse = { onUse(current.layout.id) },
    ) {
        val inSplit = current.splitView && !current.isNineKey
        val liveFirst = BuiltinLayouts.usesT9QuickFirstKey(current.layout)
        val selectingFirst = current.isNineKey && current.rail == null && current.selection == KeySelection(0, 0)
        val previewRows =
            if (liveFirst) {
                val first = current.editRows[0].keys[0]
                val live =
                    BuiltinLayouts.t9CycleKey(
                        BuiltinLayouts.t9QuickCycleValues(
                            current.layout.effectiveT9QuickSymbols(inheritedSymbols.orEmpty()),
                            false,
                        ),
                    )
                if (first.label ==
                    BuiltinLayouts.phone9.rows[0]
                        .keys[0]
                        .label &&
                    live != null
                ) {
                    current.editRows.toMutableList().also { rows ->
                        rows[0] =
                            LayoutRow(rows[0].keys.toMutableList().also { it[0] = first.copy(label = live.label) })
                    }
                } else {
                    current.editRows
                }
            } else {
                current.editRows
            }
        GridSections(
            editPrimaryAction = !selectingFirst || !liveFirst,
            rows = previewRows,
            selection = current.selection,
            selectedKey = current.selectedKey,
            allowCode = true,
            split = current.layout.split,
            activationOnly = true,
            cuts = if (inSplit) current.splitCuts else null,
            gapFraction =
                current.layout.split.let { split ->
                    if (split == null || split.automaticGap) {
                        LayoutSplitConfiguration.DEFAULT_GAP_FRACTION.toFloat()
                    } else {
                        liveGap ?: split.gapFraction.toFloat()
                    }
                },
            beforeGrid = {
                if (current.isNineKey && (current.usesBundledRails || liveFirst)) {
                    NineKeySymbolsSection(
                        saved = current.layout.t9QuickSymbols,
                        inherited = inheritedSymbols.orEmpty(),
                        showsRail = current.usesBundledRails,
                        showsCycle = liveFirst,
                        onChange = { symbols ->
                            viewModel.update { it.copy(layout = it.layout.copy(t9QuickSymbols = symbols)) }
                        },
                    )
                }
                if (!current.isNineKey) {
                    ArrangementChoice(
                        split = inSplit,
                        explicit = current.hasExplicitSplit,
                        onChange = { on -> viewModel.update { it.withSplitView(on) } },
                    )
                }
            },
            splitControls = {
                SplitViewControls(
                    split = current.layout.split,
                    cuts = current.splitCuts,
                    sizes = current.splitLetterRows.map { it.keys.size },
                    explicit = current.hasExplicitSplit,
                    onSplit = { split -> viewModel.update { it.withSplit(split) } },
                    onCut = { row, delta -> viewModel.update { it.moveSplitCut(row, delta) } },
                    onRestore = { viewModel.update { it.restoreAutomaticSplit() } },
                    onLive = { liveGap = it },
                )
            },
            onSelect = { at -> viewModel.update { it.select(at) } },
            onEdit = { edit -> viewModel.update { it.apply(edit) } },
            onKey = { key -> viewModel.update { it.replaceSelectedKey(key) } },
            onSplit = { split -> viewModel.update { it.withSplit(split) } },
            // A nine-key layout is a fixed 3x3 grid with side columns instead of rows to rearrange.
            fixedStructure = current.isNineKey,
            selectionToken = current.rail ?: current.selection,
            spanKey = current.rail != null,
            beforeFields = {
                if (selectingFirst) {
                    SectionHeader(stringResource(R.string.layout_editor_key_action))
                    RadioRow(
                        title = stringResource(R.string.t9_first_quick),
                        selected = liveFirst,
                        onClick = {
                            viewModel.update {
                                it.copy(
                                    layout = it.layout.copy(t9FirstKeyBehavior = T9FirstKeyBehavior.QUICK_SYMBOLS),
                                )
                            }
                        },
                    )
                    RadioRow(
                        title = stringResource(R.string.t9_first_custom),
                        selected = !liveFirst,
                        onClick = {
                            viewModel.update {
                                it.copy(
                                    layout = it.layout.copy(t9FirstKeyBehavior = T9FirstKeyBehavior.CUSTOM),
                                )
                            }
                        },
                    )
                    if (liveFirst) {
                        Text(
                            stringResource(R.string.t9_first_quick_note),
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
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
                        onDeclare = { viewModel.update { it.declareRails(inheritedSymbols.orEmpty()) } },
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
        BottomRowEditor(current.bottomKeys, allowEmpty = false) { keys ->
            viewModel.update { it.withBottomKeys(keys) }
        }
    }
}

/** 编辑页面: the content rows of one page of the page library. */
@Composable
fun PageEditorScreen(
    pageId: String,
    onClose: () -> Unit,
    onUse: (String) -> Unit = {},
    viewModel: PageEditorViewModel = viewModel(key = "page-$pageId", factory = PageEditorViewModel.Factory(pageId)),
) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val outcome by viewModel.outcome.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
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
        saving = saving,
        onDiscard = viewModel::discardDraft,
        outcome = outcome,
        onOutcomeShown = viewModel::consumeOutcome,
        onClose = onClose,
        onRename = { name -> viewModel.update { it.rename(name) } },
        onSave = viewModel::save,
        onSaveAsCopy = { viewModel.saveAsCopy(current.page.name + copySuffix) },
        onUse = { onUse(current.page.id) },
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
        if (!current.isCategorized) {
            BottomRowEditor(current.bottomKeys) { keys ->
                viewModel.update { it.withBottomKeys(keys) }
            }
        }
    }
}

@Composable
private fun NineKeySymbolsSection(
    saved: String?,
    inherited: String,
    showsRail: Boolean,
    showsCycle: Boolean,
    onChange: (String?) -> Unit,
) {
    val value = saved ?: inherited
    val problem = T9QuickSymbolsDraft.check(value)
    val (rail, cycle) = T9QuickSymbolsDraft.preview(value)
    SectionHeader(stringResource(R.string.t9_symbols_title))
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.t9_symbols_field)) },
        isError = problem != null,
        supportingText = {
            Text(
                when (problem) {
                    T9QuickSymbolsProblem.DUPLICATE -> stringResource(R.string.t9_symbols_error_duplicate)
                    T9QuickSymbolsProblem.INVALID ->
                        stringResource(
                            R.string.t9_symbols_error_invalid,
                            T9QuickSymbolStore.MAXIMUM_SLOTS,
                        )
                    null -> stringResource(R.string.t9_symbols_empty_default)
                },
            )
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
    if (saved == null && inherited.isNotEmpty() && inherited != T9QuickSymbolStore.DEFAULT_SYMBOLS) {
        Text(
            stringResource(R.string.t9_symbols_inherited),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    if (showsRail) NineKeySymbolsPreview(stringResource(R.string.t9_symbols_rail), rail)
    if (showsCycle) NineKeySymbolsPreview(stringResource(R.string.t9_symbols_cycle), cycle)
    Row(Modifier.padding(horizontal = 16.dp)) {
        TextButton(onClick = { onChange("") }) { Text(stringResource(R.string.t9_symbols_restore)) }
        if (saved !=
            null
        ) {
            TextButton(onClick = { onChange(null) }) { Text(stringResource(R.string.t9_symbols_inherit)) }
        }
    }
}

@Composable
private fun NineKeySymbolsPreview(
    label: String,
    values: List<String>,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(
                    rememberScrollState(),
                ).padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, maxLines = 1)
        Text(
            values.joinToString(" "),
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            maxLines = 1,
        )
    }
}

/**
 * The frame both editors share: the name, the save buttons, the discard question and the reason a
 * save was refused. Saving keeps the editor open so newer edits remain available.
 */
@Composable
private fun EditorFrame(
    title: String,
    name: String,
    hasChanges: Boolean,
    saving: Boolean,
    onDiscard: () -> Unit,
    outcome: SaveOutcome?,
    onOutcomeShown: () -> Unit,
    onClose: () -> Unit,
    onRename: (String) -> Unit,
    onSave: () -> Unit,
    onSaveAsCopy: () -> Unit,
    onUse: () -> Unit,
    content: @Composable () -> Unit,
) {
    val hostGuard = LocalEditorNavigationGuard.current
    val navigationGuard = hostGuard ?: remember { EditorNavigationController() }
    val currentOnUse by rememberUpdatedState(onUse)
    CompositionLocalProvider(LocalEditorNavigationGuard provides navigationGuard) {
        EditorNavigationGuard(hasChanges, onDiscard = onDiscard, saving = saving)
        val snackbar = remember { SnackbarHostState() }
        var confirmingDiscard by remember { mutableStateOf(false) }
        val failureText = (outcome as? SaveOutcome.Failed)?.let { layoutErrorMessage(it.error) }
        val savedText = stringResource(R.string.t9_symbols_saved)
        val useText = stringResource(R.string.layouts_action_use)
        LaunchedEffect(outcome) {
            when (outcome) {
                is SaveOutcome.Saved -> {
                    if (snackbar.showSnackbar(savedText, actionLabel = useText) == SnackbarResult.ActionPerformed) {
                        navigationGuard.request { currentOnUse() }
                    }
                    onOutcomeShown()
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
        val back = {
            if (!saving) {
                if (hasChanges) {
                    confirmingDiscard = true
                } else {
                    onClose()
                }
            }
        }
        BackHandler(onBack = back)
        SettingsScaffold(
            title = title,
            modifier = Modifier.imePadding(),
            onBack = back,
            snackbarHost = { SnackbarHost(snackbar, Modifier.testTag("editor_snackbar")) },
            actions = {
                TextButton(onClick = onSave, enabled = hasChanges && !saving) {
                    Text(stringResource(if (saving) R.string.features_saving else R.string.layout_editor_save))
                }
            },
        ) { padding ->
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 24.dp),
            ) {
                if (saving) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = name,
                    onValueChange = onRename,
                    label = { Text(stringResource(R.string.layout_editor_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                content()
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                    OutlinedButton(
                        onClick = { navigationGuard.request { currentOnUse() } },
                        enabled =
                            !hasChanges && !saving,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.layouts_action_use))
                    }
                    OutlinedButton(
                        onClick = onSaveAsCopy,
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) {
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
                    TextButton(onClick = {
                        onDiscard()
                        onClose()
                    }, enabled = !saving) {
                        Text(stringResource(R.string.layout_editor_discard))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { confirmingDiscard = false },
                    ) { Text(stringResource(R.string.layout_editor_keep_editing)) }
                },
            )
        }
        if (hostGuard == null) EditorNavigationConfirmation(navigationGuard)
    }
}

/**
 * Which arrangement the grid shows: the keyboard as it is whole, or as it is when split (left half,
 * gap, right half). The split one is its own copy of the keys once edited.
 */
@Composable
private fun ArrangementChoice(
    split: Boolean,
    explicit: Boolean,
    onChange: (Boolean) -> Unit,
) {
    SectionHeader(stringResource(R.string.layout_editor_arrangement_section))
    Row(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = !split,
            onClick = { onChange(false) },
            label = { Text(stringResource(R.string.layout_editor_arrangement_whole)) },
        )
        FilterChip(
            selected = split,
            onClick = { onChange(true) },
            label = { Text(stringResource(R.string.layout_editor_arrangement_split)) },
        )
    }
    val note =
        when {
            split -> R.string.layout_editor_arrangement_split_note
            explicit -> R.string.layout_editor_arrangement_own_note
            else -> null
        }
    if (note != null) {
        Text(
            text = stringResource(note),
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
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
    /** Set while the grid shows the split arrangement: the keys of each row left of the gap. */
    cuts: List<Int>? = null,
    /** The gap the split arrangement is drawn with, as a share of the width. */
    gapFraction: Float = 0f,
    /** Shown above the grid (the whole / split choice of a main layout). */
    beforeGrid: @Composable () -> Unit = {},
    /** What the split arrangement adds under its grid; the activation choice alone is shown otherwise. */
    splitControls: @Composable () -> Unit = {},
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
    /** The gap and the cuts are edited elsewhere (a main layout's split view); only the activation shows here. */
    activationOnly: Boolean = false,
    editPrimaryAction: Boolean = true,
) {
    val template = LayoutKey("·", action = LayoutAction.literal("·"))
    beforeGrid()
    SectionHeader(stringResource(R.string.layout_editor_keys_section))
    KeyGrid(
        rows = rows,
        selection = selection,
        onSelect = onSelect,
        asciiPreview = asciiPreview,
        cuts = cuts,
        gapFraction = gapFraction,
    )
    val inSplit = cuts != null
    if (!fixedStructure) {
        GridToolbar(
            actions = if (cuts != null) GridActions.ofSplit(rows, cuts, selection) else GridActions.of(rows, selection),
            onMoveKey = { delta -> selection?.let { onEdit(KeyGridEditing.moveKey(rows, it, delta)) } },
            onMoveRow = { delta -> selection?.let { onEdit(KeyGridEditing.moveRow(rows, it, delta)) } },
            onAddKey = { selection?.let { onEdit(KeyGridEditing.addKeyAfter(rows, it, template)) } },
            onRemoveKey = { selection?.let { onEdit(KeyGridEditing.removeKey(rows, it)) } },
        )
        if (!inSplit) {
            androidx.compose.material3.TextButton(
                onClick = { onEdit(KeyGridEditing.addRow(rows, template)) },
                enabled = GridActions.of(rows, selection).canAddRow,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) { Text(stringResource(R.string.layout_editor_add_row)) }
        }
    }
    beforeFields()
    if (selectedKey != null && selectionToken != null) {
        SectionHeader(stringResource(R.string.layout_editor_selected_section, selectedKey.label))
        // The key's own state is keyed by its place, so moving the selection resets the text fields.
        androidx.compose.runtime.key(selectionToken) {
            KeyFields(
                key = selectedKey,
                allowCode = allowCode,
                onChange = onKey,
                span = spanKey,
                allowJump = allowJump,
                editPrimaryAction = editPrimaryAction,
            )
        }
    }
    if (!fixedStructure) {
        if (inSplit) {
            SplitEditor(rows = rows, split = split, onChange = onSplit, activationOnly = true)
            splitControls()
        } else {
            SplitEditor(rows = rows, split = split, onChange = onSplit, activationOnly = activationOnly)
        }
    }
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

@Composable
private fun BottomRowEditor(
    keys: List<LayoutKey>,
    allowEmpty: Boolean = true,
    onChange: (List<LayoutKey>) -> Unit,
) {
    var selected by remember { mutableStateOf<KeySelection?>(null) }
    val rows = listOf(LayoutRow(keys))
    SectionHeader(stringResource(R.string.layout_editor_bottom_section))
    KeyGrid(rows = rows, selection = selected, onSelect = { selected = it })
    val template = LayoutKey("·", action = LayoutAction.literal("·"))
    GridToolbar(
        actions =
            GridActions.of(rows, selected).copy(
                canAddKey = keys.size < app.songyan.ime.layout.LayoutResourceBudget.KEYS_PER_REGION,
                canRemoveKey = selected != null && (allowEmpty || keys.size > 1),
            ),
        onMoveKey = { delta ->
            selected?.let { at ->
                KeyGridEditing.moveKey(rows, at, delta)?.let {
                    onChange(it.rows.single().keys)
                    selected = it.selection
                }
            }
        },
        onMoveRow = {},
        onAddKey = {
            val at = selected
            if (at == null) {
                onChange(keys + template)
                selected = KeySelection(0, keys.size)
            } else {
                KeyGridEditing.addKeyAfter(rows, at, template)?.let {
                    onChange(it.rows.single().keys)
                    selected = it.selection
                }
            }
        },
        onRemoveKey = {
            selected?.let { at ->
                onChange(keys.filterIndexed { index, _ -> index != at.key })
                selected = null
            }
        },
    )
    selected?.let { at ->
        keys.getOrNull(at.key)?.let { key ->
            androidx.compose.runtime.key(at) {
                KeyFields(
                    key = key,
                    allowCode = false,
                    onChange = { replacement -> onChange(keys.toMutableList().also { it[at.key] = replacement }) },
                    allowJump = true,
                )
            }
        }
    }
}
