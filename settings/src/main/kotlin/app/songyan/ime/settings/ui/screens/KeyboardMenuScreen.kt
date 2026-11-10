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
import app.songyan.ime.settings.state.CurrentMainLayout
import app.songyan.ime.settings.state.InstalledScheme
import app.songyan.ime.settings.state.KeyboardLayoutOption
import app.songyan.ime.settings.state.SettingsPreferences
import app.songyan.ime.settings.state.SettingsPreferencesHolder
import app.songyan.ime.settings.state.StoredSourceInfo
import app.songyan.ime.settings.state.bundledLayoutNameRes
import app.songyan.ime.settings.ui.components.SectionHeader
import app.songyan.ime.settings.ui.components.SettingsScaffold
import app.songyan.ime.settings.ui.components.SwitchRow
import app.songyan.ime.settings.ui.text
import kotlinx.coroutines.launch

/**
 * 键盘菜单: which input methods and layouts the keyboard's menu offers. A scheme package can carry
 * a dozen input methods; hiding the ones you never use keeps the menu short. The input scheme in use
 * comes first under its own name, followed by the layouts of its input method in use, worded as
 * 我的键盘 and the menu word them: its physical boards (romaji and kana for Japanese) by their own
 * names, then the shapes it can use. Every other input scheme follows under its name; its input
 * methods that stay on can be switched to from the menu. The ones in use stay in the menu, so their
 * switches are fixed on.
 */
@Composable
fun KeyboardMenuScreen(
    schemes: List<InstalledScheme>,
    inUseSchemeId: String?,
    layoutId: String,
    onBack: () -> Unit,
    preferences: SettingsPreferences = SettingsPreferencesHolder.current,
    otherSources: List<StoredSourceInfo> = emptyList(),
    /** The name of the input scheme in use; null before the stored schemes are read. */
    sourceName: String? = null,
    /** The main layout the keyboard in use draws, when the catalog has resolved it. */
    mainLayout: CurrentMainLayout? = null,
    /** The shapes the input method in use can switch to; null keeps the static capability. */
    supportedLayouts: Set<String>? = null,
    /** The physical boards the input method in use types on. */
    boards: List<String> = emptyList(),
) {
    val hiddenSchemas by preferences.menuHiddenSchemas.collectAsStateWithLifecycle(emptySet())
    val hiddenLayouts by preferences.menuHiddenLayouts.collectAsStateWithLifecycle(emptySet())
    val scope = rememberCoroutineScope()
    val inUse = stringResource(R.string.keyboard_menu_in_use)
    val inputMethod = schemes.firstOrNull { it.id == inUseSchemeId }?.name?.takeIf { it.isNotEmpty() }

    fun setLayoutShown(
        id: String,
        shown: Boolean,
    ) {
        scope.launch { preferences.setMenuHiddenLayouts(if (shown) hiddenLayouts - id else hiddenLayouts + id) }
    }

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
            SectionHeader(sourceName ?: stringResource(R.string.keyboard_menu_input_methods))
            for (scheme in schemes.filterNot { KeyboardResourceSet.EncodingAdapter.isGeneratedID(it.id) }) {
                val current = scheme.id == inUseSchemeId
                SchemeSwitch(scheme, current, inUse, hiddenSchemas, preferences)
            }
            SectionHeader(
                inputMethod?.let { stringResource(R.string.keyboard_menu_layouts_of, it) }
                    ?: stringResource(R.string.keyboard_menu_layouts),
            )
            val onIdentityShape = layoutId == KeyboardLayoutOption.QWERTY26.id
            for (board in boards) {
                val current = onIdentityShape && mainLayout?.id == board
                SwitchRow(
                    title = stringResource(requireNotNull(bundledLayoutNameRes(board))),
                    subtitle = if (current) inUse else null,
                    checked = current || board !in hiddenLayouts,
                    enabled = !current,
                    onCheckedChange = { setLayoutShown(board, it) },
                )
            }
            for (option in KeyboardLayoutOption.entries) {
                val current = option.id == layoutId
                // The same rows as 我的键盘: a shape the input method cannot use is left out, and the
                // identity shape stays only when the keyboard draws none of the boards.
                val boardsCoverIt =
                    option == KeyboardLayoutOption.QWERTY26 &&
                        boards.isNotEmpty() &&
                        (!onIdentityShape || mainLayout?.id in boards)
                if (boardsCoverIt || (!option.availableIn(supportedLayouts) && !current)) continue
                SwitchRow(
                    title =
                        if (current && mainLayout != null) {
                            mainLayout.displayName.text()
                        } else {
                            stringResource(option.nameRes)
                        },
                    subtitle = if (current) inUse else null,
                    checked = current || option.id !in hiddenLayouts,
                    enabled = !current,
                    onCheckedChange = { setLayoutShown(option.id, it) },
                )
            }
            for (source in otherSources) {
                SectionHeader(source.name)
                for (scheme in source.schemes.filterNot { KeyboardResourceSet.EncodingAdapter.isGeneratedID(it.id) }) {
                    SchemeSwitch(scheme, false, inUse, hiddenSchemas, preferences)
                }
            }
        }
    }
}

@Composable
private fun SchemeSwitch(
    scheme: InstalledScheme,
    current: Boolean,
    inUse: String,
    hiddenSchemas: Set<String>,
    preferences: SettingsPreferences,
) {
    val scope = rememberCoroutineScope()
    SwitchRow(
        title = scheme.name,
        subtitle = if (current) inUse else null,
        checked = current || scheme.id !in hiddenSchemas,
        enabled = !current,
        onCheckedChange = { shown ->
            scope.launch {
                preferences.setMenuHiddenSchemas(if (shown) hiddenSchemas - scheme.id else hiddenSchemas + scheme.id)
            }
        },
    )
}
