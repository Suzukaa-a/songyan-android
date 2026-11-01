package app.songyan.ime.settings.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.songyan.ime.settings.R
import app.songyan.ime.settings.platform.LicenceComponent
import app.songyan.ime.settings.ui.LicensesViewModel
import app.songyan.ime.settings.ui.components.SectionHeader
import app.songyan.ime.settings.ui.components.SettingsRow
import app.songyan.ime.settings.ui.components.SettingsScaffold
import app.songyan.ime.settings.ui.components.StateMessage

/**
 * Third-party licences: a component index first (name, version and licence); each component's
 * notice details and the verbatim licence text open on their own page. The bundled notice document
 * is written for the iOS build and inlines its licence texts behind ASCII rules, so it is parsed for
 * display instead of being shown as-is; no legal text is shortened or removed.
 */
@Composable
fun LicensesScreen(
    onOpen: (index: Int) -> Unit,
    onBack: (() -> Unit)?,
    viewModel: LicensesViewModel = viewModel(),
) {
    val bundle by viewModel.bundle.collectAsStateWithLifecycle()
    SettingsScaffold(
        title = stringResource(R.string.licenses_title),
        onBack = onBack,
    ) { innerPadding ->
        val loaded = bundle
        when {
            loaded == null ->
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(R.string.licenses_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

            loaded.index.entries.isEmpty() ->
                StateMessage(
                    title = stringResource(R.string.licenses_empty),
                    icon = Icons.Outlined.Description,
                    actionLabel = stringResource(R.string.licenses_retry),
                    onAction = viewModel::reload,
                    modifier = Modifier.padding(innerPadding),
                )

            else -> {
                val componentCount = loaded.index.components.size
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    item {
                        Text(
                            text = stringResource(R.string.licenses_intro),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    item { SectionHeader(stringResource(R.string.licenses_section_components)) }
                    itemsIndexed(loaded.index.components) { index, component ->
                        SettingsRow(
                            title = component.displayTitle,
                            subtitle = component.licence,
                            onClick = { onOpen(index) },
                        )
                    }
                    if (loaded.index.extraTexts.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.licenses_section_texts)) }
                        itemsIndexed(loaded.index.extraTexts) { index, text ->
                            SettingsRow(
                                title = text.label,
                                subtitle = null,
                                onClick = { onOpen(componentCount + index) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One component's details and licence text, opened from the list by its position. */
@Composable
fun LicenceDetailRoute(
    index: Int,
    onBack: (() -> Unit)?,
    viewModel: LicensesViewModel = viewModel(),
) {
    val bundle by viewModel.bundle.collectAsStateWithLifecycle()
    val component = bundle?.index?.entries?.getOrNull(index)
    if (component == null) {
        SettingsScaffold(title = stringResource(R.string.licenses_title), onBack = onBack) { innerPadding ->
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        return
    }
    LicenceDetailScreen(component = component, onBack = onBack)
}

@Composable
private fun LicenceDetailScreen(
    component: LicenceComponent,
    onBack: (() -> Unit)?,
) {
    SettingsScaffold(
        title = component.title,
        onBack = onBack,
    ) { innerPadding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            component.licence?.let { item { LicenceField(stringResource(R.string.licenses_field_licence), it) } }
            component.copyright?.let { item { LicenceField(stringResource(R.string.licenses_field_copyright), it) } }
            component.upstream?.let { item { LicenceField(stringResource(R.string.licenses_field_upstream), it) } }
            if (component.texts.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.licenses_no_text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                component.texts.forEach { text ->
                    // One text needs no name of its own; several are told apart by the licence they carry.
                    item(key = "header-${text.label}") {
                        SectionHeader(
                            if (component.texts.size == 1) stringResource(R.string.licenses_full_text) else text.label,
                        )
                    }
                    item(key = "text-${text.label}") {
                        // Selectable so a user can copy a licence snippet out of the app.
                        SelectionContainer {
                            Text(
                                text = text.text,
                                style = MaterialTheme.typography.bodySmall,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LicenceField(
    label: String,
    value: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(text = value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
