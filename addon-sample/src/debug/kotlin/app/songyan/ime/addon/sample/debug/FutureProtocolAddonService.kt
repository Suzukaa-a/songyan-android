// SPDX-License-Identifier: Apache-2.0
package app.songyan.ime.addon.sample.debug

import app.songyan.ime.addon.AddonCallerPolicy
import app.songyan.ime.addon.AddonHandshake
import app.songyan.ime.addon.AddonHost
import app.songyan.ime.addon.SongyanAddon
import app.songyan.ime.addon.SongyanAddonService
import app.songyan.ime.addon.SongyanAddonSession

/**
 * Debug-only fixture: an addon that speaks only a future protocol version, so the integration
 * test can prove that a version mismatch is reported as incompatible and nothing runs.
 */
public class FutureProtocolAddonService : SongyanAddonService() {
    override val callerPolicy: AddonCallerPolicy =
        AddonCallerPolicy(allowDebugCallers = true)

    override fun createAddon(): SongyanAddon = FutureProtocolAddon()
}

private class FutureProtocolAddon : SongyanAddon {
    override val addonId: String = "app.songyan.ime.addon.sample.future"

    override val addonName: String = "Future protocol sample"

    override val supportedProtocolVersions: IntRange = 99..99

    override fun handshake(host: AddonHost): AddonHandshake = error("no common version must never reach this addon")

    override fun openSession(host: AddonHost): SongyanAddonSession =
        error("no common version must never open a session")

    override fun onDisconnect(): Unit = Unit
}
