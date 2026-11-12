// SPDX-License-Identifier: Apache-2.0
package app.songyan.ime.addon.sample

import android.content.ComponentName
import android.content.Context
import app.songyan.ime.addon.AddonCapability
import app.songyan.ime.addon.AddonHandshake
import app.songyan.ime.addon.AddonHost
import app.songyan.ime.addon.AddonProtocol
import app.songyan.ime.addon.AddonToolDescriptor
import app.songyan.ime.addon.SongyanAddon
import app.songyan.ime.addon.SongyanAddonSession

/**
 * The sample addon: two text tools, a simulated voice flow and one panel. A real addon replaces
 * these with its own features; the shape of the implementation (interface + session, no Binder
 * code, no caller checks) is the part worth copying.
 */
internal class SampleAddon(
    private val context: Context,
) : SongyanAddon {
    override val addonId: String = "app.songyan.ime.addon.sample"

    override val addonName: String = context.getString(R.string.addon_name)

    override val supportedProtocolVersions: IntRange = AddonProtocol.MIN_VERSION..AddonProtocol.MAX_VERSION

    private val tools: List<AddonToolDescriptor> =
        buildList {
            add(AddonToolDescriptor(TOOL_UPPERCASE, context.getString(R.string.tool_uppercase)))
            add(AddonToolDescriptor(TOOL_BOOK_TITLE, context.getString(R.string.tool_book_title)))
            // The delay tool exists for the timeout case of the integration tests only.
            if (BuildConfig.DEBUG) {
                add(AddonToolDescriptor(TOOL_DELAY, context.getString(R.string.tool_delay)))
                add(AddonToolDescriptor(TOOL_CRASH, context.getString(R.string.tool_crash)))
            }
        }

    override fun handshake(host: AddonHost): AddonHandshake =
        AddonHandshake(
            protocolVersion = host.protocolVersion,
            addonId = addonId,
            addonName = addonName,
            addonVersionName = BuildConfig.VERSION_NAME,
            addonVersionCode = BuildConfig.VERSION_CODE.toLong(),
            capabilities = setOf(AddonCapability.TEXT, AddonCapability.AUDIO, AddonCapability.PANEL),
            tools = tools,
            supportsPanel = true,
            supportsVoice = true,
            settingsComponent = ComponentName(context, SampleSettingsActivity::class.java).flattenToString(),
        )

    override fun openSession(host: AddonHost): SongyanAddonSession = SampleAddonSession(context, host, tools)

    override fun onDisconnect(): Unit = Unit

    internal companion object {
        const val TOOL_UPPERCASE: String = "uppercase"
        const val TOOL_BOOK_TITLE: String = "bookTitle"
        const val TOOL_DELAY: String = "delay"
        const val TOOL_CRASH: String = "crash"
    }
}
