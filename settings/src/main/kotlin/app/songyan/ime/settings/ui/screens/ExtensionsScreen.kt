// SPDX-License-Identifier: GPL-3.0-only
package app.songyan.ime.settings.ui.screens

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.songyan.ime.settings.R
import app.songyan.ime.settings.state.AddonCallInfo
import app.songyan.ime.settings.state.AddonCallKind
import app.songyan.ime.settings.state.AddonCompatibility
import app.songyan.ime.settings.state.AddonInfo
import app.songyan.ime.settings.ui.ExtensionsUiModel
import app.songyan.ime.settings.ui.ExtensionsViewModel
import app.songyan.ime.settings.ui.components.LoadingScreen
import app.songyan.ime.settings.ui.components.SettingsScaffold
import app.songyan.ime.settings.ui.components.StateMessage
import kotlinx.coroutines.launch

/**
 * Settings → Extensions: the addon APKs installed on this device. Songyan has no store; the user installs
 * an addon themselves and turns it on here. Each addon is one card: identity and switch in the main
 * row, a badge only when something is wrong, capability/tool chips, permissions only when it has
 * any, an expandable package/version/signature block and the last call as secondary information. The extension's own settings
 * page is a plain button that only launches a component the handshake declared.
 */
@Composable
fun ExtensionsScreen(
    onBack: (() -> Unit)?,
    model: ExtensionsViewModel = viewModel(factory = ExtensionsViewModel.Factory()),
    /** Test seam: when null the screen launches the declared component itself. */
    openSettings: ((String) -> Unit)? = null,
) {
    val state by model.uiState.collectAsStateWithLifecycle()
    val title = stringResource(R.string.extensions_title)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settingsUnavailable = stringResource(R.string.extensions_settings_unavailable)
    val scanFailedText = stringResource(R.string.extensions_scan_failed)
    LaunchedEffect(state.scanFailed) {
        if (state.scanFailed) {
            snackbar.showSnackbar(scanFailedText)
            model.consumeScanFailed()
        }
    }
    val actionFailedText = stringResource(R.string.extensions_toggle_failed)
    LaunchedEffect(state.actionFailed) {
        if (state.actionFailed) {
            snackbar.showSnackbar(actionFailedText)
            model.consumeActionFailed()
        }
    }
    val openAddonSettings: (String) -> Unit = { component ->
        if (openSettings != null) {
            openSettings.invoke(component)
        } else {
            val name = ComponentName.unflattenFromString(component)
            if (name == null) {
                scope.launch { snackbar.showSnackbar(settingsUnavailable) }
            } else {
                try {
                    context.startActivity(Intent().setComponent(name))
                } catch (_: ActivityNotFoundException) {
                    scope.launch { snackbar.showSnackbar(settingsUnavailable) }
                } catch (_: SecurityException) {
                    scope.launch { snackbar.showSnackbar(settingsUnavailable) }
                }
            }
        }
    }
    // Extensions are installed and removed outside Songyan; read the list again on every return
    // instead of asking the user to refresh. The first resume is the scan the model already runs.
    var resumedBefore by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (resumedBefore) model.rescan(showProgress = false)
        resumedBefore = true
    }
    if (state.loading) {
        LoadingScreen(title = title, onBack = onBack)
        return
    }
    SettingsScaffold(
        title = title,
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.extensions_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (state.refreshing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            when {
                !state.available ->
                    StateMessage(
                        title = stringResource(R.string.extensions_not_available),
                        icon = Icons.Outlined.Extension,
                    )
                state.extensions.isEmpty() ->
                    StateMessage(
                        title = stringResource(R.string.extensions_empty_title),
                        body = stringResource(R.string.extensions_empty_body),
                        icon = Icons.Outlined.Extension,
                        actionLabel = stringResource(R.string.extensions_rescan),
                        onAction = { model.rescan() },
                    )
                else ->
                    for (extension in state.extensions) {
                        ExtensionCard(
                            addon = extension,
                            onEnabledChange = { enabled -> model.setEnabled(extension.packageName, enabled) },
                            onOpenSettings = openAddonSettings,
                        )
                    }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExtensionCard(
    addon: AddonInfo,
    onEnabledChange: (Boolean) -> Unit,
    onOpenSettings: (String) -> Unit,
) {
    val label = addon.label.ifBlank { addon.packageName }
    val switchLabel = switchDescription(label)
    val context = LocalContext.current
    val releaseHost =
        remember(addon.packageName) {
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0
        }
    val visibleTools =
        remember(addon.tools, releaseHost) {
            ExtensionsUiModel.visibleTools(addon.tools, releaseHost)
        }
    var expanded by rememberSaveable(addon.packageName) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_500)
            copied = false
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Extension,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(
                    checked = addon.enabled,
                    onCheckedChange = onEnabledChange,
                    enabled = addon.compatibility == AddonCompatibility.COMPATIBLE,
                    modifier =
                        Modifier.semantics {
                            contentDescription = switchLabel
                        },
                )
            }
            if (addon.compatibility != AddonCompatibility.COMPATIBLE) {
                Spacer(modifier = Modifier.height(10.dp))
                CompatibilityBadge(addon)
            }
            addon.incompatibleReason?.takeIf { it.isNotBlank() }?.let { reason ->
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (visibleTools.isNotEmpty() || addon.supportsPanel || addon.supportsVoice) {
                Spacer(modifier = Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (tool in visibleTools) {
                        CapabilityChip(
                            icon = Icons.Outlined.TextFields,
                            text = tool.title.ifBlank { tool.id },
                        )
                    }
                    if (addon.supportsPanel) {
                        CapabilityChip(
                            icon = Icons.Outlined.Fullscreen,
                            text = stringResource(R.string.extensions_panel),
                        )
                    }
                    if (addon.supportsVoice) {
                        CapabilityChip(
                            icon = Icons.Outlined.Mic,
                            text = stringResource(R.string.extensions_voice),
                        )
                    }
                }
            }
            if (addon.permissions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.extensions_permissions_section),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    for (permission in addon.permissions) {
                        CapabilityChip(icon = null, text = permission)
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.semantics { contentDescription = addon.packageName + "_details" },
            ) {
                Text(stringResource(R.string.extensions_details))
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                )
            }
            if (expanded) {
                IdentityRow(value = addon.packageName)
                IdentityRow(
                    value = stringResource(R.string.extensions_version, addon.versionName.ifBlank { "-" }),
                )
                addon.signerSha256?.let { signer ->
                    IdentityRow(
                        value = stringResource(R.string.extensions_signer, shortSigner(signer)),
                        copyDescription = stringResource(R.string.extensions_copy_signer),
                        onCopy = {
                            clipboard.setText(AnnotatedString(signer))
                            copied = true
                        },
                    )
                }
                if (copied) {
                    Text(
                        text = stringResource(R.string.extensions_copied),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            addon.lastCall?.let { call ->
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        // The inline extensions_last_call* text already starts with "Last use:";
                        // the section key labels the history icon for screen readers so it stays
                        // in use instead of becoming a dead string.
                        contentDescription = stringResource(R.string.extensions_last_call_section),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        // extensions_last_call* already start with "Last use:"; a second label
                        // before them read like a phone call ("Last call · Last use: ...").
                        text = lastCallText(call),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            addon.settingsComponent?.takeIf { it.isNotBlank() }?.let { component ->
                TextButton(onClick = { onOpenSettings(component) }) {
                    Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.extensions_open_settings))
                }
            }
        }
    }
}

@Composable
private fun CompatibilityBadge(addon: AddonInfo) {
    val (icon, text, container, content) =
        when (addon.compatibility) {
            AddonCompatibility.COMPATIBLE, AddonCompatibility.INCOMPATIBLE ->
                BadgeSpec(
                    Icons.Outlined.ErrorOutline,
                    R.string.extensions_incompatible,
                    MaterialTheme.colorScheme.errorContainer,
                    MaterialTheme.colorScheme.onErrorContainer,
                )
            AddonCompatibility.UNKNOWN ->
                BadgeSpec(
                    Icons.AutoMirrored.Outlined.HelpOutline,
                    R.string.extensions_unknown,
                    MaterialTheme.colorScheme.surfaceContainerHighest,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
    Surface(shape = RoundedCornerShape(50), color = container) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
            Text(text = stringResource(text), style = MaterialTheme.typography.labelMedium, color = content)
        }
    }
}

private data class BadgeSpec(
    val icon: ImageVector,
    val text: Int,
    val container: Color,
    val content: Color,
)

@Composable
private fun CapabilityChip(
    icon: ImageVector?,
    text: String,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(text = text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun IdentityRow(
    value: String,
    copyDescription: String? = null,
    onCopy: (() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (copyDescription != null && onCopy != null) {
            IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = copyDescription,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun switchDescription(label: String): String = stringResource(R.string.extensions_switch, label)

@Composable
private fun lastCallText(call: AddonCallInfo): String {
    val kind =
        stringResource(
            when (call.kind) {
                AddonCallKind.TEXT -> R.string.extensions_call_kind_text
                AddonCallKind.VOICE -> R.string.extensions_call_kind_voice
                AddonCallKind.PANEL -> R.string.extensions_call_kind_panel
            },
        )
    val locale = LocalConfiguration.current.locales[0]
    val seconds = String.format(locale, "%.1f", call.durationMs / 1000.0)
    return if (call.error.isNullOrBlank()) {
        stringResource(R.string.extensions_last_call, kind, seconds)
    } else {
        stringResource(R.string.extensions_last_call_error, kind, seconds, call.error)
    }
}

/** The first 16 hex characters are enough to compare with a published fingerprint by eye. */
private fun shortSigner(signer: String): String = if (signer.length <= 16) signer else signer.take(16) + "…"
