package app.songyan.ime.settings.ui.screens

import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.songyan.ime.appearance.font.FontCascade
import app.songyan.ime.appearance.font.FontRole
import app.songyan.ime.settings.R
import app.songyan.ime.settings.state.FontEntry
import app.songyan.ime.settings.state.FontImportResult
import app.songyan.ime.settings.ui.FontsViewModel
import app.songyan.ime.settings.ui.components.RadioRow
import app.songyan.ime.settings.ui.components.SectionHeader
import app.songyan.ime.settings.ui.components.SettingsRow
import app.songyan.ime.settings.ui.components.SettingsScaffold

/**
 * 字体: import font files, choose the font for candidate text and for key labels, and see both
 * drawn with the choice. The previews use the same typefaces the keyboard builds.
 */
@Composable
fun FontsScreen(
    onBack: (() -> Unit)?,
    viewModel: FontsViewModel = viewModel(factory = FontsViewModel.Factory()),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var deleting by remember { mutableStateOf<FontEntry?>(null) }
    val importer =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            viewModel.import(uris)
        }
    val message = state.result?.let { importMessage(it) }
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            viewModel.consumeResult()
        }
    }

    SettingsScaffold(
        title = stringResource(R.string.fonts_title),
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
            if (!state.loaded) return@Column
            FontPreview(state.candidateTypeface, state.keyTypeface)

            SectionHeader(stringResource(R.string.fonts_candidates_section))
            FontChoice(state.fonts, state.candidates, state.rowTypefaces, "candidate") {
                viewModel.choose(FontRole.CANDIDATES, it)
            }
            SectionHeader(stringResource(R.string.fonts_keys_section))
            FontChoice(state.fonts, state.keys, state.rowTypefaces, "key") {
                viewModel.choose(FontRole.KEYS, it)
            }
            Text(
                stringResource(R.string.fonts_fallback_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            SectionHeader(stringResource(R.string.fonts_imported_section))
            state.fonts.forEach { font ->
                ListItem(
                    modifier = Modifier.testTag("font-row-" + font.file),
                    headlineContent = {
                        Text(
                            font.family ?: font.file,
                            fontFamily = state.rowTypefaces[font.file]?.let { FontFamily(it) },
                        )
                    },
                    supportingContent = {
                        Text(
                            if (font.readable) font.file else stringResource(R.string.fonts_unreadable, font.file),
                            color =
                                if (font.readable) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                        )
                    },
                    trailingContent = {
                        IconButton(onClick = { deleting = font }) {
                            Icon(Icons.Outlined.Delete, stringResource(R.string.fonts_delete_action, font.file))
                        }
                    },
                )
            }
            SettingsRow(
                title = stringResource(R.string.fonts_import_title),
                subtitle = stringResource(R.string.fonts_import_body),
                onClick = { importer.launch(arrayOf("*/*")) },
                modifier = Modifier.testTag("font-import"),
            )
        }
    }

    deleting?.let { font ->
        val name = font.family ?: font.file
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.fonts_delete_title, name)) },
            text = { Text(stringResource(R.string.fonts_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(font.file)
                        deleting = null
                    },
                ) { Text(stringResource(R.string.fonts_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** The candidate row and a few keys as the keyboard draws them with the current choice. */
@Composable
private fun FontPreview(
    candidates: Typeface?,
    keys: Typeface?,
) {
    Column(
        modifier =
            Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .padding(12.dp)
                .testTag("font-preview"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.testTag("font-preview-candidates"),
        ) {
            stringResource(R.string.fonts_preview_candidates).split(' ').forEach {
                Text(it, fontSize = 22.sp, fontFamily = candidates?.let { face -> FontFamily(face) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("font-preview-keys")) {
            stringResource(R.string.fonts_preview_keys).split(' ').forEach {
                Box(
                    modifier =
                        Modifier
                            .size(44.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(it, fontSize = 20.sp, fontFamily = keys?.let { face -> FontFamily(face) })
                }
            }
        }
    }
}

@Composable
private fun FontChoice(
    fonts: List<FontEntry>,
    chosen: String,
    typefaces: Map<String, Typeface>,
    tag: String,
    onChoose: (String) -> Unit,
) {
    Column(modifier = Modifier.selectableGroup()) {
        RadioRow(
            title = stringResource(R.string.fonts_system),
            selected = chosen == FontCascade.SYSTEM || fonts.none { it.file == chosen && it.readable },
            onClick = { onChoose(FontCascade.SYSTEM) },
            modifier = Modifier.testTag("$tag-font-system"),
        )
        fonts.filter { it.readable }.forEach { font ->
            RadioRow(
                title = font.family.orEmpty(),
                selected = chosen == font.file,
                onClick = { onChoose(font.file) },
                modifier = Modifier.testTag("$tag-font-" + font.file),
                trailing = {
                    typefaces[font.file]?.let {
                        Text(
                            stringResource(R.string.fonts_row_sample),
                            fontFamily = FontFamily(it),
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun importMessage(result: FontImportResult): String =
    when {
        result.failed -> stringResource(R.string.fonts_import_failed)
        result.imported.isEmpty() && result.rejected.isNotEmpty() ->
            stringResource(R.string.fonts_import_rejected, result.rejected.first())
        result.rejected.isNotEmpty() ->
            stringResource(R.string.fonts_import_partial, result.imported.size, result.rejected.first())
        result.imported.size == 1 -> stringResource(R.string.fonts_import_done_one, result.imported.first())
        else -> stringResource(R.string.fonts_import_done_many, result.imported.size)
    }
