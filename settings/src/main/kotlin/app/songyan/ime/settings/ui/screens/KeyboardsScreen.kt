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
import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.settings.R
import app.songyan.ime.settings.state.CurrentMainLayout
import app.songyan.ime.settings.state.KeyboardLayoutOption
import app.songyan.ime.settings.state.bundledLayoutNameRes
import app.songyan.ime.settings.ui.components.LayoutThumbnail
import app.songyan.ime.settings.ui.components.RadioRow
import app.songyan.ime.settings.ui.components.SectionHeader
import app.songyan.ime.settings.ui.components.SettingsRow
import app.songyan.ime.settings.ui.components.SettingsScaffold
import app.songyan.ime.settings.ui.text

/**
 * My keyboards: the layouts to switch between. The list already marks the current layout with a
 * selected radio, so only the input method (which the list cannot show) stays as one compact line
 * above it instead of a card repeating the same selection.
 */
@Composable
fun KeyboardsScreen(
    inputMethodName: String?,
    layoutId: String,
    /** The main layout the keyboard in use draws, when the catalog has resolved it. */
    mainLayout: CurrentMainLayout? = null,
    /** The shapes the input method in use can switch to; null keeps the static capability. */
    supportedLayouts: Set<String>?,
    onSelectLayout: (KeyboardLayoutOption) -> Unit,
    /**
     * The physical boards the input method in use types on (romaji and kana for Japanese). Each is
     * its own row, so the user never has to find the board inside the page pairing.
     */
    boards: List<String> = emptyList(),
    onSelectBoard: (String) -> Unit = {},
    onBack: (() -> Unit)?,
    onOpenLayouts: () -> Unit = {},
    onOpenPages: () -> Unit = {},
    onOpenComposition: () -> Unit = {},
    onOpenMenu: () -> Unit = {},
    onOpenSchemes: () -> Unit = {},
) {
    val selected = KeyboardLayoutOption.fromId(layoutId)
    // The row marks the shape in use; a keyboard that draws a physical board names that board instead,
    // so the selected row never claims "26 keys" for a zhuyin or cangjie keyboard.
    val selectedName = mainLayout?.displayName
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
                val onIdentityShape = selected == KeyboardLayoutOption.QWERTY26
                for (board in boards) {
                    RadioRow(
                        title = stringResource(requireNotNull(bundledLayoutNameRes(board))),
                        selected = onIdentityShape && mainLayout?.id == board,
                        onClick = { onSelectBoard(board) },
                        trailing = { LayoutThumbnail(boardThumbnail(board)) },
                    )
                }
                KeyboardLayoutOption.entries.forEachIndexed { index, option ->
                    val available = option.availableIn(supportedLayouts)
                    // A shape the input method cannot use is left out rather than offered greyed: it
                    // is not coming later. The one in use stays so the screen never hides the
                    // current choice; the identity shape stays only when it draws none of the boards.
                    val boardsCoverIt =
                        option == KeyboardLayoutOption.QWERTY26 &&
                            boards.isNotEmpty() &&
                            (!onIdentityShape || mainLayout?.id in boards)
                    if (boardsCoverIt || (!available && option != selected)) return@forEachIndexed
                    RadioRow(
                        title =
                            if (option == selected && selectedName != null) {
                                selectedName.text()
                            } else {
                                stringResource(option.nameRes)
                            },
                        subtitle = option.noteRes(available)?.let { stringResource(it) },
                        selected = option == selected,
                        enabled = available,
                        onClick = { onSelectLayout(option) },
                        trailing = { LayoutThumbnail(option.thumbnailRows) },
                    )
                }
            }

            SectionHeader(stringResource(R.string.keyboards_pages_section))
            SettingsRow(
                title = stringResource(R.string.composition_title),
                subtitle =
                    stringResource(
                        if (inputMethod ==
                            null
                        ) {
                            R.string.composition_needs_scheme
                        } else {
                            R.string.composition_entry_body
                        },
                    ),
                onClick = if (inputMethod == null) onOpenSchemes else onOpenComposition,
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
        }
    }
}

/** Relative key widths per row of a bundled board, read from the board itself. */
private fun boardThumbnail(id: String): List<List<Double>> =
    BuiltinLayouts
        .byId(id)
        ?.rows
        ?.map { row -> row.keys.map { it.widthWeight } }
        .orEmpty()
