// SPDX-License-Identifier: GPL-3.0-only
package app.songyan.ime.settings.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.songyan.ime.settings.R
import app.songyan.ime.settings.state.AddonCallInfo
import app.songyan.ime.settings.state.AddonCallKind
import app.songyan.ime.settings.state.AddonCompatibility
import app.songyan.ime.settings.state.AddonInfo
import app.songyan.ime.settings.ui.ExtensionsViewModel
import app.songyan.ime.settings.ui.components.SectionHeader
import app.songyan.ime.settings.ui.components.SettingsScaffold

/**
 * 设置 → 扩展: the addons installed on this device. Songyan does not offer a store: the user
 * installs an addon APK themselves, then turns it on here. The page shows who the APK is (package,
 * version, signing certificate), what it may use (its declared permissions), whether this build can
 * speak to it, and what it last did for the keyboard.
 */
@Composable
fun ExtensionsScreen(
    onBack: (() -> Unit)?,
    model: ExtensionsViewModel = viewModel(factory = ExtensionsViewModel.Factory()),
) {
    val state by model.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
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
    SettingsScaffold(
        title = stringResource(R.string.extensions_title),
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbar) },
        actions = {
            IconButton(
                onClick = model::rescan,
                enabled = !state.refreshing,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = stringResource(R.string.extensions_rescan),
                )
            }
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.extensions_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            when {
                !state.available ->
                    Text(
                        text = stringResource(R.string.extensions_not_available),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                state.loading ->
                    Text(
                        text = stringResource(R.string.extensions_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                state.extensions.isEmpty() -> {
                    SectionHeader(stringResource(R.string.extensions_empty_title))
                    Text(
                        text = stringResource(R.string.extensions_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                else -> {
                    if (state.refreshing) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    }
                    for (extension in state.extensions) {
                        ExtensionCard(
                            addon = extension,
                            onEnabledChange = { enabled -> model.setEnabled(extension.packageName, enabled) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtensionCard(
    addon: AddonInfo,
    onEnabledChange: (Boolean) -> Unit,
) {
    val label = addon.label.ifBlank { addon.packageName }
    val switchLabel = switchDescription(label)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = addon.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        Text(
            text =
                when (addon.compatibility) {
                    AddonCompatibility.COMPATIBLE -> stringResource(R.string.extensions_compatible)
                    AddonCompatibility.INCOMPATIBLE -> stringResource(R.string.extensions_incompatible)
                    AddonCompatibility.UNKNOWN -> stringResource(R.string.extensions_unknown)
                },
            style = MaterialTheme.typography.bodySmall,
            color =
                if (addon.compatibility == AddonCompatibility.INCOMPATIBLE) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.padding(top = 4.dp),
        )
        addon.incompatibleReason?.takeIf { it.isNotBlank() }?.let { reason ->
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.extensions_version, addon.versionName.ifBlank { "-" }),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        addon.signerSha256?.let { signer ->
            Text(
                text = stringResource(R.string.extensions_signer, shortSigner(signer)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text =
                if (addon.permissions.isEmpty()) {
                    stringResource(R.string.extensions_no_permissions)
                } else {
                    stringResource(R.string.extensions_permissions, addon.permissions.joinToString(", "))
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (addon.tools.isNotEmpty()) {
            Text(
                text =
                    stringResource(
                        R.string.extensions_tools,
                        addon.tools.joinToString(", ") { it.title.ifBlank { it.id } },
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (addon.supportsPanel) {
            Text(
                text = stringResource(R.string.extensions_panel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (addon.supportsVoice) {
            Text(
                text = stringResource(R.string.extensions_voice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        addon.lastCall?.let { call ->
            Text(
                text = lastCallText(call),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        HorizontalDivider(modifier = Modifier.padding(top = 10.dp))
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
    return if (call.error.isNullOrBlank()) {
        stringResource(R.string.extensions_last_call, kind, call.durationMs)
    } else {
        stringResource(R.string.extensions_last_call_error, kind, call.durationMs, call.error)
    }
}

/** The first 16 hex characters are enough to compare with a published fingerprint by eye. */
private fun shortSigner(signer: String): String = if (signer.length <= 16) signer else signer.take(16) + "…"
