package app.songyan.ime.settings.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.songyan.ime.settings.R
import app.songyan.ime.settings.state.RecommendedScheme
import app.songyan.ime.settings.ui.RecommendedSchemeDownloadDetails
import app.songyan.ime.settings.ui.RecommendedSchemeSummary
import app.songyan.ime.settings.ui.components.SectionHeader
import app.songyan.ime.settings.ui.components.SettingsScaffold
import app.songyan.ime.settings.ui.openWebPage
import app.songyan.ime.settings.ui.recommendedSchemes

/**
 * Where a new input scheme comes from: the recommended catalogue first, then a file, a folder or a
 * link. Starting any of them returns to the input scheme list through [onStarted], whose card at the
 * top shows the download and installation, so the user sees at once that it began.
 */
@Composable
fun AddSchemeScreen(
    importAvailable: Boolean,
    /** Another import or download is running; only one runs at a time. */
    busy: Boolean,
    onImportScheme: (android.net.Uri) -> Unit,
    onImportSchemeFolder: (android.net.Uri) -> Unit,
    onImportSchemeFromLink: (String) -> Unit,
    onImportRecommendedScheme: (RecommendedScheme) -> Unit,
    onStarted: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // A scheme package is a ZIP; some document providers only offer it as generic binary.
    val schemePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                onImportScheme(uri)
                onStarted()
            }
        }
    val folderPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                onImportSchemeFolder(uri)
                onStarted()
            }
        }
    var showLinkDialog by rememberSaveable { mutableStateOf(false) }
    var linkText by rememberSaveable { mutableStateOf("") }
    var recommendedId by rememberSaveable { mutableStateOf<String?>(null) }
    // One order for the guide and this page: the device's own language decides it, not the app's.
    val catalog = recommendedSchemes()

    SettingsScaffold(title = stringResource(R.string.schemes_add_title), onBack = onBack) { innerPadding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            if (!importAvailable) {
                item { BodyText(stringResource(R.string.schemes_scheme_import_unavailable)) }
                return@LazyColumn
            }
            if (busy) item { BodyText(stringResource(R.string.schemes_add_busy)) }
            item { SectionHeader(stringResource(R.string.schemes_recommended_section)) }
            items(catalog.size, key = { "recommended_" + catalog[it].id }) {
                val entry = catalog[it]
                ListItem(
                    headlineContent = { Text(stringResource(entry.nameRes)) },
                    supportingContent = { RecommendedSchemeSummary(entry) },
                    modifier = Modifier.clickable(enabled = !busy, role = Role.Button) { recommendedId = entry.id },
                )
            }
            item { SectionHeader(stringResource(R.string.schemes_add_import_section)) }
            item {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { schemePicker.launch(SCHEME_ARCHIVE_MIME_TYPES) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.schemes_scheme_import_file))
                    }
                    OutlinedButton(
                        onClick = { folderPicker.launch(null) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.schemes_scheme_import_folder))
                    }
                    OutlinedButton(
                        onClick = { showLinkDialog = true },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.schemes_scheme_import_link))
                    }
                }
            }
            item { BodyText(stringResource(R.string.schemes_scheme_import_note)) }
        }
    }

    if (showLinkDialog) {
        SchemeLinkDialog(
            url = linkText,
            onUrlChange = { linkText = it },
            onConfirm = {
                onImportSchemeFromLink(linkText)
                linkText = ""
                showLinkDialog = false
                onStarted()
            },
            onDismiss = { showLinkDialog = false },
        )
    }

    val selected = catalog.firstOrNull { it.id == recommendedId }
    if (selected != null) {
        val name = stringResource(selected.nameRes)
        AlertDialog(
            onDismissRequest = { recommendedId = null },
            title = { Text(stringResource(R.string.schemes_recommended_confirm_title, name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.schemes_recommended_confirm_body))
                    RecommendedSchemeSummary(selected)
                    RecommendedSchemeDownloadDetails(
                        selected,
                        onOpenHomepage = { openWebPage(context, it) },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        recommendedId = null
                        onImportRecommendedScheme(selected)
                        onStarted()
                    },
                ) {
                    Text(stringResource(R.string.schemes_recommended_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { recommendedId = null }) {
                    Text(stringResource(R.string.schemes_dictionary_cancel))
                }
            },
        )
    }
}
