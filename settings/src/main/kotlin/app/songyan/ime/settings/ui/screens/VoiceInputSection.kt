package app.songyan.ime.settings.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.songyan.ime.settings.R
import app.songyan.ime.settings.ui.VoiceInputUiState
import app.songyan.ime.settings.ui.VoiceScriptOption
import app.songyan.ime.settings.ui.VoiceSourceOrigin
import app.songyan.ime.settings.ui.components.DetailRow
import app.songyan.ime.settings.ui.components.RadioRow
import app.songyan.ime.settings.ui.components.SectionHeader
import app.songyan.ime.settings.ui.components.StateMessage
import app.songyan.ime.settings.ui.components.SwitchRow

/** What the section needs from Android: the microphone permission and the two ways to fix it. */
internal class VoicePermission(
    val granted: Boolean,
    val dialogBlocked: Boolean,
    val request: () -> Unit,
    val openSystemSettings: () -> Unit,
)

/** Reads the microphone permission, re-reading it on every return from the system page. */
@Composable
internal fun rememberVoicePermission(): VoicePermission {
    val context = LocalContext.current
    var microphoneGranted by remember { mutableStateOf(microphoneGranted(context)) }
    // The user may grant it in the system page and come back: read it again on every return.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { microphoneGranted = microphoneGranted(context) }
    // Android stops showing the dialog after the user denies it twice; the request then returns
    // false at once with no rationale, and only the app's settings page can grant it.
    var dialogBlocked by rememberSaveable { mutableStateOf(false) }
    val request =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            microphoneGranted = granted
            if (!granted) {
                val activity = context.findActivity()
                dialogBlocked = activity == null ||
                    !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
            }
        }
    return VoicePermission(
        granted = microphoneGranted,
        dialogBlocked = dialogBlocked,
        request = { request.launch(Manifest.permission.RECORD_AUDIO) },
        openSystemSettings = { openMicrophoneSettings(context) },
    )
}

/**
 * Voice input lives with the scripts and extensions that provide it, so the Scripts page shows this
 * section only when it has something to say: a source that can record, voice input already on (so it
 * can always be turned off), or a catalog that could not be read while it is on.
 */
internal fun VoiceInputUiState.showsSection(): Boolean = !loading && (scripts.isNotEmpty() || enabled)

/**
 * The section's rows: the switch for the toolbar microphone, the microphone permission, and the
 * list of sources (audio scripts and extensions). A missing permission is asked for with the
 * system dialog, and only once Android stops showing that dialog does the button open the app's
 * system settings instead; a catalog that cannot be read offers a retry instead of an empty list.
 */
internal fun LazyListScope.voiceInputSection(
    state: VoiceInputUiState,
    permission: VoicePermission,
    onEnabledChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    onRetry: () -> Unit,
) {
    // The switch is only live when voice input could actually run: a granted microphone and at
    // least one runnable source. A switch already on stays tappable, so it can still be turned off.
    val canStartVoice = permission.granted && state.scripts.isNotEmpty() && !state.failed
    item(key = "voice-header", contentType = "voice-header") {
        SectionHeader(stringResource(R.string.voice_title))
    }
    item(key = "voice-switch", contentType = "voice-switch") {
        SwitchRow(
            title = stringResource(R.string.voice_switch_title),
            subtitle =
                stringResource(
                    when {
                        !permission.granted -> R.string.voice_switch_needs_permission
                        state.scripts.isEmpty() && !state.failed -> R.string.voice_switch_needs_script
                        else -> R.string.voice_switch_subtitle
                    },
                ),
            checked = state.enabled,
            onCheckedChange = onEnabledChange,
            enabled = state.enabled || canStartVoice,
        )
    }
    item(key = "voice-permission", contentType = "voice-permission") {
        Column {
            DetailRow(
                title = stringResource(R.string.voice_permission_title),
                body =
                    stringResource(
                        if (permission.granted) R.string.voice_permission_granted else R.string.voice_permission_denied,
                    ),
            )
            if (!permission.granted) {
                if (permission.dialogBlocked) {
                    PermissionAction(R.string.voice_permission_open_settings, permission.openSystemSettings)
                } else {
                    PermissionAction(R.string.voice_permission_request, permission.request)
                }
            }
        }
    }
    if (state.failed) {
        item(key = "voice-failed", contentType = "voice-failed") {
            StateMessage(
                title = stringResource(R.string.voice_load_failed_title),
                body = stringResource(R.string.voice_load_failed_body),
                actionLabel = stringResource(R.string.voice_retry),
                onAction = onRetry,
            )
        }
    } else if (state.scripts.isNotEmpty()) {
        item(key = "voice-sources-header", contentType = "voice-header") {
            SectionHeader(stringResource(R.string.voice_scripts_section))
        }
        items(state.scripts, key = { "voice-source-" + it.id }, contentType = { "voice-source" }) { script ->
            VoiceSourceRow(
                script = script,
                selected = script.id == state.selectedScriptId,
                enabled = state.enabled,
                onSelect = onSelect,
            )
        }
    }
}

@Composable
private fun PermissionAction(
    label: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Button(onClick = onClick) {
            Text(stringResource(label))
        }
    }
}

@Composable
private fun VoiceSourceRow(
    script: VoiceScriptOption,
    selected: Boolean,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    val origin =
        when (val source = script.origin) {
            VoiceSourceOrigin.Pasted -> stringResource(R.string.scripts_origin_pasted)
            is VoiceSourceOrigin.File -> stringResource(R.string.scripts_origin_file, source.displayName)
            VoiceSourceOrigin.Link -> stringResource(R.string.scripts_origin_link)
            VoiceSourceOrigin.Edited -> stringResource(R.string.scripts_origin_edited)
            VoiceSourceOrigin.Addon -> stringResource(R.string.voice_addon_origin)
        }
    val status =
        stringResource(
            if (script.enabled) R.string.voice_script_enabled else R.string.voice_script_disabled,
        )
    val selectionState =
        stringResource(
            if (selected) R.string.voice_script_selected else R.string.voice_script_not_selected,
        )
    RadioRow(
        title =
            if (script.isAddon) {
                stringResource(R.string.voice_addon_option, script.name)
            } else {
                script.name
            },
        subtitle = stringResource(R.string.voice_script_summary, origin, status),
        selected = selected,
        enabled = enabled,
        onClick = { onSelect(script.id) },
        modifier = Modifier.semantics { stateDescription = selectionState },
    )
}

/** RECORD_AUDIO as the page shows it; the keyboard itself asks through its transparent activity. */
private fun microphoneGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

/** App details, where the microphone permission lives; there is no per-permission system page. */
private fun openMicrophoneSettings(context: Context) {
    val intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
