// SPDX-License-Identifier: Apache-2.0
package app.songyan.ime.addon

import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder

/**
 * What the host tells an addon about itself before any work runs. Capabilities are the host's
 * offer, never a promise the addon can rely on beyond what the user does.
 */
public data class AddonHostInfo(
    public val protocolMinVersion: Int = AddonProtocol.MIN_VERSION,
    public val protocolMaxVersion: Int = AddonProtocol.MAX_VERSION,
    public val hostPackage: String = AddonProtocol.HOST_PACKAGE,
    public val hostVersionName: String = "",
    public val hostVersionCode: Long = 0L,
    public val sdkInt: Int = 0,
    public val capabilities: Set<AddonCapability> = AddonCapability.entries.toSet(),
) {
    public companion object {
        /** The host info of the running keyboard build, for the host client. */
        public fun forHost(context: Context): AddonHostInfo {
            val packageInfo =
                try {
                    context.packageManager.getPackageInfo(context.packageName, 0)
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }
            return AddonHostInfo(
                hostPackage = context.packageName,
                hostVersionName = packageInfo?.versionName ?: "",
                hostVersionCode = packageInfo?.longVersionCode ?: 0L,
                sdkInt = android.os.Build.VERSION.SDK_INT,
                capabilities = AddonCapability.entries.toSet(),
            )
        }
    }
}

/** One text tool an addon offers; its id is stable for the addon installation. */
public data class AddonToolDescriptor(
    public val id: String,
    public val title: String,
    public val requiredCapabilities: Set<AddonCapability> = setOf(AddonCapability.TEXT),
) {
    init {
        require(id.isNotBlank()) { "tool id must not be blank" }
        require(title.isNotBlank()) { "tool title must not be blank" }
    }
}

/**
 * The addon's answer to [AddonHostInfo]. A protocolVersion below 1 means incompatible: the host
 * shows the addon as such and runs nothing. All other fields are the addon's declaration; the host
 * still checks the capabilities it can provide.
 */
public data class AddonHandshake(
    public val protocolVersion: Int,
    public val addonId: String = "",
    public val addonName: String = "",
    public val addonVersionName: String = "",
    public val addonVersionCode: Long = 0L,
    public val capabilities: Set<AddonCapability> = emptySet(),
    public val tools: List<AddonToolDescriptor> = emptyList(),
    public val supportsPanel: Boolean = false,
    public val supportsVoice: Boolean = false,
    /** Flattened ComponentName of the addon's own settings activity, when it declares one. */
    public val settingsComponent: String? = null,
    /** Developer-facing reason shown for an incompatible addon; null when compatible. */
    public val incompatibleReason: String? = null,
    public val supportedMinVersion: Int = AddonProtocol.MIN_VERSION,
    public val supportedMaxVersion: Int = AddonProtocol.MAX_VERSION,
) {
    init {
        require(protocolVersion >= 0) { "protocolVersion must not be negative" }
    }

    public val isCompatible: Boolean get() = protocolVersion >= 1

    public companion object {
        /** The handshake an addon returns when its protocol range and the host's do not overlap. */
        public fun incompatible(
            addonId: String,
            addonName: String,
            reason: String,
            supportedMinVersion: Int,
            supportedMaxVersion: Int,
        ): AddonHandshake =
            AddonHandshake(
                protocolVersion = 0,
                addonId = addonId,
                addonName = addonName,
                incompatibleReason = reason,
                supportedMinVersion = supportedMinVersion,
                supportedMaxVersion = supportedMaxVersion,
            )
    }
}

/** One edge of the area the keyboard offers the panel, in pixels. */
public data class AddonInsets(
    public val leftPx: Int = 0,
    public val topPx: Int = 0,
    public val rightPx: Int = 0,
    public val bottomPx: Int = 0,
) {
    public companion object {
        public val ZERO: AddonInsets = AddonInsets()
    }
}

/**
 * Everything a panel may know about the input and the space it was given. [hostWindowToken] is the
 * keyboard window the addon creates its SurfaceControlViewHost against; it is only present when the
 * panel opens, never in later updates.
 */
public data class AddonPanelContext(
    public val hostWindowToken: IBinder? = null,
    public val displayId: Int = 0,
    public val widthPx: Int = 0,
    public val heightPx: Int = 0,
    public val densityDpi: Int = 0,
    public val fontScale: Float = 1f,
    public val insets: AddonInsets = AddonInsets.ZERO,
    public val inputType: Int = 0,
    public val imeOptions: Int = 0,
    public val darkMode: Boolean = false,
    /** The keyboard's primary color, so the panel can match it. ARGB. */
    public val keyboardColor: Int = 0,
)

/** Which text a panel asked to read. */
public enum class AddonTextScope(
    public val wireName: String,
) {
    SELECTION("selection"),
    BEFORE_CURSOR("beforeCursor"),
    ;

    public companion object {
        public fun fromWireName(name: String?): AddonTextScope? = entries.firstOrNull { it.wireName == name }
    }
}

/** Why a panel could not read text. */
public enum class AddonReadBlockReason(
    public val wireName: String,
) {
    SECURE_ENTRY("secureEntry"),
    NO_EDITOR("noEditor"),
    NO_TEXT("noText"),
    INVALID_QUERY("invalidQuery"),
    ;

    public companion object {
        public fun fromWireName(name: String?): AddonReadBlockReason? = entries.firstOrNull { it.wireName == name }
    }
}

/** One panel read request; [maxUnits] is the host's cap for the returned text. */
public data class AddonTextQuery(
    public val mode: AddonTextScope,
    public val maxUnits: Int = 2000,
) {
    init {
        require(maxUnits > 0) { "maxUnits must be positive: " + maxUnits }
    }
}

/** The text a panel read, or a typed reason why there is none. */
public data class AddonTextResult(
    public val text: String? = null,
    public val scope: AddonTextScope? = null,
    public val isTruncated: Boolean = false,
    public val blockReason: AddonReadBlockReason? = null,
)

/** Why the host refused a panel edit before touching the document. */
public enum class AddonWriteBlockReason(
    public val wireName: String,
) {
    SECURE_ENTRY("secureEntry"),
    NO_EDITOR("noEditor"),
    DOCUMENT_CHANGED("documentChanged"),
    INVALID_REQUEST("invalidRequest"),
    NOT_APPLIED("notApplied"),
    ;

    public companion object {
        public fun fromWireName(name: String?): AddonWriteBlockReason? = entries.firstOrNull { it.wireName == name }
    }
}

/** One panel edit: delete [deleteBefore] units before the cursor, then insert [text]. */
public data class AddonTextWrite(
    public val text: String,
    public val deleteBefore: Int = 0,
) {
    init {
        require(deleteBefore >= 0) { "deleteBefore must not be negative: " + deleteBefore }
    }
}

/** How the host answered a panel edit. */
public data class AddonWriteResult(
    public val applied: Boolean,
    public val blockReason: AddonWriteBlockReason? = null,
) {
    public companion object {
        public val APPLIED: AddonWriteResult = AddonWriteResult(applied = true)
    }
}

/** Why a tool run or a voice flow failed. The message is the addon's own text for the user. */
public enum class AddonFailureCode(
    public val wireName: String,
) {
    NOT_SUPPORTED("notSupported"),
    INVALID_REQUEST("invalidRequest"),
    CANCELLED("cancelled"),
    TIMED_OUT("timedOut"),
    TOOL_ERROR("toolError"),
    UNAVAILABLE("unavailable"),
    INTERNAL_ERROR("internalError"),
    ;

    public companion object {
        public fun fromWireName(name: String?): AddonFailureCode? = entries.firstOrNull { it.wireName == name }
    }
}

public data class AddonFailure(
    public val code: AddonFailureCode,
    public val message: String? = null,
)

/** The state the host shows while an addon does its own voice input. */
public enum class AddonVoiceState(
    public val wireName: String,
) {
    IDLE("idle"),
    LISTENING("listening"),
    PROCESSING("processing"),
    FINISHED("finished"),
    FAILED("failed"),
    ;

    public companion object {
        public fun fromWireName(name: String?): AddonVoiceState? = entries.firstOrNull { it.wireName == name }
    }
}

// --- Wire codecs: the only place the typed model meets the key names above. ---

internal fun AddonHostInfo.toWire(): AddonWireMap =
    mapOf(
        AddonKeys.HOST_MIN_PROTOCOL to protocolMinVersion,
        AddonKeys.HOST_MAX_PROTOCOL to protocolMaxVersion,
        AddonKeys.HOST_PACKAGE to hostPackage,
        AddonKeys.HOST_VERSION_NAME to hostVersionName,
        AddonKeys.HOST_VERSION_CODE to hostVersionCode,
        AddonKeys.HOST_SDK_INT to sdkInt,
        AddonKeys.HOST_CAPABILITIES to AddonCapability.sortedWireNames(capabilities),
    )

internal fun addonHostInfoFromWire(map: AddonWireMap): AddonHostInfo =
    AddonHostInfo(
        protocolMinVersion = AddonWire.int(map, AddonKeys.HOST_MIN_PROTOCOL, AddonProtocol.MIN_VERSION),
        protocolMaxVersion = AddonWire.int(map, AddonKeys.HOST_MAX_PROTOCOL, AddonProtocol.MAX_VERSION),
        hostPackage = AddonWire.stringOrEmpty(map, AddonKeys.HOST_PACKAGE),
        hostVersionName = AddonWire.stringOrEmpty(map, AddonKeys.HOST_VERSION_NAME),
        hostVersionCode = AddonWire.long(map, AddonKeys.HOST_VERSION_CODE),
        sdkInt = AddonWire.int(map, AddonKeys.HOST_SDK_INT),
        capabilities = AddonCapability.fromWireNames(AddonWire.stringList(map, AddonKeys.HOST_CAPABILITIES)),
    )

internal fun AddonToolDescriptor.toWire(): AddonWireMap =
    mapOf(
        AddonKeys.TOOL_ID to id,
        AddonKeys.TOOL_TITLE to title,
        AddonKeys.TOOL_CAPABILITIES to AddonCapability.sortedWireNames(requiredCapabilities),
    )

internal fun addonToolDescriptorFromWire(map: AddonWireMap): AddonToolDescriptor =
    AddonToolDescriptor(
        id = AddonWire.stringOrEmpty(map, AddonKeys.TOOL_ID),
        title = AddonWire.stringOrEmpty(map, AddonKeys.TOOL_TITLE),
        requiredCapabilities = AddonCapability.fromWireNames(AddonWire.stringList(map, AddonKeys.TOOL_CAPABILITIES)),
    )

internal fun AddonHandshake.toWire(): AddonWireMap {
    val wire = LinkedHashMap<String, Any?>()
    wire[AddonKeys.HANDSHAKE_PROTOCOL] = protocolVersion
    wire[AddonKeys.HANDSHAKE_ADDON_ID] = addonId
    wire[AddonKeys.HANDSHAKE_ADDON_NAME] = addonName
    wire[AddonKeys.HANDSHAKE_ADDON_VERSION_NAME] = addonVersionName
    wire[AddonKeys.HANDSHAKE_ADDON_VERSION_CODE] = addonVersionCode
    wire[AddonKeys.HANDSHAKE_CAPABILITIES] = AddonCapability.sortedWireNames(capabilities)
    wire[AddonKeys.HANDSHAKE_TOOLS] = tools.map { it.toWire() }
    wire[AddonKeys.HANDSHAKE_PANEL] = supportsPanel
    wire[AddonKeys.HANDSHAKE_VOICE] = supportsVoice
    settingsComponent?.let { wire[AddonKeys.HANDSHAKE_SETTINGS] = it }
    incompatibleReason?.let { wire[AddonKeys.HANDSHAKE_INCOMPATIBLE] = it }
    wire[AddonKeys.HANDSHAKE_SUPPORTED_MIN] = supportedMinVersion
    wire[AddonKeys.HANDSHAKE_SUPPORTED_MAX] = supportedMaxVersion
    return wire
}

internal fun addonHandshakeFromWire(map: AddonWireMap): AddonHandshake =
    AddonHandshake(
        protocolVersion = AddonWire.int(map, AddonKeys.HANDSHAKE_PROTOCOL),
        addonId = AddonWire.stringOrEmpty(map, AddonKeys.HANDSHAKE_ADDON_ID),
        addonName = AddonWire.stringOrEmpty(map, AddonKeys.HANDSHAKE_ADDON_NAME),
        addonVersionName = AddonWire.stringOrEmpty(map, AddonKeys.HANDSHAKE_ADDON_VERSION_NAME),
        addonVersionCode = AddonWire.long(map, AddonKeys.HANDSHAKE_ADDON_VERSION_CODE),
        capabilities = AddonCapability.fromWireNames(AddonWire.stringList(map, AddonKeys.HANDSHAKE_CAPABILITIES)),
        tools = AddonWire.maps(map, AddonKeys.HANDSHAKE_TOOLS).map { addonToolDescriptorFromWire(it) },
        supportsPanel = AddonWire.boolean(map, AddonKeys.HANDSHAKE_PANEL),
        supportsVoice = AddonWire.boolean(map, AddonKeys.HANDSHAKE_VOICE),
        settingsComponent = AddonWire.string(map, AddonKeys.HANDSHAKE_SETTINGS),
        incompatibleReason = AddonWire.string(map, AddonKeys.HANDSHAKE_INCOMPATIBLE),
        supportedMinVersion = AddonWire.int(map, AddonKeys.HANDSHAKE_SUPPORTED_MIN, AddonProtocol.MIN_VERSION),
        supportedMaxVersion = AddonWire.int(map, AddonKeys.HANDSHAKE_SUPPORTED_MAX, AddonProtocol.MAX_VERSION),
    )

internal fun AddonPanelContext.toWire(): AddonWireMap {
    val wire = LinkedHashMap<String, Any?>()
    hostWindowToken?.let { wire[AddonKeys.PANEL_TOKEN] = it }
    wire[AddonKeys.PANEL_DISPLAY] = displayId
    wire[AddonKeys.PANEL_WIDTH] = widthPx
    wire[AddonKeys.PANEL_HEIGHT] = heightPx
    wire[AddonKeys.PANEL_DENSITY_DPI] = densityDpi
    wire[AddonKeys.PANEL_FONT_SCALE] = fontScale
    wire[AddonKeys.PANEL_INSET_LEFT] = insets.leftPx
    wire[AddonKeys.PANEL_INSET_TOP] = insets.topPx
    wire[AddonKeys.PANEL_INSET_RIGHT] = insets.rightPx
    wire[AddonKeys.PANEL_INSET_BOTTOM] = insets.bottomPx
    wire[AddonKeys.PANEL_INPUT_TYPE] = inputType
    wire[AddonKeys.PANEL_IME_OPTIONS] = imeOptions
    wire[AddonKeys.PANEL_DARK] = darkMode
    wire[AddonKeys.PANEL_KEYBOARD_COLOR] = keyboardColor
    return wire
}

internal fun addonPanelContextFromWire(map: AddonWireMap): AddonPanelContext =
    AddonPanelContext(
        hostWindowToken = AddonWire.binder(map, AddonKeys.PANEL_TOKEN),
        displayId = AddonWire.int(map, AddonKeys.PANEL_DISPLAY),
        widthPx = AddonWire.int(map, AddonKeys.PANEL_WIDTH),
        heightPx = AddonWire.int(map, AddonKeys.PANEL_HEIGHT),
        densityDpi = AddonWire.int(map, AddonKeys.PANEL_DENSITY_DPI),
        fontScale = AddonWire.float(map, AddonKeys.PANEL_FONT_SCALE, 1f),
        insets =
            AddonInsets(
                leftPx = AddonWire.int(map, AddonKeys.PANEL_INSET_LEFT),
                topPx = AddonWire.int(map, AddonKeys.PANEL_INSET_TOP),
                rightPx = AddonWire.int(map, AddonKeys.PANEL_INSET_RIGHT),
                bottomPx = AddonWire.int(map, AddonKeys.PANEL_INSET_BOTTOM),
            ),
        inputType = AddonWire.int(map, AddonKeys.PANEL_INPUT_TYPE),
        imeOptions = AddonWire.int(map, AddonKeys.PANEL_IME_OPTIONS),
        darkMode = AddonWire.boolean(map, AddonKeys.PANEL_DARK),
        keyboardColor = AddonWire.int(map, AddonKeys.PANEL_KEYBOARD_COLOR),
    )

internal fun AddonTextQuery.toWire(): AddonWireMap =
    mapOf(
        AddonKeys.QUERY_MODE to mode.wireName,
        AddonKeys.QUERY_MAX_UNITS to maxUnits,
    )

internal fun addonTextQueryFromWire(map: AddonWireMap): AddonTextQuery? {
    val mode = AddonTextScope.fromWireName(AddonWire.string(map, AddonKeys.QUERY_MODE)) ?: return null
    return AddonTextQuery(mode = mode, maxUnits = AddonWire.int(map, AddonKeys.QUERY_MAX_UNITS, 2000).coerceAtLeast(1))
}

internal fun AddonTextResult.toWire(): AddonWireMap {
    val wire = LinkedHashMap<String, Any?>()
    text?.let { wire[AddonKeys.TEXT_VALUE] = it }
    scope?.let { wire[AddonKeys.TEXT_SCOPE] = it.wireName }
    wire[AddonKeys.TEXT_TRUNCATED] = isTruncated
    blockReason?.let { wire[AddonKeys.TEXT_BLOCK_REASON] = it.wireName }
    return wire
}

internal fun addonTextResultFromWire(map: AddonWireMap): AddonTextResult =
    AddonTextResult(
        text = AddonWire.string(map, AddonKeys.TEXT_VALUE),
        scope = AddonTextScope.fromWireName(AddonWire.string(map, AddonKeys.TEXT_SCOPE)),
        isTruncated = AddonWire.boolean(map, AddonKeys.TEXT_TRUNCATED),
        blockReason = AddonReadBlockReason.fromWireName(AddonWire.string(map, AddonKeys.TEXT_BLOCK_REASON)),
    )

internal fun AddonTextWrite.toWire(): AddonWireMap =
    mapOf(
        AddonKeys.WRITE_TEXT to text,
        AddonKeys.WRITE_DELETE_BEFORE to deleteBefore,
    )

internal fun addonTextWriteFromWire(map: AddonWireMap): AddonTextWrite? {
    val deleteBefore = AddonWire.int(map, AddonKeys.WRITE_DELETE_BEFORE)
    if (deleteBefore < 0) return null
    return AddonTextWrite(
        text = AddonWire.stringOrEmpty(map, AddonKeys.WRITE_TEXT),
        deleteBefore = deleteBefore,
    )
}

internal fun AddonWriteResult.toWire(): AddonWireMap {
    val wire = LinkedHashMap<String, Any?>()
    wire[AddonKeys.WRITE_APPLIED] = applied
    blockReason?.let { wire[AddonKeys.WRITE_BLOCK_REASON] = it.wireName }
    return wire
}

internal fun addonWriteResultFromWire(map: AddonWireMap): AddonWriteResult =
    AddonWriteResult(
        applied = AddonWire.boolean(map, AddonKeys.WRITE_APPLIED),
        blockReason = AddonWriteBlockReason.fromWireName(AddonWire.string(map, AddonKeys.WRITE_BLOCK_REASON)),
    )

internal fun AddonFailure.toWire(): AddonWireMap {
    val wire = LinkedHashMap<String, Any?>()
    wire[AddonKeys.FAILURE_CODE] = code.wireName
    message?.let { wire[AddonKeys.FAILURE_MESSAGE] = it }
    return wire
}

internal fun addonFailureFromWire(map: AddonWireMap): AddonFailure =
    AddonFailure(
        code =
            AddonFailureCode.fromWireName(AddonWire.string(map, AddonKeys.FAILURE_CODE))
                ?: AddonFailureCode.INTERNAL_ERROR,
        message = AddonWire.string(map, AddonKeys.FAILURE_MESSAGE),
    )
