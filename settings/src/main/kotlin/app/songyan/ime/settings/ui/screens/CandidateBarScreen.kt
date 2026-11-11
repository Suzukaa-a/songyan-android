package app.songyan.ime.settings.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.songyan.ime.candidate.CandidateBarDefinition.Expanded.Mode
import app.songyan.ime.keyboard.support.CandidateTextSize
import app.songyan.ime.settings.R
import app.songyan.ime.settings.ui.AppearanceViewModel
import app.songyan.ime.settings.ui.components.RadioRow
import app.songyan.ime.settings.ui.components.SectionHeader
import app.songyan.ime.settings.ui.components.SettingsScaffold
import app.songyan.ime.settings.ui.components.SwitchRow
import kotlin.math.roundToInt

/** The row height slider's range in dp; the keyboard draws the stored value (a large candidate font tops it up). */
private val ROW_HEIGHT_RANGE = 36f..56f

/** The cell padding slider's range in dp, drawn as stored. */
private val CELL_PADDING_RANGE = 4f..24f

/**
 * 候选栏: whether the idle row shows the toolbar, the single-row size, the candidate text size and how
 * the expanded panel arranges candidates. The toolbar choice is a keyboard preference; the sizes belong to the keyboard
 * appearance in use, so a change on a built-in appearance lands in a copy of it, and the keyboard
 * picks it up the next time it is shown.
 */
@Composable
fun CandidateBarScreen(
    hideIdleToolbar: Boolean,
    onHideIdleToolbarChanged: (Boolean) -> Unit,
    candidateTextSize: CandidateTextSize?,
    onCandidateTextSizeChanged: (CandidateTextSize) -> Unit,
    collapsedComments: Boolean?,
    onCollapsedCommentsChanged: (Boolean) -> Unit,
    onBack: (() -> Unit)?,
    viewModel: AppearanceViewModel = viewModel(factory = AppearanceViewModel.Factory()),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val failure by viewModel.failure.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val applyFailed = stringResource(R.string.appearance_apply_failed)
    LaunchedEffect(Unit) { viewModel.refresh() }
    LaunchedEffect(failure) {
        if (failure != null) {
            snackbar.showSnackbar(applyFailed)
            viewModel.consumeFailure()
        }
    }
    val bar = state.candidateBar

    SettingsScaffold(
        title = stringResource(R.string.candidate_bar_title),
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp),
        ) {
            SwitchRow(
                title = stringResource(R.string.candidate_bar_hide_toolbar_title),
                subtitle = stringResource(R.string.candidate_bar_hide_toolbar_body),
                checked = hideIdleToolbar,
                onCheckedChange = onHideIdleToolbarChanged,
            )

            SectionHeader(stringResource(R.string.candidate_bar_row_section))
            ValueSlider(
                title = stringResource(R.string.candidate_bar_row_height),
                stored =
                    bar.collapsed.height
                        .toFloat()
                        .coerceIn(ROW_HEIGHT_RANGE),
                range = ROW_HEIGHT_RANGE,
                display = { it.roundToInt() },
                onCommit = { value ->
                    viewModel.updateCandidateBar { current ->
                        current.copy(collapsed = current.collapsed.copy(height = value.roundToInt().toDouble()))
                    }
                },
            )
            ValueSlider(
                title = stringResource(R.string.candidate_bar_cell_padding),
                stored =
                    bar.collapsed.cellPaddingX
                        .toFloat()
                        .coerceIn(CELL_PADDING_RANGE),
                range = CELL_PADDING_RANGE,
                display = { it.roundToInt() },
                onCommit = { value ->
                    viewModel.updateCandidateBar { current ->
                        current.copy(collapsed = current.collapsed.copy(cellPaddingX = value.roundToInt().toDouble()))
                    }
                },
            )
            ValueSlider(
                title = stringResource(R.string.candidate_bar_cell_spacing),
                stored = bar.collapsed.cellSpacing.toFloat(),
                range = 0f..16f,
                display = { it.roundToInt() },
                onCommit = { value ->
                    viewModel.updateCandidateBar { current ->
                        current.copy(collapsed = current.collapsed.copy(cellSpacing = value.roundToInt().toDouble()))
                    }
                },
            )

            // Not drawn until the stored choice is read, so a default never flashes by.
            if (candidateTextSize != null) {
                SectionHeader(stringResource(R.string.options_candidate_size_section))
                Column(modifier = Modifier.selectableGroup()) {
                    CandidateTextSize.entries.forEach { size ->
                        RadioRow(
                            title = stringResource(candidateTextSizeLabel(size)),
                            selected = size == candidateTextSize,
                            onClick = { onCandidateTextSizeChanged(size) },
                        )
                    }
                }
            }
            if (collapsedComments != null) {
                SwitchRow(
                    title = stringResource(R.string.candidate_bar_row_comments_title),
                    subtitle = stringResource(R.string.candidate_bar_row_comments_body),
                    checked = collapsedComments,
                    onCheckedChange = onCollapsedCommentsChanged,
                )
            }

            SectionHeader(stringResource(R.string.candidate_bar_expanded_section))
            Column(modifier = Modifier.selectableGroup()) {
                listOf(
                    Mode.ADAPTIVE to R.string.candidate_bar_mode_adaptive,
                    Mode.LIST to R.string.candidate_bar_mode_list,
                    Mode.GRID to R.string.candidate_bar_mode_grid,
                ).forEach { (mode, label) ->
                    RadioRow(
                        title = stringResource(label),
                        selected = bar.expanded.mode == mode,
                        onClick = {
                            viewModel.updateCandidateBar { current ->
                                current.copy(expanded = current.expanded.copy(mode = mode))
                            }
                        },
                    )
                }
            }
            if (bar.expanded.mode == Mode.GRID) {
                ValueSlider(
                    title = stringResource(R.string.candidate_bar_columns),
                    stored = bar.expanded.columns.toFloat(),
                    range = 1f..4f,
                    steps = 2,
                    display = { it.roundToInt() },
                    onCommit = { value ->
                        viewModel.updateCandidateBar { current ->
                            current.copy(expanded = current.expanded.copy(columns = value.roundToInt()))
                        }
                    },
                )
            }
            ValueSlider(
                title = stringResource(R.string.candidate_bar_row_gap),
                stored = bar.expanded.rowSpacing.toFloat(),
                range = 0f..16f,
                display = { it.roundToInt() },
                onCommit = { value ->
                    viewModel.updateCandidateBar { current ->
                        current.copy(expanded = current.expanded.copy(rowSpacing = value.roundToInt().toDouble()))
                    }
                },
            )
            ValueSlider(
                title = stringResource(R.string.candidate_bar_column_gap),
                stored = bar.expanded.columnSpacing.toFloat(),
                range = 0f..16f,
                display = { it.roundToInt() },
                onCommit = { value ->
                    viewModel.updateCandidateBar { current ->
                        current.copy(expanded = current.expanded.copy(columnSpacing = value.roundToInt().toDouble()))
                    }
                },
            )
        }
    }
}

/** A slider row that writes once, when the finger lifts: every write rebuilds the keyboard's styles. */
@Composable
private fun ValueSlider(
    title: String,
    stored: Float,
    range: ClosedFloatingPointRange<Float>,
    onCommit: (Float) -> Unit,
    display: (Float) -> Int,
    steps: Int = 0,
) {
    var position by remember(stored) { mutableFloatStateOf(stored) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.candidate_bar_value, title, display(position))) },
        supportingContent = {
            Slider(
                value = position,
                onValueChange = { position = it },
                onValueChangeFinished = { onCommit(position) },
                valueRange = range,
                steps = steps,
            )
        },
    )
}

private fun candidateTextSizeLabel(size: CandidateTextSize): Int =
    when (size) {
        CandidateTextSize.SMALL -> R.string.options_candidate_size_small
        CandidateTextSize.STANDARD -> R.string.options_candidate_size_standard
        CandidateTextSize.LARGE -> R.string.options_candidate_size_large
        CandidateTextSize.EXTRA_LARGE -> R.string.options_candidate_size_extra_large
    }
