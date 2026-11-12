// SPDX-License-Identifier: Apache-2.0
package app.songyan.ime.addon

/**
 * Version and discovery constants for the external-addon protocol, plus the version negotiation
 * both sides run before anything else.
 *
 * The protocol is append-only (the rules are in docs/ARCHITECTURE.md and addon-api/README.md):
 * a new protocol version may only add methods to the Binder
 * interfaces and optional keys to the payload bundles. Methods are never removed, renamed or
 * reordered, and an existing key never changes meaning. Both sides state the version range they
 * support, and the highest common version is used; with no common version the addon is shown as
 * incompatible and is not run.
 */
public object AddonProtocol {
    /** The protocol version this library implements. */
    public const val VERSION: Int = 1

    /** The oldest protocol version declarations in this library can speak. */
    public const val MIN_VERSION: Int = 1

    /** The newest protocol version declarations in this library can speak. */
    public const val MAX_VERSION: Int = 1

    /** Intent action an addon service declares in its manifest; the host discovers addons by it. */
    public const val SERVICE_ACTION: String = "app.songyan.ime.addon.SERVICE"

    /** Package of the Songyan keyboard, the only host an addon should accept. */
    public const val HOST_PACKAGE: String = "app.songyan.ime"

    /** Package of the debug build of the keyboard; also accepted by debug addons. */
    public const val HOST_DEBUG_PACKAGE: String = "app.songyan.ime.debug"

    /**
     * The highest version both sides support, or null when the ranges do not overlap. Callers
     * must treat null as "incompatible" and run nothing.
     */
    public fun negotiatedVersion(
        hostMinVersion: Int,
        hostMaxVersion: Int,
        addonMinVersion: Int,
        addonMaxVersion: Int,
    ): Int? {
        if (hostMinVersion < 1 || addonMinVersion < 1) return null
        if (hostMinVersion > hostMaxVersion || addonMinVersion > addonMaxVersion) return null
        val lowest = maxOf(hostMinVersion, addonMinVersion)
        val highest = minOf(hostMaxVersion, addonMaxVersion)
        return if (lowest <= highest) highest else null
    }
}

/**
 * What one side can offer the other. A tool lists the capabilities it needs; the host lists the
 * ones it can provide and never assumes a tool's declaration proves the host can run it.
 */
public enum class AddonCapability(
    public val wireName: String,
) {
    /** Hands over text and writes the result back. */
    TEXT("text"),

    /** Shows a voice session and puts the recognized text on screen. */
    AUDIO("audio"),

    /** Embeds the addon's own panel inside the keyboard window. */
    PANEL("panel"),
    ;

    public companion object {
        /** Parses one wire name; null for a name this library does not know (forward compatible). */
        public fun fromWireName(name: String?): AddonCapability? = entries.firstOrNull { it.wireName == name }

        internal fun sortedWireNames(capabilities: Set<AddonCapability>): List<String> =
            capabilities
                .map {
                    it.wireName
                }.sorted()

        internal fun fromWireNames(names: List<String>): Set<AddonCapability> =
            names.mapNotNull { fromWireName(it) }.toSet()
    }
}
