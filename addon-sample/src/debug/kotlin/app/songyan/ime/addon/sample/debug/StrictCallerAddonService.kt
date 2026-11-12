// SPDX-License-Identifier: Apache-2.0
package app.songyan.ime.addon.sample.debug

import app.songyan.ime.addon.AddonCallerPolicy
import app.songyan.ime.addon.AddonHandshake
import app.songyan.ime.addon.AddonHost
import app.songyan.ime.addon.SongyanAddon
import app.songyan.ime.addon.SongyanAddonService
import app.songyan.ime.addon.SongyanAddonSession

/**
 * Debug-only fixture: the release caller policy with no debug escape hatch. The integration test
 * binds from its own package and must be refused, which is the "the service only accepts Songyan"
 * path an installed addon ships with.
 */
public class StrictCallerAddonService : SongyanAddonService() {
    override val callerPolicy: AddonCallerPolicy =
        AddonCallerPolicy(
            acceptedHostPackages = AddonCallerPolicy.DEFAULT_HOST_PACKAGES,
            acceptedHostSignerSha256 = setOf("00".repeat(32)),
            allowDebugCallers = false,
        )

    override fun createAddon(): SongyanAddon = StrictCallerAddon()
}

private class StrictCallerAddon : SongyanAddon {
    override val addonId: String = "app.songyan.ime.addon.sample.strict"

    override val addonName: String = "Strict caller sample"

    override val supportedProtocolVersions: IntRange = 1..1

    override fun handshake(host: AddonHost): AddonHandshake =
        AddonHandshake(protocolVersion = host.protocolVersion, addonId = addonId, addonName = addonName)

    override fun openSession(host: AddonHost): SongyanAddonSession =
        error("an untrusted caller must never open a session")

    override fun onDisconnect(): Unit = Unit
}
