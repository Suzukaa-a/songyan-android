// SPDX-License-Identifier: Apache-2.0
package app.songyan.ime.addon.sample

import app.songyan.ime.addon.AddonCallerPolicy
import app.songyan.ime.addon.SongyanAddon
import app.songyan.ime.addon.SongyanAddonService

/**
 * The component Songyan binds to. The API library does the Binder work; this class only supplies
 * the addon implementation and the caller policy.
 *
 * A shipping addon pins Songyan's published signing certificate (SONGYAN_HOST_SHA256, set with the
 * Gradle property songyan.addonHostSha256) and keeps the debug escape hatch out of release builds.
 * The debug build accepts any caller so the repository's integration tests and device probes can
 * bind without Songyan's release key.
 */
public class SampleAddonService : SongyanAddonService() {
    override val callerPolicy: AddonCallerPolicy =
        AddonCallerPolicy(
            acceptedHostPackages = AddonCallerPolicy.DEFAULT_HOST_PACKAGES,
            acceptedHostSignerSha256 = setOfNotNull(BuildConfig.SONGYAN_HOST_SHA256.takeIf { it.isNotEmpty() }),
            allowDebugCallers = BuildConfig.DEBUG,
        )

    override fun createAddon(): SongyanAddon = SampleAddon(this)
}
