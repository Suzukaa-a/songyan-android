// SPDX-License-Identifier: GPL-3.0-only
package app.songyan.ime.settings.state

/** How an installed extension stands with this build of Songyan. */
enum class AddonCompatibility {
    /** The service answered with a protocol version both sides speak. */
    COMPATIBLE,

    /** It answered, but no common protocol version: it is shown and never run. */
    INCOMPATIBLE,

    /** It did not answer: no service, a crash during the handshake, or a caller refusal. */
    UNKNOWN,
}

/** What one extension last did for the keyboard; [error] is the extension's own message. */
enum class AddonCallKind {
    TEXT,
    VOICE,
    PANEL,
}

/** One call the keyboard made into the extension, kept for the list's "last call" line. */
data class AddonCallInfo(
    val kind: AddonCallKind,
    val atEpochMs: Long,
    val durationMs: Long,
    val error: String? = null,
)

/** One text tool the extension offers; the id is the extension's own. */
data class AddonToolInfo(
    val id: String,
    val title: String,
)

/**
 * One installed extension as the 设置 → 扩展 page shows it. [permissions] are the dangerous
 * permissions it declares, already localized by the system; [signerSha256] is the first signing
 * certificate so the user can compare it with the extension's published fingerprint.
 */
data class AddonInfo(
    val packageName: String,
    val label: String,
    val versionName: String,
    val signerSha256: String?,
    val permissions: List<String> = emptyList(),
    val enabled: Boolean = false,
    val compatibility: AddonCompatibility = AddonCompatibility.UNKNOWN,
    val incompatibleReason: String? = null,
    val tools: List<AddonToolInfo> = emptyList(),
    val supportsPanel: Boolean = false,
    val supportsVoice: Boolean = false,
    /** Flattened ComponentName of the extension's own settings page; null when it declares none. */
    val settingsComponent: String? = null,
    val lastCall: AddonCallInfo? = null,
)

/**
 * Bridge the app module implements: discovery, handshake and the enabled switch of installed
 * extensions. The settings UI never binds to an extension itself; it only reads this state.
 */
interface AddonSettingsGateway {
    val available: Boolean

    /** Every installed extension; negotiates with the ones not seen yet. Runs off the main thread. */
    suspend fun list(): List<AddonInfo>

    /** Forces a fresh scan (package changes, newly installed extensions). */
    suspend fun rescan(): List<AddonInfo>

    /**
     * Turns one extension on or off; off is the state of every new installation. False when the
     * switch could not be saved, so the page can roll it back and say so.
     */
    suspend fun setEnabled(
        packageName: String,
        enabled: Boolean,
    ): Boolean
}

/** Stand-in before the app module installs its gateway, or in builds without extensions. */
class UnavailableAddonSettingsGateway : AddonSettingsGateway {
    override val available: Boolean = false

    override suspend fun list(): List<AddonInfo> = emptyList()

    override suspend fun rescan(): List<AddonInfo> = emptyList()

    override suspend fun setEnabled(
        packageName: String,
        enabled: Boolean,
    ): Boolean = false
}

/** Process-wide holder for the app-provided gateway, mirroring the other settings bridges. */
object AddonSettingsHolder {
    private val fallback = UnavailableAddonSettingsGateway()

    @Volatile
    private var installed: AddonSettingsGateway? = null

    val current: AddonSettingsGateway
        get() = installed ?: fallback

    /** Installs the app-provided gateway; replacing one is allowed for tests. */
    fun install(gateway: AddonSettingsGateway) {
        installed = gateway
    }
}
