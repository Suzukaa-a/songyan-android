package app.songyan.ime.appearance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Strict JSON discipline for the appearance documents.
 *
 * Android's appearance files use the same shape as the iOS ones (field names, enum raw values,
 * default filling, unknown-field rejection) so files move between the platforms unchanged.
 */
internal object StrictJsonShared {
    val decoder: Json =
        Json {
            ignoreUnknownKeys = false
            coerceInputValues = false
            isLenient = false
            explicitNulls = false
            encodeDefaults = true
        }

    val encoder: Json =
        Json {
            explicitNulls = false
            encodeDefaults = true
            prettyPrint = true
        }

    fun rejectUnknownFields(
        node: JsonObject,
        path: String,
        allowed: (String) -> Set<String>,
        onUnknown: (String) -> Nothing,
    ) {
        val permitted = allowed(path)
        for ((key, value) in node) {
            if (key !in permitted) onUnknown(path + "." + key)
            if (value is JsonObject) rejectUnknownFields(value, path + "." + key, allowed, onUnknown)
        }
    }
}
