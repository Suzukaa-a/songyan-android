package app.songyan.ime.settings.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.songyan.ime.settings.R
import app.songyan.ime.settings.state.KeyboardLayoutOption
import app.songyan.ime.settings.ui.components.LayoutThumbnail
import app.songyan.ime.settings.ui.components.RadioRow
import app.songyan.ime.settings.ui.components.SectionHeader
import app.songyan.ime.settings.ui.components.SettingsRow
import app.songyan.ime.settings.ui.components.SettingsScaffold

/**
 * My keyboards: the layouts to switch between. The list already marks the current layout with a
 * selected radio, so only the input method (which the list cannot show) stays as one compact line
 * above it instead of a card repeating the same selection.
 */
@Composable
fun KeyboardsScreen(
    inputMethodName: String?,
    layoutId: String,
    onSelectLayout: (KeyboardLayoutOption) -> Unit,
    onBack: (() -> Unit)?,
    onOpenLayouts: () -> Unit = {},
    onOpenPages: () -> Unit = {},
    onOpenComposition: () -> Unit = {},
    onOpenMenu: () -> Unit = {},
    onOpenQuickSymbols: () -> Unit = {},
) {
    val selected = KeyboardLayoutOption.fromId(layoutId)
    SettingsScaffold(
        title = stringResource(R.string.row_keyboards_title),
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
            val inputMethod = inputMethodName?.takeIf { it.isNotEmpty() }
            Text(
                text =
                    if (inputMethod == null) {
                        stringResource(R.string.keyboards_current_none)
                    } else {
                        stringResource(R.string.keyboards_current_compact, inputMethod)
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )

            SectionHeader(stringResource(R.string.keyboards_layout_section))
            Column {
                KeyboardLayoutOption.entries.forEachIndexed { index, option ->
                    RadioRow(
                        title = stringResource(option.nameRes),
                        subtitle = option.noteRes(option.available)?.let { stringResource(it) },
                        selected = option == selected,
                        enabled = option.available,
                        onClick = { onSelectLayout(option) },
                        trailing = { LayoutThumbnail(option.thumbnailRows) },
                    )
                }
            }

            SectionHeader(stringResource(R.string.keyboards_pages_section))
            SettingsRow(
                title = stringResource(R.string.composition_title),
                subtitle = stringResource(R.string.composition_entry_body),
                onClick = onOpenComposition,
            )
            SettingsRow(
                title = stringResource(R.string.layouts_title),
                subtitle = stringResource(R.string.layouts_entry_body),
                onClick = onOpenLayouts,
            )
            SettingsRow(
                title = stringResource(R.string.pages_title),
                subtitle = stringResource(R.string.pages_entry_body),
                onClick = onOpenPages,
            )
            SettingsRow(
                title = stringResource(R.string.keyboard_menu_title),
                subtitle = stringResource(R.string.keyboard_menu_entry_body),
                onClick = onOpenMenu,
            )
            SettingsRow(
                title = stringResource(R.string.t9_symbols_title),
                subtitle = stringResource(R.string.t9_symbols_entry_body),
                onClick = onOpenQuickSymbols,
            )
        }
    }
}
