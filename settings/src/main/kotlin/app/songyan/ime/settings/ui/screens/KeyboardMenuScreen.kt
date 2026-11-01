package app.songyan.ime.settings.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.songyan.ime.keyboard.engine.KeyboardResourceSet
import app.songyan.ime.settings.R
import app.songyan.ime.settings.state.InstalledScheme
import app.songyan.ime.settings.state.KeyboardLayoutOption
import app.songyan.ime.settings.state.SettingsPreferences
import app.songyan.ime.settings.state.SettingsPreferencesHolder
import app.songyan.ime.settings.ui.components.SectionHeader
import app.songyan.ime.settings.ui.components.SettingsScaffold
import app.songyan.ime.settings.ui.components.SwitchRow
import kotlinx.coroutines.launch

/**
 * 键盘菜单: which input methods and layouts the keyboard's menu offers. A scheme package can carry
 * a dozen input methods; hiding the ones you never use keeps the menu short. The ones in use stay
 * in the menu, so their switches are fixed on.
 */
@Composable
fun KeyboardMenuScreen(
    schemes: List<InstalledScheme>,
    inUseSchemeId: String?,
    layoutId: String,
    onBack: () -> Unit,
    preferences: SettingsPreferences = SettingsPreferencesHolder.current,
) {
    val hiddenSchemas by preferences.menuHiddenSchemas.collectAsStateWithLifecycle(emptySet())
    val hiddenLayouts by preferences.menuHiddenLayouts.collectAsStateWithLifecycle(emptySet())
    val scope = rememberCoroutineScope()
    val inUse = stringResource(R.string.keyboard_menu_in_use)

    SettingsScaffold(title = stringResource(R.string.keyboard_menu_title), onBack = onBack) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.keyboard_menu_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            SectionHeader(stringResource(R.string.keyboard_menu_input_methods))
            for (scheme in schemes.filterNot { KeyboardResourceSet.EncodingAdapter.isGeneratedID(it.id) }) {
                val current = scheme.id == inUseSchemeId
                SwitchRow(
                    title = scheme.name,
                    subtitle = if (current) inUse else null,
                    checked = current || scheme.id !in hiddenSchemas,
                    enabled = !current,
                    onCheckedChange = { shown ->
                        scope.launch {
                            preferences.setMenuHiddenSchemas(
                                if (shown) {
                                    hiddenSchemas - scheme.id
                                } else {
                                    hiddenSchemas +
                                        scheme.id
                                },
                            )
                        }
                    },
                )
            }
            SectionHeader(stringResource(R.string.keyboard_menu_layouts))
            for (option in KeyboardLayoutOption.entries) {
                val current = option.id == layoutId
                SwitchRow(
                    title = stringResource(option.nameRes),
                    subtitle = if (current) inUse else null,
                    checked = current || option.id !in hiddenLayouts,
                    enabled = !current,
                    onCheckedChange = { shown ->
                        scope.launch {
                            preferences.setMenuHiddenLayouts(
                                if (shown) {
                                    hiddenLayouts - option.id
                                } else {
                                    hiddenLayouts +
                                        option.id
                                },
                            )
                        }
                    },
                )
            }
        }
    }
}
