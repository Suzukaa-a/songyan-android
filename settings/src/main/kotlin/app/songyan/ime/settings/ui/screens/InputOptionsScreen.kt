package app.songyan.ime.settings.ui.screens

import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.songyan.ime.keyboard.engine.EngineOptionKind
import app.songyan.ime.keyboard.support.CandidateTextSize
import app.songyan.ime.keyboard.support.KeyFeedbackSettings
import app.songyan.ime.settings.R
import app.songyan.ime.settings.ui.EngineOptionsUiState
import app.songyan.ime.settings.ui.components.RadioRow
import app.songyan.ime.settings.ui.components.SectionHeader
import app.songyan.ime.settings.ui.components.SettingsRow
import app.songyan.ime.settings.ui.components.SettingsScaffold
import app.songyan.ime.settings.ui.components.SwitchRow

/** The system's own touch sound and touch vibration switches, re-read when the user comes back. */
data class SystemFeedbackState(
    val soundEnabled: Boolean,
    val hapticsEnabled: Boolean,
)

@Composable
private fun rememberSystemFeedbackState(): SystemFeedbackState {
    val resolver = LocalContext.current.contentResolver

    fun read() =
        SystemFeedbackState(
            soundEnabled = Settings.System.getInt(resolver, Settings.System.SOUND_EFFECTS_ENABLED, 0) != 0,
            hapticsEnabled = Settings.System.getInt(resolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0,
        )
    var state by remember { mutableStateOf(read()) }
    // The user may flip the system switch while this screen is in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state = read() }
    return state
}

/**
 * Input options: what the input field and candidate bar show, the candidate text size, and key
 * sound and vibration. Sound and vibration follow the system until the user touches a control, and
 * one action brings them back to following the system.
 */
@Composable
fun InputOptionsScreen(
    keyboardFeel: KeyboardFeelState,
    onKeyHeightChanged: (String) -> Unit,
    onBottomGapChanged: (Int) -> Unit = {},
    onLongPressDelayChanged: (Int?) -> Unit = {},
    onOneHandChanged: (String) -> Unit,
    onKeyPreviewChanged: (Boolean) -> Unit,
    onKeyFlickChanged: (Boolean) -> Unit = {},
    onDoubleSpacePeriodChanged: (Boolean) -> Unit = {},
    onReleaseHapticChanged: (Boolean) -> Unit,
    onWidePositionChanged: (String) -> Unit = {},
    onWidePositionCustomChanged: (Int) -> Unit = {},
    onSplitModeChanged: (String) -> Unit = {},
    onReadingPlacementChanged: (String) -> Unit = {},
    candidateTextSize: CandidateTextSize,
    onCandidateTextSizeChanged: (CandidateTextSize) -> Unit,
    keyFeedback: KeyFeedbackSettings,
    onKeyFeedbackChanged: (KeyFeedbackSettings) -> Unit,
    onBack: (() -> Unit)?,
    onOpenFeatures: () -> Unit = {},
    onOpenCandidateBar: () -> Unit = {},
    onOpenShuangpinHints: () -> Unit = {},
    engineOptions: EngineOptionsUiState = EngineOptionsUiState(),
    onEngineOptionChanged: (String, Boolean) -> Unit = { _, _ -> },
    system: SystemFeedbackState = rememberSystemFeedbackState(),
) {
    SettingsScaffold(
        title = stringResource(R.string.row_options_title),
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
            KeyboardFeelSection(
                state = keyboardFeel,
                onKeyHeight = onKeyHeightChanged,
                onBottomGap = onBottomGapChanged,
                onLongPressDelay = onLongPressDelayChanged,
                onOneHand = onOneHandChanged,
                onKeyPreview = onKeyPreviewChanged,
                onKeyFlick = onKeyFlickChanged,
                onDoubleSpacePeriod = onDoubleSpacePeriodChanged,
                onReleaseHaptic = onReleaseHapticChanged,
                onWidePosition = onWidePositionChanged,
                onWidePositionCustom = onWidePositionCustomChanged,
                onSplitMode = onSplitModeChanged,
            )
            SettingsRow(
                title = stringResource(R.string.features_title),
                subtitle = stringResource(R.string.features_entry_body),
                onClick = onOpenFeatures,
            )
            SettingsRow(
                title = stringResource(R.string.shuangpin_title),
                subtitle = stringResource(R.string.shuangpin_footer),
                onClick = onOpenShuangpinHints,
            )

            EngineOptionsSection(engineOptions, onEngineOptionChanged)

            SectionHeader(stringResource(R.string.options_reading_section))
            Column(modifier = Modifier.selectableGroup()) {
                RadioRow(
                    title = stringResource(R.string.options_reading_row_title),
                    subtitle = stringResource(R.string.options_reading_row_body),
                    selected = keyboardFeel.readingPlacement == "row",
                    onClick = { onReadingPlacementChanged("row") },
                )
                RadioRow(
                    title = stringResource(R.string.options_reading_inline_title),
                    subtitle = stringResource(R.string.options_reading_inline_body),
                    selected = keyboardFeel.readingPlacement == "inline",
                    onClick = { onReadingPlacementChanged("inline") },
                )
            }

            SettingsRow(
                title = stringResource(R.string.candidate_bar_title),
                subtitle = stringResource(R.string.candidate_bar_entry_subtitle),
                onClick = onOpenCandidateBar,
            )

            SectionHeader(stringResource(R.string.options_candidate_size_section))
            Column(modifier = Modifier.selectableGroup()) {
                CandidateTextSize.entries.forEach { size ->
                    RadioRow(
                        title = stringResource(candidateTextSizeLabel(size)),
                        selected = size == candidateTextSize,
                        onClick = { onCandidateTextSizeChanged(size) },
                    )
                }
            }

            SectionHeader(stringResource(R.string.options_feedback_section))
            FeedbackControls(keyFeedback, onKeyFeedbackChanged, system)
        }
    }
}

/** The switches of the input method in use (traditional output, emoji, ...); absent when it has none. */
@Composable
private fun EngineOptionsSection(
    state: EngineOptionsUiState,
    onChange: (String, Boolean) -> Unit,
) {
    if (state.options.isEmpty()) return
    SectionHeader(stringResource(R.string.options_engine_section))
    var openGroupName by rememberSaveable { mutableStateOf<String?>(null) }
    state.options.distinctBy { it.choiceGroup.firstOrNull() ?: it.name }.forEach { option ->
        if (option.choiceGroup.isEmpty()) {
            SwitchRow(
                title = stringResource(engineOptionTitle(option.kind)),
                subtitle = null,
                checked = option.on,
                onCheckedChange = { onChange(option.name, it) },
            )
        } else {
            SettingsRow(
                title = stringResource(R.string.options_engine_character_form),
                subtitle = state.options.firstOrNull { it.name in option.choiceGroup && it.on }?.choiceLabel,
                onClick = { openGroupName = option.choiceGroup.first() },
            )
        }
    }
    state.options.firstOrNull { it.name == openGroupName }?.choiceGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { openGroupName = null },
            title = { Text(stringResource(R.string.options_engine_character_form)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                    group.mapNotNull { name -> state.options.find { it.name == name } }.forEach { option ->
                        RadioRow(
                            title = option.choiceLabel ?: option.name,
                            selected = option.on,
                            onClick = {
                                onChange(option.name, true)
                                openGroupName = null
                            },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { openGroupName = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    Text(
        text =
            stringResource(
                if (state.saveFailed) R.string.options_engine_save_failed else R.string.options_engine_footer,
            ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

private fun engineOptionTitle(kind: EngineOptionKind): Int =
    when (kind) {
        EngineOptionKind.TRADITIONAL -> R.string.options_engine_traditional
        EngineOptionKind.SIMPLIFIED -> R.string.options_engine_simplified
        EngineOptionKind.EMOJI -> R.string.options_engine_emoji
        EngineOptionKind.FULL_SHAPE -> R.string.options_engine_full_shape
    }

private fun candidateTextSizeLabel(size: CandidateTextSize): Int =
    when (size) {
        CandidateTextSize.SMALL -> R.string.options_candidate_size_small
        CandidateTextSize.STANDARD -> R.string.options_candidate_size_standard
        CandidateTextSize.LARGE -> R.string.options_candidate_size_large
        CandidateTextSize.EXTRA_LARGE -> R.string.options_candidate_size_extra_large
    }

@Composable
private fun FeedbackControls(
    value: KeyFeedbackSettings,
    onChange: (KeyFeedbackSettings) -> Unit,
    system: SystemFeedbackState,
) {
    val soundOn = value.playsSound(system.soundEnabled)
    val hapticsOn = value.vibrates(system.hapticsEnabled)
    SwitchRow(
        title = stringResource(R.string.options_sound_title),
        subtitle =
            when {
                !system.soundEnabled -> stringResource(R.string.options_sound_system_off)
                value.soundEnabled == null -> stringResource(R.string.options_follow_system)
                else -> null
            },
        checked = soundOn,
        enabled = system.soundEnabled,
        onCheckedChange = { onChange(value.copy(soundEnabled = it)) },
    )
    FeedbackSlider(
        title = stringResource(R.string.options_sound_volume_title),
        stored = value.soundVolume,
        enabled = soundOn,
        onChange = { onChange(value.copy(soundVolume = it)) },
    )
    SwitchRow(
        title = stringResource(R.string.options_haptics_title),
        subtitle = if (value.hapticsEnabled == null) stringResource(R.string.options_follow_system) else null,
        checked = hapticsOn,
        onCheckedChange = { onChange(value.copy(hapticsEnabled = it)) },
    )
    FeedbackSlider(
        title = stringResource(R.string.options_haptic_strength_title),
        stored = value.hapticStrength,
        enabled = hapticsOn,
        onChange = { onChange(value.copy(hapticStrength = it)) },
    )
    if (value != KeyFeedbackSettings()) {
        TextButton(
            onClick = { onChange(KeyFeedbackSettings()) },
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Text(stringResource(R.string.options_feedback_reset))
        }
    }
}

/** Where a slider starts while its value still follows the system. */
private const val FOLLOW_SYSTEM_POSITION = 0.6f

/** A 0..1 slider; its summary says "follows system" until the user has moved it. */
@Composable
private fun FeedbackSlider(
    title: String,
    stored: Float?,
    enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    // Local while dragging; a stored change (including the one this drag causes) restarts from it.
    var position by remember(stored) { mutableFloatStateOf(stored ?: FOLLOW_SYSTEM_POSITION) }
    var moved by remember(stored) { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Column {
                Text(
                    if (stored == null && !moved) {
                        stringResource(R.string.options_follow_system)
                    } else {
                        stringResource(R.string.options_percent, (position * 100).toInt())
                    },
                )
                Slider(
                    value = position,
                    onValueChange = {
                        moved = true
                        position = it
                    },
                    onValueChangeFinished = { onChange(position) },
                    enabled = enabled,
                )
            }
        },
    )
}
