// SPDX-License-Identifier: Apache-2.0
package app.songyan.ime.addon.sample.debug

import app.songyan.ime.addon.AddonCallerPolicy
import app.songyan.ime.addon.AddonCapability
import app.songyan.ime.addon.AddonHandshake
import app.songyan.ime.addon.AddonHost
import app.songyan.ime.addon.AddonPanelContext
import app.songyan.ime.addon.AddonPanelHost
import app.songyan.ime.addon.AddonProtocol
import app.songyan.ime.addon.AddonToolDescriptor
import app.songyan.ime.addon.SongyanAddon
import app.songyan.ime.addon.SongyanAddonService
import app.songyan.ime.addon.SongyanAddonSession

/**
 * Debug-only addon whose panel never hands its surface back: the host must close the panel by its
 * own timeout instead of leaving a blank keyboard window. The phase-2 host integration test binds
 * this service explicitly; a real addon never behaves like this.
 */
public class StalledPanelAddonService : SongyanAddonService() {
    override val callerPolicy: AddonCallerPolicy =
        AddonCallerPolicy(
            acceptedHostPackages = AddonCallerPolicy.DEFAULT_HOST_PACKAGES,
            acceptedHostSignerSha256 = emptySet(),
            allowDebugCallers = true,
        )

    override fun createAddon(): SongyanAddon = StalledPanelAddon()
}

private class StalledPanelAddon : SongyanAddon {
    override val addonId: String = "app.songyan.ime.addon.sample.stalled"
    override val addonName: String = "Stalled panel"
    override val supportedProtocolVersions: IntRange = AddonProtocol.MIN_VERSION..AddonProtocol.MAX_VERSION

    override fun handshake(host: AddonHost): AddonHandshake =
        AddonHandshake(
            protocolVersion = host.protocolVersion,
            addonId = addonId,
            addonName = addonName,
            capabilities = setOf(AddonCapability.PANEL),
            tools = emptyList(),
            supportsPanel = true,
            supportsVoice = false,
        )

    override fun openSession(host: AddonHost): SongyanAddonSession = StalledPanelSession()

    override fun onDisconnect(): Unit = Unit
}

private class StalledPanelSession : SongyanAddonSession {
    override fun tools(): List<AddonToolDescriptor> = emptyList()

    override fun runTextTool(
        requestId: String,
        tool: AddonToolDescriptor,
        text: String,
    ): Unit = Unit

    override fun cancelRun(requestId: String): Unit = Unit

    override fun startVoice(requestId: String): Unit = Unit

    override fun stopVoice(): Unit = Unit

    override fun openPanel(
        requestId: String,
        context: AddonPanelContext,
        panelHost: AddonPanelHost,
    ) {
        // The bug under test: connected, accepted, and then the surface never arrives.
    }

    override fun updatePanelContext(context: AddonPanelContext): Unit = Unit

    override fun closePanel(): Unit = Unit

    override fun close(): Unit = Unit
}
