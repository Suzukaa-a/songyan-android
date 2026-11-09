package app.songyan.ime.settings.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.songyan.ime.candidate.ToolbarCapacity
import app.songyan.ime.candidate.ToolbarItem
import app.songyan.ime.candidate.ToolbarLayout
import app.songyan.ime.settings.R
import app.songyan.ime.settings.state.SettingsPreferences
import app.songyan.ime.settings.state.SettingsPreferencesHolder
import app.songyan.ime.settings.ui.components.SettingsScaffold
import kotlinx.coroutines.launch

/** Test tags of one toolbar row, its switch and its actions menu button. */
fun toolbarRowTag(item: ToolbarItem): String = "toolbar-row-${item.wire}"

fun toolbarSwitchTag(item: ToolbarItem): String = "toolbar-switch-${item.wire}"

fun toolbarMenuTag(item: ToolbarItem): String = "toolbar-menu-${item.wire}"

const val TOOLBAR_RESET_TAG: String = "toolbar-reset"

private fun itemName(item: ToolbarItem): Int =
    when (item) {
        ToolbarItem.CLIPBOARD -> R.string.toolbar_item_clipboard
        ToolbarItem.INPUT_METHODS -> R.string.toolbar_item_input_methods
        ToolbarItem.EMOJI -> R.string.toolbar_item_emoji
        ToolbarItem.EDIT -> R.string.toolbar_item_edit
        ToolbarItem.ONE_HAND -> R.string.toolbar_item_one_hand
        ToolbarItem.SETTINGS -> R.string.toolbar_item_settings
    }

/**
 * 工具栏: which buttons the idle toolbar shows and in what order. Every change is written at once and
 * the keyboard follows while it is open. The width limit comes from the same function the keyboard
 * uses; shown buttons past it are marked, because the keyboard leaves them out.
 */
@Composable
fun ToolbarScreen(
    onBack: () -> Unit,
    preferences: SettingsPreferences = SettingsPreferencesHolder.current,
    widthDp: Float = LocalConfiguration.current.screenWidthDp.toFloat(),
) {
    val stored by preferences.toolbarLayout.collectAsStateWithLifecycle("")
    val layout = remember(stored) { ToolbarLayout.parse(stored) }
    val scope = rememberCoroutineScope()
    val limit = ToolbarCapacity.limit(widthDp)
    val save: (ToolbarLayout) -> Unit = { next ->
        scope.launch { preferences.setToolbarLayout(if (next.isDefault) null else next.toWire()) }
    }

    SettingsScaffold(title = stringResource(R.string.toolbar_title), onBack = onBack) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.toolbar_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            Text(
                text = stringResource(R.string.toolbar_limit, limit),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("toolbar-limit"),
            )
            var shownSoFar = 0
            for (entry in layout.entries) {
                val overflow = entry.shown && shownSoFar >= limit
                if (entry.shown) shownSoFar++
                ToolbarItemRow(
                    item = entry.item,
                    shown = entry.shown,
                    overflow = overflow,
                    canMoveEarlier = layout.canMoveEarlier(entry.item),
                    canMoveLater = layout.canMoveLater(entry.item),
                    onShownChange = { save(layout.withShown(entry.item, it)) },
                    onMoveEarlier = { save(layout.moveEarlier(entry.item)) },
                    onMoveLater = { save(layout.moveLater(entry.item)) },
                )
            }
            TextButton(
                onClick = { save(ToolbarLayout.DEFAULT) },
                enabled = !layout.isDefault,
                modifier = Modifier.padding(horizontal = 8.dp).testTag(TOOLBAR_RESET_TAG),
            ) {
                Text(stringResource(R.string.toolbar_reset))
            }
        }
    }
}

@Composable
private fun ToolbarItemRow(
    item: ToolbarItem,
    shown: Boolean,
    overflow: Boolean,
    canMoveEarlier: Boolean,
    canMoveLater: Boolean,
    onShownChange: (Boolean) -> Unit,
    onMoveEarlier: () -> Unit,
    onMoveLater: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val name = stringResource(itemName(item))
    val more = stringResource(R.string.toolbar_more, name)
    ListItem(
        headlineContent = { Text(name) },
        supportingContent = if (overflow) ({ Text(stringResource(R.string.toolbar_over_width)) }) else null,
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.testTag(toolbarMenuTag(item)),
                    ) {
                        Icon(imageVector = Icons.Outlined.MoreVert, contentDescription = more)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.toolbar_move_up)) },
                            enabled = canMoveEarlier,
                            onClick = {
                                menuOpen = false
                                onMoveEarlier()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.toolbar_move_down)) },
                            enabled = canMoveLater,
                            onClick = {
                                menuOpen = false
                                onMoveLater()
                            },
                        )
                    }
                }
                Switch(
                    checked = shown,
                    onCheckedChange = onShownChange,
                    modifier = Modifier.testTag(toolbarSwitchTag(item)).semantics { contentDescription = name },
                )
            }
        },
        modifier = Modifier.testTag(toolbarRowTag(item)),
    )
}
