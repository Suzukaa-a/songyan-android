package app.songyan.ime.settings.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import app.songyan.ime.keyboard.engine.PublishedKeyboard
import app.songyan.ime.layout.ApplicableLayouts
import app.songyan.ime.layout.KeyboardCompositionKey
import app.songyan.ime.layout.KeyboardPageKind
import app.songyan.ime.layout.KeyboardPageMarkCoverage
import app.songyan.ime.layout.LayoutInput
import app.songyan.ime.layout.compositionKey
import app.songyan.ime.settings.R
import app.songyan.ime.settings.state.EngineStatus
import app.songyan.ime.settings.state.KeyboardLayoutOption
import app.songyan.ime.settings.state.displayPercent
import app.songyan.ime.settings.state.familiesOfSchemes
import app.songyan.ime.settings.state.hasProgress
import app.songyan.ime.settings.state.keyboardScheme
import app.songyan.ime.settings.state.layoutDisplayName
import app.songyan.ime.settings.state.pageDisplayName
import app.songyan.ime.settings.ui.LayoutLibraryViewModel
import app.songyan.ime.settings.ui.components.LoadingScreen
import app.songyan.ime.settings.ui.components.RadioRow
import app.songyan.ime.settings.ui.components.SectionHeader
import app.songyan.ime.settings.ui.components.SettingsScaffold
import app.songyan.ime.settings.ui.text
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * 页面搭配: which 123, symbol and ABC pages one keyboard uses. The layout is the keyboard's own, so
 * it is not chosen here. A choice is stored at once and reaches the keyboard the next time it draws.
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
            for (kind in KeyboardPageKind.entries) {
                SectionHeader(stringResource(pageKindLabel(kind)))
                Column(modifier = Modifier.selectableGroup()) {
                    current.pages.filter { it.kind == kind }.forEach { page ->
                        RadioRow(
                            title = pageDisplayName(page).text(),
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

/**
 * Shown while no input scheme is in use. A scheme that was just added has no keyboard to set up
 * until it is prepared, so the page waits for it instead of offering to add another one; once
 * preparation ends the caller swaps this page for the real one.
 */
@Composable
fun CompositionEmptyScreen(
    engine: EngineStatus,
    hasSchemes: Boolean,
    onBack: (() -> Unit)?,
    onAddScheme: () -> Unit,
    onChooseScheme: () -> Unit,
) {
    SettingsScaffold(title = stringResource(R.string.composition_title), onBack = onBack) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            when {
                engine is EngineStatus.Preparing -> {
                    Text(
                        if (engine.hasProgress) {
                            stringResource(R.string.home_engine_preparing, engine.displayPercent)
                        } else {
                            stringResource(R.string.home_engine_preparing_unknown)
                        },
                    )
                    if (engine.hasProgress) {
                        LinearProgressIndicator(
                            progress = { engine.displayPercent / 100f },
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp))
                    }
                }

                hasSchemes -> {
                    Text(stringResource(R.string.composition_no_scheme_in_use))
                    Button(onClick = onChooseScheme, modifier = Modifier.padding(top = 16.dp)) {
                        Text(stringResource(R.string.composition_choose_scheme))
                    }
                }

                else -> {
                    Text(stringResource(R.string.composition_needs_scheme))
                    Button(
                        onClick = onAddScheme,
                        modifier = Modifier.padding(top = 16.dp),
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    ) {
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                        Spacer(
                            Modifier.size(ButtonDefaults.IconSpacing),
                        )
                        Text(stringResource(R.string.schemes_add_title))
                    }
                }
            }
        }
    }
}

@Composable
fun UseContentScreen(
    contentId: String,
    isPage: Boolean,
    schemes: List<app.songyan.ime.settings.state.InstalledScheme>,
    /** The user's keyboards, each naming its layout: a page is used on one of them. */
    keyboards: List<PublishedKeyboard>,
    sourcesLoaded: Boolean,
    onActivate: suspend (PublishedKeyboard) -> Unit,
    onTryOut: () -> Unit,
    onOpenSchemes: () -> Unit,
    onChooseScheme: () -> Unit,
    engine: EngineStatus,
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
        CompositionEmptyScreen(engine, schemes.isNotEmpty(), onBack, onOpenSchemes, onChooseScheme)
        return
    }
    val families = familiesOfSchemes(schemes)
    val page = if (isPage) current.page(contentId) else null
    val layout = if (page == null) current.resolvedLayout(contentId) else null
    val name =
        when {
            page != null -> pageDisplayName(page).text()
            layout != null -> layoutDisplayName(layout, KeyboardLayoutOption.fromId(layout.id)).text()
            else -> null
        }
    if (name == null) {
        CompositionEmptyScreen(engine, schemes.isNotEmpty(), onBack, onOpenSchemes, onChooseScheme)
        return
    }
    // A page goes onto a keyboard the user has; a layout becomes a keyboard of an input method it fits.
    val targets =
        if (isPage) {
            keyboards
        } else {
            schemes
                .flatMap { ApplicableLayouts.of(it.keyboardScheme(), current, families) }
                .filter { it.board == contentId }
        }
    var selected by rememberSaveable(contentId) { mutableStateOf<String?>(null) }
    var applied by rememberSaveable(contentId) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LibraryEvents(viewModel, snackbar)
    val scope = rememberCoroutineScope()
    val failureText = stringResource(R.string.features_error_generic)
    val targetName: @Composable (PublishedKeyboard) -> String = { keyboard ->
        val method = schemes.firstOrNull { it.id == keyboard.schemaID }?.name ?: keyboard.schemaID
        val shape = KeyboardLayoutOption.fromId(keyboard.layout.wire)
        val mainLayout =
            current.resolvedLayout(current.composition(keyboard.compositionKey(), families).mainLayoutID)
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
            targets.forEach { keyboard ->
                RadioRow(
                    title = targetName(keyboard),
                    selected = keyboard.id == selected,
                    enabled = !saving,
                    onClick = {
                        selected = keyboard.id
                        applied = null
                    },
                )
            }
            val keyboard = targets.firstOrNull { it.id == selected }
            if (keyboard != null) {
                Button(
                    enabled = !saving && applied != keyboard.id,
                    onClick = {
                        saving = true
                        val activate = {
                            scope.launch {
                                try {
                                    onActivate(keyboard)
                                    applied = keyboard.id
                                } catch (_: IOException) {
                                    snackbar.showSnackbar(failureText)
                                } finally {
                                    saving = false
                                }
                            }
                            Unit
                        }
                        if (page == null) {
                            // The layout is the keyboard itself: there is no pairing to save first.
                            activate()
                        } else {
                            val key = keyboard.compositionKey()
                            val composition = current.composition(key, families).withPageId(page.id, page.kind)
                            viewModel.saveComposition(
                                key,
                                composition,
                                onRejected = { saving = false },
                                onSaved = activate,
                            )
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) { Text(stringResource(R.string.content_use_on, targetName(keyboard))) }
                if (applied == keyboard.id) {
                    Text(
                        stringResource(R.string.content_used_on, targetName(keyboard)),
                        modifier = Modifier.padding(16.dp),
                    )
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
