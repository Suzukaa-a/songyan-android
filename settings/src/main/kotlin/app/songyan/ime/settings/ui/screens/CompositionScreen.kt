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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.songyan.ime.keyboard.engine.LayoutFamilyContext
import app.songyan.ime.layout.KeyboardCompositionKey
import app.songyan.ime.layout.KeyboardPageKind
import app.songyan.ime.layout.KeyboardPageMarkCoverage
import app.songyan.ime.layout.LayoutInput
import app.songyan.ime.settings.R
import app.songyan.ime.settings.state.KeyboardLayoutOption
import app.songyan.ime.settings.state.familiesOfSchemes
import app.songyan.ime.settings.state.layoutDisplayName
import app.songyan.ime.settings.ui.LayoutLibraryViewModel
import app.songyan.ime.settings.ui.components.LoadingScreen
import app.songyan.ime.settings.ui.components.RadioRow
import app.songyan.ime.settings.ui.components.SectionHeader
import app.songyan.ime.settings.ui.components.SettingsScaffold
import app.songyan.ime.settings.ui.text
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * 页面搭配: which main layout and which 123, symbol and ABC pages one keyboard uses. A choice is
 * stored at once and reaches the keyboard the next time it draws.
 */
@Composable
fun CompositionScreen(
    key: KeyboardCompositionKey,
    keyboardName: String?,
    /** The families the settings app state already resolved; unknown ids keep the id-only rule. */
    families: LayoutFamilyContext = LayoutFamilyContext.UNKNOWN,
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
    val composition = current.composition(key, families)
    val shape = KeyboardLayoutOption.fromId(key.layout.wire)
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
                    .mainLayoutCandidates(key, families)
                    .forEach { layout ->
                        RadioRow(
                            title = layoutDisplayName(layout, shape).text(),
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

@Composable
fun CompositionEmptyScreen(
    onBack: (() -> Unit)?,
    onOpenSchemes: () -> Unit,
) {
    SettingsScaffold(title = stringResource(R.string.composition_title), onBack = onBack) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            Text(stringResource(R.string.composition_needs_scheme))
            androidx.compose.material3.Button(onClick = onOpenSchemes) {
                Text(stringResource(R.string.composition_get_scheme))
            }
        }
    }
}

@Composable
fun UseContentScreen(
    contentId: String,
    isPage: Boolean,
    schemes: List<app.songyan.ime.settings.state.InstalledScheme>,
    sourcesLoaded: Boolean,
    onActivate: suspend (KeyboardCompositionKey) -> Unit,
    onTryOut: () -> Unit,
    onOpenSchemes: () -> Unit,
    onBack: () -> Unit,
    viewModel: LayoutLibraryViewModel = viewModel(factory = LayoutLibraryViewModel.Factory()),
) {
    val registry by viewModel.registry.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    val current = registry
    if (current == null || !sourcesLoaded) {
        LoadingScreen(stringResource(R.string.layouts_action_use), onBack)
        return
    }
    if (schemes.isEmpty()) {
        CompositionEmptyScreen(onBack, onOpenSchemes)
        return
    }
    val families = familiesOfSchemes(schemes)
    val page = if (isPage) current.page(contentId) else null
    val name = page?.name ?: current.resolvedLayout(contentId)?.name
    if (name == null) {
        CompositionEmptyScreen(onBack, onOpenSchemes)
        return
    }
    val targets =
        schemes
            .flatMap { scheme ->
                LayoutInput.Builtin.entries
                    .filter { it.wire in scheme.supportedLayouts }
                    .map { KeyboardCompositionKey(scheme.id, it) }
            }.filter { key -> isPage || current.mainLayoutCandidates(key, families).any { it.id == contentId } }
    var selected by rememberSaveable(contentId) { mutableStateOf<String?>(null) }
    var applied by rememberSaveable(contentId) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LibraryEvents(viewModel, snackbar)
    val scope = rememberCoroutineScope()
    val failureText = stringResource(R.string.features_error_generic)
    val targetName: @Composable (KeyboardCompositionKey) -> String = { key ->
        val method = schemes.first { it.id == key.inputMethodId }.name
        val shape = KeyboardLayoutOption.fromId(key.layout.wire)
        val mainLayout = current.resolvedLayout(current.composition(key, families).mainLayoutID)
        stringResource(R.string.keyboard_title, method, layoutDisplayName(mainLayout, shape).text())
    }
    SettingsScaffold(
        title = stringResource(R.string.content_use_title, name),
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
        ) {
            Text(stringResource(R.string.content_use_target), modifier = Modifier.padding(16.dp))
            targets.forEach { key ->
                RadioRow(
                    title = targetName(key),
                    selected = key.rawValue == selected,
                    enabled = !saving,
                    onClick = {
                        selected = key.rawValue
                        applied = null
                    },
                )
            }
            val key = targets.firstOrNull { it.rawValue == selected }
            if (key != null) {
                androidx.compose.material3.Button(
                    enabled = !saving && applied != key.rawValue,
                    onClick = {
                        saving = true
                        val before = current.composition(key, families)
                        val composition =
                            if (page !=
                                null
                            ) {
                                before.withPageId(page.id, page.kind)
                            } else {
                                before.withMainLayoutId(contentId)
                            }
                        viewModel.saveComposition(key, composition, onRejected = { saving = false }) {
                            scope.launch {
                                try {
                                    onActivate(key)
                                    applied = key.rawValue
                                } catch (_: IOException) {
                                    snackbar.showSnackbar(failureText)
                                } finally {
                                    saving = false
                                }
                            }
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) { Text(stringResource(R.string.content_use_on, targetName(key))) }
                if (applied == key.rawValue) {
                    Text(stringResource(R.string.content_used_on, targetName(key)), modifier = Modifier.padding(16.dp))
                    androidx.compose.material3.OutlinedButton(
                        onClick = onTryOut,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        Text(stringResource(R.string.row_tryout_title))
                    }
                }
            }
        }
    }
}

/** The pairing key of the keyboard in use, or null when no input method is available. */
fun compositionKeyOf(
    inputMethodId: String?,
    layoutId: String,
): KeyboardCompositionKey? {
    val id = inputMethodId?.takeIf { it.isNotBlank() } ?: return null
    return KeyboardCompositionKey(id, LayoutInput.Builtin.fromWire(layoutId) ?: return null)
}
