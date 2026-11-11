package app.songyan.ime.settings.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.songyan.ime.appearance.AppearanceDocument
import app.songyan.ime.appearance.AppearancePresets
import app.songyan.ime.appearance.BackgroundFill
import app.songyan.ime.appearance.KeyboardPalette
import app.songyan.ime.keyboard.support.CandidateTextSize
import app.songyan.ime.settings.R
import app.songyan.ime.settings.state.AppearanceFailure
import app.songyan.ime.settings.state.AppearanceImportOutcome
import app.songyan.ime.settings.state.AppearanceOption
import app.songyan.ime.settings.ui.AppearanceViewModel
import app.songyan.ime.settings.ui.components.RadioRow
import app.songyan.ime.settings.ui.components.SectionHeader
import app.songyan.ime.settings.ui.components.SettingsRow
import app.songyan.ime.settings.ui.components.SettingsScaffold
import app.songyan.ime.settings.ui.theme.ThemeMode
import app.songyan.ime.settings.ui.theme.ThemePreferencesHolder

/**
 * 键盘外观: light/dark and one list of keyboard colours (the wallpaper palette plus the colour schemes).
 *
 * Every choice is written through the app module, so it applies to the keyboard and to this app at
 * the same time; nothing here is a preview-only switch. Each colour scheme carries a small sample of
 * its keyboard colours, because a list of names alone gives no idea what 墨夜 or 青竹 looks like.
 */
@Composable
fun AppearanceScreen(
    onBack: (() -> Unit)?,
    candidateTextSize: CandidateTextSize?,
    onCandidateTextSizeChanged: (CandidateTextSize) -> Unit,
    onOpenCandidateBar: () -> Unit,
    viewModel: AppearanceViewModel = viewModel(factory = AppearanceViewModel.Factory()),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val themeMode by ThemePreferencesHolder.current.mode.collectAsStateWithLifecycle()
    val failure by viewModel.failure.collectAsStateWithLifecycle()
    val importOutcome by viewModel.importOutcome.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val failureText =
        when (failure) {
            AppearanceFailure.APPLY_FAILED -> stringResource(R.string.appearance_apply_failed)
            AppearanceFailure.EXPORT_FAILED -> stringResource(R.string.appearance_export_failed)
            AppearanceFailure.DELETE_FAILED -> stringResource(R.string.appearance_delete_failed)
            null -> null
        }
    val importText = importOutcome?.let { importMessage(it) }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<AppearanceOption?>(null) }
    val importer =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.importFrom(uri)
        }
    val exporter =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val id = exportId
            exportId = null
            if (uri != null && id != null) viewModel.exportTo(id, uri)
        }

    LaunchedEffect(Unit) { viewModel.refresh() }
    LaunchedEffect(failureText) {
        if (failureText != null) {
            snackbar.showSnackbar(failureText)
            viewModel.consumeFailure()
        }
    }
    LaunchedEffect(importText) {
        if (importText != null) {
            snackbar.showSnackbar(importText)
            viewModel.consumeImport()
        }
    }

    SettingsScaffold(
        title = stringResource(R.string.appearance_title),
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
            SectionHeader(stringResource(R.string.appearance_section_theme))
            Column(modifier = Modifier.selectableGroup()) {
                ThemeMode.entries.forEach { mode ->
                    RadioRow(
                        title = themeModeLabel(mode),
                        selected = mode == themeMode,
                        onClick = { ThemePreferencesHolder.current.setMode(mode) },
                    )
                }
            }

            // Wallpaper colours and the colour schemes are one choice, so they are one list: the
            // chosen entry is the only one marked, and no note has to explain how they interact.
            SectionHeader(stringResource(R.string.appearance_section_palette))
            if (!state.available) {
                Text(
                    text = stringResource(R.string.appearance_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            val dark = darkPreview(themeMode)
            val previews = remember(dark) { builtInPalettePreviews(dark) }
            Column(modifier = Modifier.selectableGroup()) {
                if (state.dynamicColorAvailable) {
                    RadioRow(
                        title = stringResource(R.string.appearance_dynamic_title),
                        selected = state.wallpaperSelected,
                        onClick = { viewModel.setDynamicColor(true) },
                    )
                }
                state.appearances.forEach { option ->
                    AppearanceRow(
                        option = option,
                        selected = state.isColourSelected(option.id),
                        preview = previews[option.id],
                        onClick = { viewModel.select(option.id) },
                        onExport = {
                            exportId = option.id
                            exporter.launch(viewModel.exportFileName(option.id))
                        },
                        onDelete = { deleting = option },
                    )
                }
            }

            CandidateBarLookSection(candidateTextSize, onCandidateTextSizeChanged, onOpenCandidateBar)

            SectionHeader(stringResource(R.string.appearance_section_files))
            SettingsRow(
                title = stringResource(R.string.appearance_import_title),
                subtitle = stringResource(R.string.appearance_import_body),
                onClick = { importer.launch(arrayOf("*/*")) },
            )
        }
    }

    deleting?.let { option ->
        val name = appearanceName(option)
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.appearance_delete_title)) },
            text = { Text(stringResource(R.string.appearance_delete_body, name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(option.id)
                        deleting = null
                    },
                ) { Text(stringResource(R.string.appearance_action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun importMessage(outcome: AppearanceImportOutcome): String =
    when (outcome) {
        is AppearanceImportOutcome.Imported -> stringResource(R.string.appearance_import_done, outcome.name)
        is AppearanceImportOutcome.Rejected ->
            when (outcome.reason) {
                AppearanceImportOutcome.Reason.UNREADABLE -> stringResource(R.string.appearance_import_unreadable)
                AppearanceImportOutcome.Reason.TOO_LARGE -> stringResource(R.string.appearance_import_too_large)
                AppearanceImportOutcome.Reason.NOT_AN_APPEARANCE ->
                    stringResource(R.string.appearance_import_not_appearance)
                AppearanceImportOutcome.Reason.INVALID_VALUE ->
                    outcome.path?.let { stringResource(R.string.appearance_import_invalid, it) }
                        ?: stringResource(R.string.appearance_import_invalid_plain)
                AppearanceImportOutcome.Reason.UNSUPPORTED_VERSION -> stringResource(R.string.appearance_import_version)
                AppearanceImportOutcome.Reason.STORAGE -> stringResource(R.string.appearance_import_storage)
            }
    }

/** Which light/dark variant the picked colour scheme is previewed in. */
@Composable
private fun darkPreview(themeMode: ThemeMode): Boolean =
    when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

/**
 * Resolved palettes for the built-in appearances, keyed by id. User-authored appearances are not
 * resolvable here without loading their files, so they show no sample rather than a wrong one.
 */
private fun builtInPalettePreviews(dark: Boolean): Map<String, KeyboardPalette> =
    AppearancePresets.ALL_BUILT_IN.associate { document ->
        document.id to KeyboardPalette.of(document, dark)
    }

@Composable
private fun AppearanceRow(
    option: AppearanceOption,
    selected: Boolean,
    preview: KeyboardPalette?,
    onClick: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val name = appearanceName(option)
    RadioRow(
        title = name,
        selected = selected,
        onClick = onClick,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                preview?.let { KeyboardPaletteSample(it) }
                AppearanceMenu(name, builtIn = option.builtIn, onExport = onExport, onDelete = onDelete)
            }
        },
    )
}

/** Export for every appearance, delete only for the user's own: a built-in comes back with the app. */
@Composable
private fun AppearanceMenu(
    name: String,
    builtIn: Boolean,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.appearance_more_actions, name),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.appearance_action_export)) },
                onClick = {
                    open = false
                    onExport()
                },
            )
            if (!builtIn) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.appearance_action_delete)) },
                    onClick = {
                        open = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/** Three keycaps and the keyboard surface at a glance; decorative, the row text names the scheme. */
@Composable
private fun KeyboardPaletteSample(palette: KeyboardPalette) {
    Row(
        modifier =
            Modifier
                .size(width = 52.dp, height = 34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(palette.background))
                .then(gradientOf(palette.backgroundFill))
                .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        listOf(palette.keySurface, palette.keySurface, palette.accent).forEach { fill ->
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(fill)),
            )
        }
    }
}

/** The skin's gradient over the panel colour; a picture is left out of a swatch this small. */
private fun gradientOf(fill: BackgroundFill?): Modifier {
    if (fill !is BackgroundFill.Gradient) return Modifier
    val colors = fill.colors.map { Color(it) }
    val brush =
        when (fill.direction) {
            BackgroundFill.Direction.VERTICAL -> Brush.verticalGradient(colors)
            BackgroundFill.Direction.HORIZONTAL -> Brush.horizontalGradient(colors)
            BackgroundFill.Direction.DIAGONAL -> Brush.linearGradient(colors)
        }
    return Modifier.background(brush)
}

/** A user-authored appearance carries its own name; built-ins resolve from resources. */
@Composable
private fun appearanceName(option: AppearanceOption): String {
    option.name?.let { return it }
    return when (option.id) {
        AppearancePresets.SONGYAN_ID -> stringResource(R.string.appearance_name_songyan)
        "builtin-ink" -> stringResource(R.string.appearance_name_ink)
        "builtin-bamboo" -> stringResource(R.string.appearance_name_bamboo)
        "builtin-paper" -> stringResource(R.string.appearance_name_paper)
        "builtin-galaxy" -> stringResource(R.string.appearance_name_galaxy)
        "builtin-twilight" -> stringResource(R.string.appearance_name_dusk)
        AppearanceDocument.SYSTEM_DEFAULT_ID -> stringResource(R.string.appearance_name_system)
        // An id with no localized name is shown as-is rather than given a made-up label.
        else -> option.id
    }
}

@Composable
private fun themeModeLabel(mode: ThemeMode): String =
    stringResource(
        when (mode) {
            ThemeMode.SYSTEM -> R.string.appearance_theme_system
            ThemeMode.LIGHT -> R.string.appearance_theme_light
            ThemeMode.DARK -> R.string.appearance_theme_dark
        },
    )

/**
 * Everything about how the candidate bar looks sits with the rest of the look, so it is not split
 * between this page and the input options. [candidateTextSize] is null until the stored choice is
 * read; the size list is not drawn before that, so a default never flashes by.
 */
@Composable
fun CandidateBarLookSection(
    candidateTextSize: CandidateTextSize?,
    onCandidateTextSizeChanged: (CandidateTextSize) -> Unit,
    onOpenCandidateBar: () -> Unit,
) {
    SectionHeader(stringResource(R.string.appearance_section_candidates))
    SettingsRow(
        title = stringResource(R.string.candidate_bar_title),
        subtitle = stringResource(R.string.candidate_bar_entry_subtitle),
        onClick = onOpenCandidateBar,
    )
    if (candidateTextSize == null) return
    Text(
        text = stringResource(R.string.options_candidate_size_section),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
    )
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

private fun candidateTextSizeLabel(size: CandidateTextSize): Int =
    when (size) {
        CandidateTextSize.SMALL -> R.string.options_candidate_size_small
        CandidateTextSize.STANDARD -> R.string.options_candidate_size_standard
        CandidateTextSize.LARGE -> R.string.options_candidate_size_large
        CandidateTextSize.EXTRA_LARGE -> R.string.options_candidate_size_extra_large
    }
