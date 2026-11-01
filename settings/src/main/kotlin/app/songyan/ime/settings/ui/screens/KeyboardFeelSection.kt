package app.songyan.ime.settings.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.songyan.ime.settings.R
import app.songyan.ime.settings.state.SettingsPreferences
import app.songyan.ime.settings.ui.components.RadioRow
import app.songyan.ime.settings.ui.components.SectionHeader
import app.songyan.ime.settings.ui.components.SettingsRow
import app.songyan.ime.settings.ui.components.SwitchRow

/** What the keyboard-feel rows show; read from the same preferences the keyboard reads. */
data class KeyboardFeelState(
    val keyHeight: String = SettingsPreferences.DEFAULT_KEY_HEIGHT,
    val bottomGapDp: Int = 0,
    val longPressDelayMs: Int? = null,
    val showSwitchKey: Boolean = false,
    val keyPreview: Boolean = true,
    val keyFlick: Boolean = true,
    val doubleSpacePeriod: Boolean = true,
    val hideIdleToolbar: Boolean = false,
    val releaseHaptic: Boolean = false,
    val oneHand: String = "off",
    val widePosition: String = "center",
    val widePositionCustom: Int = SettingsPreferences.WIDE_POSITION_CENTER,
    val splitMode: String = "layout",
    val readingPlacement: String = SettingsPreferences.DEFAULT_READING_PLACEMENT,
)

/** Key height, one-handed mode, key preview and release vibration: the keyboard's physical feel. */
@Composable
fun KeyboardFeelSection(
    state: KeyboardFeelState,
    onKeyHeight: (String) -> Unit,
    onBottomGap: (Int) -> Unit = {},
    onLongPressDelay: (Int?) -> Unit = {},
    onOneHand: (String) -> Unit,
    onKeyPreview: (Boolean) -> Unit,
    onKeyFlick: (Boolean) -> Unit = {},
    onDoubleSpacePeriod: (Boolean) -> Unit = {},
    onReleaseHaptic: (Boolean) -> Unit,
    onWidePosition: (String) -> Unit = {},
    onWidePositionCustom: (Int) -> Unit = {},
    onSplitMode: (String) -> Unit = {},
) {
    var bottomGapOpen by rememberSaveable { mutableStateOf(false) }
    var longPressOpen by rememberSaveable { mutableStateOf(false) }
    if (bottomGapOpen) {
        ValueSliderDialog(
            title = stringResource(R.string.options_bottom_gap_title),
            range = 0..SettingsPreferences.BOTTOM_GAP_MAX_DP,
            step = SettingsPreferences.BOTTOM_GAP_STEP_DP,
            initial = state.bottomGapDp,
            label = { stringResource(R.string.options_bottom_gap_value, it) },
            onConfirm = onBottomGap,
            onDismiss = { bottomGapOpen = false },
            resetLabel = null,
            onReset = {},
        )
    }
    if (longPressOpen) {
        ValueSliderDialog(
            title = stringResource(R.string.options_long_press_title),
            range = SettingsPreferences.LONG_PRESS_MIN_MS..SettingsPreferences.LONG_PRESS_MAX_MS,
            step = SettingsPreferences.LONG_PRESS_STEP_MS,
            initial = state.longPressDelayMs ?: DEFAULT_LONG_PRESS_START_MS,
            label = { stringResource(R.string.options_long_press_value, it) },
            onConfirm = { onLongPressDelay(it) },
            onDismiss = { longPressOpen = false },
            resetLabel = stringResource(R.string.options_follow_system).takeIf { state.longPressDelayMs != null },
            onReset = { onLongPressDelay(null) },
        )
    }
    Column {
        SectionHeader(stringResource(R.string.options_key_height_section))
        for (height in SettingsPreferences.KEY_HEIGHTS) {
            RadioRow(
                title =
                    stringResource(
                        when (height) {
                            "compact" -> R.string.options_key_height_compact
                            "tall" -> R.string.options_key_height_tall
                            else -> R.string.options_key_height_standard
                        },
                    ),
                selected = state.keyHeight == height,
                onClick = { onKeyHeight(height) },
            )
        }

        SettingsRow(
            title = stringResource(R.string.options_bottom_gap_title),
            subtitle =
                if (state.bottomGapDp == 0) {
                    stringResource(R.string.options_bottom_gap_none)
                } else {
                    stringResource(R.string.options_bottom_gap_value, state.bottomGapDp)
                },
            onClick = { bottomGapOpen = true },
        )

        SectionHeader(stringResource(R.string.options_one_hand_section))
        for (mode in SettingsPreferences.ONE_HAND_MODES) {
            RadioRow(
                title =
                    stringResource(
                        when (mode) {
                            "left" -> R.string.options_one_hand_left
                            "right" -> R.string.options_one_hand_right
                            else -> R.string.options_one_hand_off
                        },
                    ),
                selected = state.oneHand == mode,
                onClick = { onOneHand(mode) },
            )
        }

        SectionHeader(stringResource(R.string.options_wide_position_section))
        Text(
            text = stringResource(R.string.options_wide_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        for (position in SettingsPreferences.WIDE_POSITIONS) {
            RadioRow(
                title =
                    stringResource(
                        when (position) {
                            "left" -> R.string.options_wide_position_left
                            "right" -> R.string.options_wide_position_right
                            "custom" -> R.string.options_wide_position_custom
                            else -> R.string.options_wide_position_center
                        },
                    ),
                subtitle =
                    if (position == "custom") {
                        stringResource(R.string.options_wide_position_value, state.widePositionCustom)
                    } else {
                        null
                    },
                selected = state.widePosition == position,
                onClick = { onWidePosition(position) },
            )
        }
        if (state.widePosition == "custom") {
            WidePositionCustomControls(percent = state.widePositionCustom, onChange = onWidePositionCustom)
        }

        SectionHeader(stringResource(R.string.options_split_section))
        for (mode in SettingsPreferences.SPLIT_MODES) {
            RadioRow(
                title =
                    stringResource(
                        when (mode) {
                            "on" -> R.string.options_split_on
                            "off" -> R.string.options_split_off
                            else -> R.string.options_split_layout
                        },
                    ),
                subtitle =
                    when (mode) {
                        "on" -> stringResource(R.string.options_split_on_body)
                        "layout" -> stringResource(R.string.options_split_layout_body)
                        else -> null
                    },
                selected = state.splitMode == mode,
                onClick = { onSplitMode(mode) },
            )
        }

        SectionHeader(stringResource(R.string.options_key_feel_section))
        SettingsRow(
            title = stringResource(R.string.options_long_press_title),
            subtitle =
                state.longPressDelayMs?.let { stringResource(R.string.options_long_press_value, it) }
                    ?: stringResource(R.string.options_follow_system),
            onClick = { longPressOpen = true },
        )
        SwitchRow(
            title = stringResource(R.string.options_key_flick_title),
            subtitle = stringResource(R.string.options_key_flick_body),
            checked = state.keyFlick,
            onCheckedChange = onKeyFlick,
        )
        SwitchRow(
            title = stringResource(R.string.options_double_space_title),
            subtitle = stringResource(R.string.options_double_space_body),
            checked = state.doubleSpacePeriod,
            onCheckedChange = onDoubleSpacePeriod,
        )
        SwitchRow(
            title = stringResource(R.string.options_key_preview_title),
            subtitle = stringResource(R.string.options_key_preview_body),
            checked = state.keyPreview,
            onCheckedChange = onKeyPreview,
        )
        SwitchRow(
            title = stringResource(R.string.options_release_haptic_title),
            subtitle = stringResource(R.string.options_release_haptic_body),
            checked = state.releaseHaptic,
            onCheckedChange = onReleaseHaptic,
        )
    }
}

/** Where the long-press slider starts while the delay still follows the system; near the usual system value. */
private const val DEFAULT_LONG_PRESS_START_MS = 400

/**
 * A slider in a dialog for one stepped number. The value is applied on confirm, so a half-dragged
 * choice never reaches the keyboard; [resetLabel] adds a button that returns to the system value.
 */
@Composable
private fun ValueSliderDialog(
    title: String,
    range: IntRange,
    step: Int,
    initial: Int,
    label: @Composable (Int) -> String,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
    resetLabel: String?,
    onReset: () -> Unit,
) {
    var value by rememberSaveable { mutableIntStateOf(initial.coerceIn(range)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(label(value), style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = value.toFloat(),
                    onValueChange = {
                        value =
                            (Math.round((it - range.first) / step) * step + range.first).coerceIn(range)
                    },
                    valueRange = range.first.toFloat()..range.last.toFloat(),
                    steps = (range.last - range.first) / step - 1,
                )
                if (resetLabel != null) {
                    TextButton(onClick = {
                        onReset()
                        onDismiss()
                    }) { Text(resetLabel) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(value)
                onDismiss()
            }) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * The custom large-screen position under the "custom" choice: a slider with a live readout and a
 * number field. The keyboard follows when a drag ends or a valid number is typed, so a half-dragged
 * value is never stored. The number stays stored while left / center / right is in use.
 */
@Composable
internal fun WidePositionCustomControls(
    percent: Int,
    onChange: (Int) -> Unit,
) {
    var dragging by remember { mutableStateOf<Int?>(null) }
    val shown = dragging ?: percent
    var text by remember(percent) { mutableStateOf(percent.toString()) }
    val parsed = parseWidePosition(text)
    val description =
        stringResource(R.string.options_wide_position_custom) + " " +
            stringResource(R.string.options_wide_position_value, shown)
    Column(modifier = Modifier.padding(start = 56.dp, end = 16.dp)) {
        Text(
            text = stringResource(R.string.options_wide_position_value, shown),
            style = MaterialTheme.typography.titleMedium,
        )
        Slider(
            value = shown.toFloat(),
            onValueChange = { dragging = Math.round(it) },
            onValueChangeFinished = {
                dragging?.let(onChange)
                dragging = null
            },
            valueRange =
                SettingsPreferences.WIDE_POSITION_MIN.toFloat()..SettingsPreferences.WIDE_POSITION_MAX.toFloat(),
            modifier = Modifier.semantics { contentDescription = description },
        )
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it.take(3)
                parseWidePosition(text)?.let(onChange)
            },
            label = { Text(stringResource(R.string.options_wide_position_field)) },
            suffix = { Text("%") },
            isError = parsed == null,
            supportingText = {
                if (parsed == null) Text(stringResource(R.string.options_wide_position_invalid))
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
}

/** The number typed into the position field, or null when it is not a whole number from 0 to 100. */
internal fun parseWidePosition(text: String): Int? =
    text
        .trim()
        .toIntOrNull()
        ?.takeIf { it in SettingsPreferences.WIDE_POSITION_MIN..SettingsPreferences.WIDE_POSITION_MAX }
