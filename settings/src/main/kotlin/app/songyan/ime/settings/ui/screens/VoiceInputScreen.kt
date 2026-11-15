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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.songyan.ime.settings.R
import app.songyan.ime.settings.ui.VoiceInputUiState
import app.songyan.ime.settings.ui.VoiceInputViewModel
import app.songyan.ime.settings.ui.VoiceScriptOption
import app.songyan.ime.settings.ui.VoiceSourceOrigin
import app.songyan.ime.settings.ui.components.DetailRow
import app.songyan.ime.settings.ui.components.LoadingScreen
import app.songyan.ime.settings.ui.components.RadioRow
import app.songyan.ime.settings.ui.components.SectionHeader
import app.songyan.ime.settings.ui.components.SettingsScaffold
import app.songyan.ime.settings.ui.components.StateMessage
import app.songyan.ime.settings.ui.components.SwitchRow

/**
 * Voice input: the keyboard's own voice input. The switch shows the microphone in the toolbar; the list
 * picks the installed script (or extension) that turns the recording into text. Only sources that
 * declare audio input and can run on this device are listed, so a script that asks for text only
 * never appears.
 *
 * The page states plainly what is wrong and what to do about it: a missing microphone permission
 * is asked for with the system dialog, and only once Android stops showing that dialog does the
 * button open the app's system settings instead, an empty catalog points at the Scripts page, and a catalog
 * that cannot be read offers a retry instead of an empty list.
 */
@Composable
fun VoiceInputScreen(
    onBack: (() -> Unit)?,
    onOpenScripts: () -> Unit,
    model: VoiceInputViewModel = viewModel(factory = VoiceInputViewModel.Factory()),
) {
    val state by model.uiState.collectAsStateWithLifecycle()
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

    VoiceInputPage(
        state = state,
        microphoneGranted = microphoneGranted,
        onEnabledChange = model::setEnabled,
        onSelect = model::selectScript,
        onOpenScripts = onOpenScripts,
        permissionDialogBlocked = dialogBlocked,
        onRequestPermission = { request.launch(Manifest.permission.RECORD_AUDIO) },
        onOpenSystemSettings = { openMicrophoneSettings(context) },
        onRetry = model::refresh,
        onBack = onBack,
    )
}

/** The page itself, without Android lookups: everything the tests and the screen both render. */
@Composable
internal fun VoiceInputPage(
    state: VoiceInputUiState,
    microphoneGranted: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    onOpenScripts: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onRetry: () -> Unit,
    onBack: (() -> Unit)?,
    permissionDialogBlocked: Boolean,
    onRequestPermission: () -> Unit,
) {
    if (state.loading) {
        LoadingScreen(stringResource(R.string.voice_title), onBack)
        return
    }
    // The switch is only live when voice input could actually run: a granted microphone and at
    // least one runnable source. A switch already on stays tappable, so it can still be turned off.
    val canStartVoice = microphoneGranted && state.scripts.isNotEmpty() && !state.failed
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
                subtitle =
                    stringResource(
                        when {
                            !microphoneGranted -> R.string.voice_switch_needs_permission
                            state.scripts.isEmpty() && !state.failed -> R.string.voice_switch_needs_script
                            else -> R.string.voice_switch_subtitle
                        },
                    ),
                checked = state.enabled,
                onCheckedChange = onEnabledChange,
                enabled = state.enabled || canStartVoice,
            )
            SectionHeader(stringResource(R.string.voice_permission_section))
            DetailRow(
                title = stringResource(R.string.voice_permission_title),
                body =
                    stringResource(
                        if (microphoneGranted) R.string.voice_permission_granted else R.string.voice_permission_denied,
                    ),
            )
            if (!microphoneGranted) {
                if (permissionDialogBlocked) {
                    PermissionAction(R.string.voice_permission_open_settings, onOpenSystemSettings)
                } else {
                    PermissionAction(R.string.voice_permission_request, onRequestPermission)
                }
            }
            SectionHeader(stringResource(R.string.voice_scripts_section))
            when {
                state.failed -> {
                    StateMessage(
                        title = stringResource(R.string.voice_load_failed_title),
                        body = stringResource(R.string.voice_load_failed_body),
                        actionLabel = stringResource(R.string.voice_retry),
                        onAction = onRetry,
                    )
                }
                state.scripts.isEmpty() -> {
                    StateMessage(
                        title = stringResource(R.string.voice_empty_scripts_title),
                        body = stringResource(R.string.voice_empty_scripts_body),
                        icon = Icons.Outlined.Mic,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Button(onClick = onOpenScripts) { Text(stringResource(R.string.voice_open_scripts)) }
                    }
                }
                else -> {
                    for (script in state.scripts) {
                        VoiceSourceRow(
                            script = script,
                            selected = script.id == state.selectedScriptId,
                            enabled = state.enabled,
                            onSelect = onSelect,
                        )
                    }
                }
            }
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
