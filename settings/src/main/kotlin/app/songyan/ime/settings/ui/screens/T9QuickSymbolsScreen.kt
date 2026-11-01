package app.songyan.ime.settings.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.songyan.ime.layout.T9QuickSymbolStore
import app.songyan.ime.settings.R
import app.songyan.ime.settings.ui.T9QuickSymbolsDraft
import app.songyan.ime.settings.ui.T9QuickSymbolsProblem
import app.songyan.ime.settings.ui.T9QuickSymbolsViewModel
import app.songyan.ime.settings.ui.components.DetailRow
import app.songyan.ime.settings.ui.components.LoadingScreen
import app.songyan.ime.settings.ui.components.SectionHeader
import app.songyan.ime.settings.ui.components.SettingsScaffold
import kotlinx.coroutines.launch

/**
 * 九键快捷符号: the order of the punctuation on the nine-key keyboard's side rail and first key.
 *
 * The draft survives rotation. It is seeded from the stored list once, so a change made elsewhere
 * while the user is typing never overwrites what they typed.
 */
@Composable
fun T9QuickSymbolsScreen(
    onBack: (() -> Unit)?,
    viewModel: T9QuickSymbolsViewModel = viewModel(factory = T9QuickSymbolsViewModel.Factory()),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (!state.loaded) {
        LoadingScreen(stringResource(R.string.t9_symbols_title), onBack)
        return
    }
    var draft by rememberSaveable { mutableStateOf(state.effective) }
    val problem = T9QuickSymbolsDraft.check(draft)
    val (rail, cycle) = T9QuickSymbolsDraft.preview(draft)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val savedText = stringResource(R.string.t9_symbols_saved)
    val restoredText = stringResource(R.string.t9_symbols_restored)

    SettingsScaffold(
        title = stringResource(R.string.t9_symbols_title),
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp),
        ) {
            SectionHeader(stringResource(R.string.t9_symbols_order_section))
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text(stringResource(R.string.t9_symbols_field)) },
                supportingText = {
                    Text(
                        text =
                            when (problem) {
                                T9QuickSymbolsProblem.INVALID ->
                                    stringResource(R.string.t9_symbols_error_invalid, T9QuickSymbolStore.MAXIMUM_SLOTS)

                                T9QuickSymbolsProblem.DUPLICATE -> stringResource(R.string.t9_symbols_error_duplicate)
                                null ->
                                    stringResource(
                                        R.string.t9_symbols_count,
                                        draft.length,
                                        T9QuickSymbolStore.MAXIMUM_SLOTS,
                                    )
                            },
                    )
                },
                isError = problem != null,
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )

            SectionHeader(stringResource(R.string.t9_symbols_keyboard_section))
            DetailRow(
                title = stringResource(R.string.t9_symbols_rail),
                body = rail.joinToString(" "),
            )
            DetailRow(
                title = stringResource(R.string.t9_symbols_cycle),
                body = cycle.joinToString(" "),
            )
            Text(
                text = stringResource(R.string.t9_symbols_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    enabled = problem == null && draft != state.effective,
                    onClick = {
                        if (viewModel.save(draft)) scope.launch { snackbar.showSnackbar(savedText) }
                    },
                ) {
                    Text(stringResource(R.string.t9_symbols_save))
                }
                TextButton(
                    enabled = state.saved.isNotEmpty() || draft != T9QuickSymbolStore.DEFAULT_SYMBOLS,
                    onClick = {
                        viewModel.restoreDefault()
                        draft = T9QuickSymbolStore.DEFAULT_SYMBOLS
                        scope.launch { snackbar.showSnackbar(restoredText) }
                    },
                ) {
                    Text(stringResource(R.string.t9_symbols_restore))
                }
            }
        }
    }
}
