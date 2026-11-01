package app.songyan.ime.settings.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.songyan.ime.layout.KeyboardCompositionKey
import app.songyan.ime.layout.KeyboardPageKind
import app.songyan.ime.layout.KeyboardPageMarkCoverage
import app.songyan.ime.layout.LayoutInput
import app.songyan.ime.settings.R
import app.songyan.ime.settings.ui.LayoutLibraryViewModel
import app.songyan.ime.settings.ui.components.LoadingScreen
import app.songyan.ime.settings.ui.components.RadioRow
import app.songyan.ime.settings.ui.components.SectionHeader
import app.songyan.ime.settings.ui.components.SettingsScaffold

/**
 * 页面搭配: which main layout and which 123, symbol and ABC pages one keyboard uses. A choice is
 * stored at once and reaches the keyboard the next time it draws.
 */
@Composable
fun CompositionScreen(
    key: KeyboardCompositionKey,
    keyboardName: String?,
    onBack: (() -> Unit)?,
    viewModel: LayoutLibraryViewModel = viewModel(factory = LayoutLibraryViewModel.Factory()),
) {
    val registry by viewModel.registry.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.refresh() }
    LibraryEvents(viewModel, snackbar)
    val current = registry
    if (current == null) {
        LoadingScreen(stringResource(R.string.composition_title), onBack)
        return
    }
    val composition = current.composition(key)
    SettingsScaffold(
        title = stringResource(R.string.composition_title),
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
            Text(
                text = stringResource(R.string.composition_body, keyboardName ?: key.inputMethodId),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            SectionHeader(stringResource(R.string.composition_main_section))
            Column(modifier = Modifier.selectableGroup()) {
                current
                    .mainLayoutCandidates(key)
                    .forEach { layout ->
                        RadioRow(
                            title = layout.name,
                            selected = composition.mainLayoutID == layout.id,
                            onClick = { viewModel.saveComposition(key, composition.withMainLayoutId(layout.id)) },
                        )
                    }
            }
            for (kind in KeyboardPageKind.entries) {
                SectionHeader(stringResource(pageKindLabel(kind)))
                Column(modifier = Modifier.selectableGroup()) {
                    current.pages.filter { it.kind == kind }.forEach { page ->
                        RadioRow(
                            title = page.name,
                            selected = composition.pageId(kind) == page.id,
                            onClick = { viewModel.saveComposition(key, composition.withPageId(page.id, kind)) },
                        )
                    }
                }
                if (kind == KeyboardPageKind.SYMBOLS) {
                    // A built-in symbol page takes the marks over itself; a user's own page is never changed.
                    val missing =
                        KeyboardPageMarkCoverage.pairingMissingMarks(
                            letters = current.resolvedLayout(composition.mainLayoutID),
                            numeric = current.page(composition.pageId(KeyboardPageKind.NUMERIC)),
                            symbols = current.page(composition.pageId(KeyboardPageKind.SYMBOLS)),
                            mainLayoutIsPhoneKeypad = key.layout == LayoutInput.Builtin.PHONE9,
                        )
                    if (missing.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.composition_missing_marks, missing.joinToString(" ")),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The pairing key of the keyboard in use, or null when the input method is not known yet. */
fun compositionKeyOf(
    inputMethodId: String?,
    layoutId: String,
): KeyboardCompositionKey? {
    val id = inputMethodId?.takeIf { it.isNotBlank() } ?: return null
    return KeyboardCompositionKey(id, LayoutInput.Builtin.fromWire(layoutId) ?: return null)
}
