// SPDX-License-Identifier: GPL-3.0-only
package app.songyan.ime.addons

import app.songyan.ime.settings.state.AddonCallInfo
import app.songyan.ime.settings.state.AddonCallKind
import app.songyan.ime.settings.state.AddonCompatibility
import app.songyan.ime.settings.state.AddonInfo
import app.songyan.ime.settings.state.AddonSettingsGateway
import app.songyan.ime.settings.state.AddonToolInfo

/**
 * Brings the extension registry to the Extensions settings page. Listing negotiates with every installed
 * extension (the page must say whether it is compatible); enabling only flips the stored switch.
 */
internal class AddonSettingsGatewayImpl(
    private val registry: AddonRegistry,
) : AddonSettingsGateway {
    override val available: Boolean = true

    override suspend fun list(): List<AddonInfo> = registry.scan(negotiate = true).map { it.toUi() }

    override suspend fun rescan(): List<AddonInfo> = list()

    override suspend fun setEnabled(
        packageName: String,
        enabled: Boolean,
    ): Boolean = registry.setEnabled(packageName, enabled)

    private fun AddonEntry.toUi(): AddonInfo =
        AddonInfo(
            packageName = facts.packageName,
            label = facts.label,
            versionName = facts.versionName,
            signerSha256 = facts.signerSha256,
            permissions = facts.permissionLabels,
            enabled = enabled,
            compatibility =
                when {
                    handshake == null -> AddonCompatibility.UNKNOWN
                    handshake.isCompatible -> AddonCompatibility.COMPATIBLE
                    else -> AddonCompatibility.INCOMPATIBLE
                },
            incompatibleReason = handshake?.incompatibleReason ?: negotiationFailure,
            tools = tools.map { AddonToolInfo(it.id, it.title) },
            supportsPanel = handshake?.supportsPanel == true,
            supportsVoice = handshake?.supportsVoice == true,
            settingsComponent = handshake?.settingsComponent,
            lastCall =
                lastCall?.let {
                    AddonCallInfo(
                        kind =
                            when (it.kind) {
                                AddonCallKinds.VOICE -> AddonCallKind.VOICE
                                AddonCallKinds.PANEL -> AddonCallKind.PANEL
                                else -> AddonCallKind.TEXT
                            },
                        atEpochMs = it.atEpochMs,
                        durationMs = it.durationMs,
                        error = it.error,
                    )
                },
        )
}
