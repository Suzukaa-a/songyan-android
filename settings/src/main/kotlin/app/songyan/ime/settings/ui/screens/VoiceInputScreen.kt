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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.songyan.ime.settings.R
import app.songyan.ime.settings.ui.VoiceInputViewModel
import app.songyan.ime.settings.ui.components.RadioRow
import app.songyan.ime.settings.ui.components.SectionHeader
import app.songyan.ime.settings.ui.components.SettingsScaffold
import app.songyan.ime.settings.ui.components.SwitchRow

/**
 * 语音输入: the keyboard's own voice input. The switch shows the microphone in the toolbar; the list
 * picks the installed script that turns the recording into text. Only scripts that declare audio
 * input and can run on this device are listed, so a script that asks for text only never appears.
 */
@Composable
fun VoiceInputScreen(
    onBack: (() -> Unit)?,
    model: VoiceInputViewModel = viewModel(factory = VoiceInputViewModel.Factory()),
) {
    val state by model.uiState.collectAsStateWithLifecycle()

    SettingsScaffold(title = stringResource(R.string.voice_title), onBack = onBack) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.voice_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            SwitchRow(
                title = stringResource(R.string.voice_switch_title),
                subtitle = stringResource(R.string.voice_switch_subtitle),
                checked = state.enabled,
                onCheckedChange = model::setEnabled,
            )
            SectionHeader(stringResource(R.string.voice_scripts_section))
            if (state.scripts.isEmpty()) {
                Text(
                    text =
                        stringResource(
                            if (state.loading) R.string.voice_scripts_loading else R.string.voice_scripts_empty,
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            } else {
                for (script in state.scripts) {
                    RadioRow(
                        title =
                            if (script.isAddon) {
                                stringResource(R.string.voice_addon_option, script.name)
                            } else {
                                script.name
                            },
                        selected = script.id == state.selectedScriptId,
                        enabled = state.enabled,
                        onClick = { model.selectScript(script.id) },
                    )
                }
            }
        }
    }
}
