package app.songyan.ime.settings.ui.screens

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.songyan.ime.packages.EngineFeatureReport.Availability
import app.songyan.ime.packages.FuzzyPinyin
import app.songyan.ime.settings.R
import app.songyan.ime.settings.state.EngineFeaturesSnapshot
import app.songyan.ime.settings.state.GrammarSelection
import app.songyan.ime.settings.state.LuaComponentInfo
import app.songyan.ime.settings.ui.EngineFeaturesViewModel
import app.songyan.ime.settings.ui.components.DetailRow
import app.songyan.ime.settings.ui.components.ImeDialog
import app.songyan.ime.settings.ui.components.SectionHeader
import app.songyan.ime.settings.ui.components.SettingsRow
import app.songyan.ime.settings.ui.components.SettingsScaffold
import app.songyan.ime.settings.ui.components.SwitchRow
import app.songyan.ime.settings.ui.components.TransferIndicator
import app.songyan.ime.settings.ui.normalizeDictionaryUrl

/**
 * Typo correction, fuzzy pinyin, next-word prediction, the grammar model and the scheme's Lua
 * components, for the input method in use. Everything shown is read from the keyboard's current
 * build; a change is saved by deploying it, which the screen waits for.
 */
@Composable
fun EngineFeaturesScreen(
    schemaId: String?,
    schemaName: String?,
    onBack: (() -> Unit)?,
    viewModel: EngineFeaturesViewModel = viewModel(factory = EngineFeaturesViewModel.Factory()),
) {
    LaunchedEffect(schemaId) { viewModel.show(schemaId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val databasePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(viewModel::importPredictionDatabase)
        }
    val modelPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(viewModel::importGrammarModel)
        }
    var linkTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var fuzzyDialog by rememberSaveable { mutableStateOf(false) }
    var grammarDialog by rememberSaveable { mutableStateOf(false) }

    SettingsScaffold(
        title = stringResource(R.string.features_title),
        onBack = onBack,
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp),
        ) {
            if (!schemaName.isNullOrEmpty()) {
                Text(
                    text = stringResource(R.string.features_current, schemaName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (state.saving) {
                // Once the file has arrived the rest is the install, which cannot be stopped halfway.
                val downloading = state.cancellable && state.transfer?.finished != true
                TransferIndicator(state.transfer, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                Text(
                    text =
                        stringResource(
                            if (downloading) R.string.features_downloading else R.string.features_saving,
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                if (downloading) {
                    TextButton(onClick = viewModel::cancelDownload, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text(stringResource(R.string.features_stop))
                    }
                }
            }
            if (state.failed) {
                // The reason stays until the user closes it; a download that can work on a second try offers one.
                Text(
                    text = state.failure ?: stringResource(R.string.features_error_generic),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Row(modifier = Modifier.padding(horizontal = 8.dp)) {
                    if (state.retryable) {
                        TextButton(onClick = viewModel::retry) { Text(stringResource(R.string.features_retry)) }
                    }
                    TextButton(onClick = viewModel::consumeFailure) { Text(stringResource(R.string.features_dismiss)) }
                }
            }
            val snapshot = state.snapshot
            when {
                state.loading -> DetailRow(title = stringResource(R.string.features_loading), body = "")
                snapshot == null ->
                    DetailRow(
                        title = stringResource(R.string.features_title),
                        body = stringResource(R.string.features_unavailable),
                    )
                else ->
                    FeatureRows(
                        snapshot = snapshot,
                        enabled = !state.saving,
                        onTypoCorrection = viewModel::setTypoCorrection,
                        onOpenFuzzy = { fuzzyDialog = true },
                        onPrediction = viewModel::setPrediction,
                        onImportDatabase = { databasePicker.launch(arrayOf("*/*")) },
                        onImportDatabaseFromLink = { linkTarget = LINK_PREDICTION },
                        onOpenGrammar = { grammarDialog = true },
                        onImportModel = { modelPicker.launch(arrayOf("*/*")) },
                        onImportModelFromLink = { linkTarget = LINK_GRAMMAR },
                        onLuaComponent = viewModel::setLuaComponent,
                    )
            }
        }
    }

    linkTarget?.let { target ->
        FeatureLinkDialog(
            onDismiss = { linkTarget = null },
            onConfirm = { url ->
                linkTarget = null
                if (target == LINK_PREDICTION) {
                    viewModel.importPredictionDatabaseFromUrl(url)
                } else {
                    viewModel.importGrammarModelFromUrl(url)
                }
            },
        )
    }

    val snapshot = state.snapshot
    if (fuzzyDialog && snapshot != null) {
        FuzzyPinyinDialog(
            initial = snapshot.fuzzyPairs,
            onDismiss = { fuzzyDialog = false },
            onSave = { pairs ->
                fuzzyDialog = false
                viewModel.setFuzzyPinyin(pairs)
            },
        )
    }
    if (grammarDialog && snapshot != null) {
        GrammarDialog(
            snapshot = snapshot,
            onDismiss = { grammarDialog = false },
            onSelect = { selection ->
                grammarDialog = false
                viewModel.setGrammar(selection)
            },
        )
    }
}

@Composable
private fun FeatureRows(
    snapshot: EngineFeaturesSnapshot,
    enabled: Boolean,
    onTypoCorrection: (Boolean) -> Unit,
    onOpenFuzzy: () -> Unit,
    onPrediction: (Boolean) -> Unit,
    onImportDatabase: () -> Unit,
    onImportDatabaseFromLink: () -> Unit,
    onOpenGrammar: () -> Unit,
    onImportModel: () -> Unit,
    onImportModelFromLink: () -> Unit,
    onLuaComponent: (String, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val schemeManaged = stringResource(R.string.features_scheme_managed)

    SectionHeader(stringResource(R.string.features_correction_section))
    SwitchRow(
        title = stringResource(R.string.features_typo_title),
        subtitle =
            when (snapshot.typoCorrection) {
                Availability.AVAILABLE -> stringResource(R.string.features_typo_body)
                Availability.SCHEME -> schemeManaged
                Availability.UNSUPPORTED -> stringResource(R.string.features_typo_unsupported)
            },
        checked = snapshot.typoCorrectionOn,
        onCheckedChange = onTypoCorrection,
        enabled = enabled && snapshot.typoCorrection == Availability.AVAILABLE,
    )
    when (snapshot.fuzzyPinyin) {
        Availability.AVAILABLE ->
            SettingsRow(
                title = stringResource(R.string.features_fuzzy_title),
                subtitle =
                    if (snapshot.fuzzyPairs.isEmpty()) {
                        stringResource(R.string.features_fuzzy_off)
                    } else {
                        stringResource(
                            R.string.features_fuzzy_on,
                            FuzzyPinyin.allPairs
                                .filter { it.id in snapshot.fuzzyPairs }
                                .joinToString(stringResource(R.string.features_list_separator)) { it.label },
                        )
                    },
                onClick = { if (enabled) onOpenFuzzy() },
            )
        Availability.SCHEME -> DetailRow(title = stringResource(R.string.features_fuzzy_title), body = schemeManaged)
        Availability.UNSUPPORTED ->
            DetailRow(
                title = stringResource(R.string.features_fuzzy_title),
                body = stringResource(R.string.features_fuzzy_unsupported),
            )
    }

    SectionHeader(stringResource(R.string.features_prediction_section))
    val database = snapshot.predictionDatabase
    SwitchRow(
        title = stringResource(R.string.features_prediction_title),
        subtitle =
            when {
                snapshot.prediction == Availability.SCHEME -> schemeManaged
                snapshot.prediction == Availability.UNSUPPORTED ->
                    stringResource(
                        R.string.features_prediction_unsupported,
                    )
                database == null -> stringResource(R.string.features_prediction_needs_database)
                else -> stringResource(R.string.features_prediction_body)
            },
        checked = snapshot.predictionOn,
        onCheckedChange = onPrediction,
        enabled = enabled && snapshot.prediction == Availability.AVAILABLE && database != null,
    )
    if (snapshot.prediction != Availability.SCHEME) {
        SettingsRow(
            title =
                stringResource(
                    if (database == null) R.string.features_prediction_import else R.string.features_prediction_replace,
                ),
            subtitle =
                database?.let {
                    stringResource(
                        R.string.features_prediction_imported,
                        Formatter.formatShortFileSize(context, it.size),
                    )
                },
            onClick = { if (enabled) onImportDatabase() },
        )
        SettingsRow(
            title = stringResource(R.string.features_link_import),
            subtitle = null,
            onClick = { if (enabled) onImportDatabaseFromLink() },
        )
    }

    SectionHeader(stringResource(R.string.features_grammar_section))
    when (snapshot.grammar) {
        Availability.UNSUPPORTED ->
            DetailRow(
                title = stringResource(R.string.features_grammar_title),
                body = stringResource(R.string.features_grammar_unsupported),
            )
        Availability.SCHEME ->
            DetailRow(title = stringResource(R.string.features_grammar_title), body = schemeManaged)
        Availability.AVAILABLE -> {
            SettingsRow(
                title = stringResource(R.string.features_grammar_title),
                subtitle = grammarLabel(snapshot),
                onClick = { if (enabled) onOpenGrammar() },
            )
            SettingsRow(
                title = stringResource(R.string.features_grammar_import),
                subtitle = stringResource(R.string.features_grammar_body),
                onClick = { if (enabled) onImportModel() },
            )
            SettingsRow(
                title = stringResource(R.string.features_link_import),
                subtitle = null,
                onClick = { if (enabled) onImportModelFromLink() },
            )
        }
    }

    if (snapshot.luaComponents.isNotEmpty()) {
        SectionHeader(stringResource(R.string.features_lua_section))
        if (snapshot.lua == Availability.SCHEME) {
            Text(
                text = stringResource(R.string.features_lua_scheme_managed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        snapshot.luaComponents.forEachIndexed { index, component ->
            SwitchRow(
                title = component.displayName,
                subtitle = luaKind(component),
                checked = component.enabled,
                onCheckedChange = { onLuaComponent(component.prescription, it) },
                enabled = enabled && snapshot.lua == Availability.AVAILABLE,
            )
        }
    }
}

@Composable
private fun grammarLabel(snapshot: EngineFeaturesSnapshot): String =
    when (val choice = snapshot.grammarChoice) {
        GrammarSelection.SchemeDefault -> stringResource(R.string.features_grammar_default)
        GrammarSelection.Off -> stringResource(R.string.features_grammar_off)
        is GrammarSelection.Model ->
            snapshot.grammarModels.firstOrNull { it.name == choice.name }?.displayName
                ?: stringResource(R.string.features_grammar_default)
    }

@Composable
private fun luaKind(component: LuaComponentInfo): String {
    val kind =
        stringResource(
            when (component.list) {
                "processors" -> R.string.features_lua_processors
                "segmentors" -> R.string.features_lua_segmentors
                "translators" -> R.string.features_lua_translators
                else -> R.string.features_lua_filters
            },
        )
    return if (component.list == "processors") stringResource(R.string.features_lua_affects_input, kind) else kind
}

/** Multiple choice with an explicit save: each save deploys, so the choices go out together. */
@Composable
internal fun FuzzyPinyinDialog(
    initial: Set<String>,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(initial.toList()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.features_fuzzy_dialog_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                for (pair in FuzzyPinyin.allPairs) {
                    val checked = pair.id in selected
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .toggleable(value = checked, role = Role.Checkbox) { on ->
                                    selected = if (on) selected + pair.id else selected - pair.id
                                }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Text(pair.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(selected.toSet()) },
                enabled = selected.toSet() != initial,
            ) { Text(stringResource(R.string.features_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.features_cancel)) } },
    )
}

@Composable
private fun GrammarDialog(
    snapshot: EngineFeaturesSnapshot,
    onDismiss: () -> Unit,
    onSelect: (GrammarSelection) -> Unit,
) {
    val options =
        listOf(
            GrammarSelection.SchemeDefault to stringResource(R.string.features_grammar_default),
            GrammarSelection.Off to stringResource(R.string.features_grammar_off),
        ) + snapshot.grammarModels.map { GrammarSelection.Model(it.name) to it.displayName }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.features_grammar_dialog_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                for ((selection, label) in options) {
                    val selected = selection == snapshot.grammarChoice
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = selected, role = Role.RadioButton) {
                                    if (selected) onDismiss() else onSelect(selection)
                                }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.features_cancel)) } },
    )
}

private const val LINK_PREDICTION = "prediction"
private const val LINK_GRAMMAR = "grammar"

/** Asks for an https link; the keyboard and settings only go online after this dialog's "Import". */
@Composable
private fun FeatureLinkDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    val valid = normalizeDictionaryUrl(url) != null
    ImeDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.features_link_import),
        confirmButton = {
            TextButton(onClick = { onConfirm(url.trim()) }, enabled = valid) {
                Text(stringResource(R.string.features_link_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.features_cancel)) } },
    ) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text(stringResource(R.string.features_link_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            isError = url.isNotBlank() && !valid,
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        Text(
            text = stringResource(R.string.features_link_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
