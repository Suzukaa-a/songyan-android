// SPDX-License-Identifier: Apache-2.0
package app.songyan.ime.addon

import android.os.Bundle
import android.os.IBinder

/**
 * The payload shape every cross-process call uses: a flat map of Bundle-compatible values.
 *
 * Typed data classes are the addon-facing API; this map is the single place where the contract
 * turns into a Bundle and back. Keeping the codecs pure (no Bundle in the model) means the whole
 * protocol is unit-testable on the JVM, and a new optional key only needs an encoder and a
 * decoder, never a Binder interface change.
 */
internal typealias AddonWireMap = Map<String, Any?>

/** Central key names. New keys are appended here together with an encoder and a decoder. */
internal object AddonKeys {
    const val HOST_MIN_PROTOCOL = "host.minProtocol"
    const val HOST_MAX_PROTOCOL = "host.maxProtocol"
    const val HOST_PACKAGE = "host.package"
    const val HOST_VERSION_NAME = "host.versionName"
    const val HOST_VERSION_CODE = "host.versionCode"
    const val HOST_SDK_INT = "host.sdkInt"
    const val HOST_CAPABILITIES = "host.capabilities"

    const val HANDSHAKE_PROTOCOL = "handshake.protocol"
    const val HANDSHAKE_ADDON_ID = "handshake.addonId"
    const val HANDSHAKE_ADDON_NAME = "handshake.addonName"
    const val HANDSHAKE_ADDON_VERSION_NAME = "handshake.addonVersionName"
    const val HANDSHAKE_ADDON_VERSION_CODE = "handshake.addonVersionCode"
    const val HANDSHAKE_CAPABILITIES = "handshake.capabilities"
    const val HANDSHAKE_TOOLS = "handshake.tools"
    const val HANDSHAKE_PANEL = "handshake.panel"
    const val HANDSHAKE_VOICE = "handshake.voice"
    const val HANDSHAKE_SETTINGS = "handshake.settings"
    const val HANDSHAKE_INCOMPATIBLE = "handshake.incompatible"
    const val HANDSHAKE_SUPPORTED_MIN = "handshake.supportedMin"
    const val HANDSHAKE_SUPPORTED_MAX = "handshake.supportedMax"

    const val TOOL_ID = "tool.id"
    const val TOOL_TITLE = "tool.title"
    const val TOOL_CAPABILITIES = "tool.capabilities"

    const val PANEL_TOKEN = "panel.token"
    const val PANEL_DISPLAY = "panel.display"
    const val PANEL_WIDTH = "panel.width"
    const val PANEL_HEIGHT = "panel.height"
    const val PANEL_DENSITY_DPI = "panel.densityDpi"
    const val PANEL_FONT_SCALE = "panel.fontScale"
    const val PANEL_INSET_LEFT = "panel.insetLeft"
    const val PANEL_INSET_TOP = "panel.insetTop"
    const val PANEL_INSET_RIGHT = "panel.insetRight"
    const val PANEL_INSET_BOTTOM = "panel.insetBottom"
    const val PANEL_INPUT_TYPE = "panel.inputType"
    const val PANEL_IME_OPTIONS = "panel.imeOptions"
    const val PANEL_DARK = "panel.dark"
    const val PANEL_KEYBOARD_COLOR = "panel.color"

    const val QUERY_MODE = "query.mode"
    const val QUERY_MAX_UNITS = "query.maxUnits"
    const val TEXT_VALUE = "text.value"
    const val TEXT_SCOPE = "text.scope"
    const val TEXT_TRUNCATED = "text.truncated"
    const val TEXT_BLOCK_REASON = "text.reason"

    const val WRITE_TEXT = "write.text"
    const val WRITE_DELETE_BEFORE = "write.deleteBefore"
    const val WRITE_APPLIED = "write.applied"
    const val WRITE_BLOCK_REASON = "write.reason"

    const val FAILURE_CODE = "failure.code"
    const val FAILURE_MESSAGE = "failure.message"

    const val RUN_TEXT = "request.text"
}

/** Typed reads used by the codecs; a missing or wrongly typed value falls back to the default. */
internal object AddonWire {
    fun string(
        map: AddonWireMap,
        key: String,
    ): String? = map[key] as? String

    fun stringOrEmpty(
        map: AddonWireMap,
        key: String,
    ): String = string(map, key) ?: ""

    fun int(
        map: AddonWireMap,
        key: String,
        fallback: Int = 0,
    ): Int = map[key] as? Int ?: fallback

    fun long(
        map: AddonWireMap,
        key: String,
        fallback: Long = 0L,
    ): Long = map[key] as? Long ?: fallback

    fun float(
        map: AddonWireMap,
        key: String,
        fallback: Float = 0f,
    ): Float = map[key] as? Float ?: fallback

    fun boolean(
        map: AddonWireMap,
        key: String,
        fallback: Boolean = false,
    ): Boolean = map[key] as? Boolean ?: fallback

    fun binder(
        map: AddonWireMap,
        key: String,
    ): IBinder? = map[key] as? IBinder

    fun stringList(
        map: AddonWireMap,
        key: String,
    ): List<String> = (map[key] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()

    fun maps(
        map: AddonWireMap,
        key: String,
    ): List<AddonWireMap> {
        val list = map[key] as? List<*> ?: return emptyList()
        return list.mapNotNull { it.asWireMap() }
    }

    fun map(
        map: AddonWireMap,
        key: String,
    ): AddonWireMap? = map[key].asWireMap()
}

@Suppress("UNCHECKED_CAST")
internal fun Any?.asWireMap(): AddonWireMap? = this as? Map<String, Any?>

/**
 * Turns the pure map into a Bundle. Values are limited to the types a Bundle carries without
 * custom Parcelables: String, Int, Long, Float, Boolean, IBinder, nested maps and lists of the
 * above (string lists and nested map lists are the two list shapes the protocol uses).
 */
internal fun AddonWireMap.toBundle(): Bundle {
    val bundle = Bundle()
    for ((key, value) in this) {
        when (value) {
            null -> Unit
            is String -> bundle.putString(key, value)
            is Int -> bundle.putInt(key, value)
            is Long -> bundle.putLong(key, value)
            is Float -> bundle.putFloat(key, value)
            is Boolean -> bundle.putBoolean(key, value)
            is IBinder -> bundle.putBinder(key, value)
            is List<*> -> bundle.putWiredList(key, value)
            is Map<*, *> -> value.asWireMap()?.let { bundle.putBundle(key, it.toBundle()) }
        }
    }
    return bundle
}

private fun Bundle.putWiredList(
    key: String,
    values: List<*>,
) {
    val strings = values.filterIsInstance<String>()
    if (strings.size == values.size) {
        putStringArrayList(key, ArrayList(strings))
        return
    }
    val maps = values.mapNotNull { it.asWireMap() }
    if (maps.size == values.size) {
        putParcelableArrayList(key, ArrayList(maps.map { it.toBundle() }))
    }
}

/** Reads a Bundle back into the pure map; unknown types and unknown keys are ignored. */
@Suppress("DEPRECATION")
internal fun Bundle.toAddonWireMap(): AddonWireMap {
    val result = LinkedHashMap<String, Any?>()
    for (key in keySet()) {
        when (val value = get(key)) {
            null -> Unit
            is String, is Int, is Long, is Float, is Boolean, is IBinder -> result[key] = value
            is Bundle -> result[key] = value.toAddonWireMap()
            is ArrayList<*> -> result[key] = value.toWiredList()
        }
    }
    return result
}

private fun ArrayList<*>.toWiredList(): List<Any?> {
    val strings = filterIsInstance<String>()
    if (strings.size == size) return strings
    return mapNotNull { (it as? Bundle)?.toAddonWireMap() }
}
