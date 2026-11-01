package app.songyan.ime.candidate

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Strict JSON discipline shared by the candidate-area, skin and appearance documents.
 *
 * Error text for the user is assembled by the UI layer from [CandidateBarError.reason] and
 * [CandidateBarError.path]; models carry no user-facing copy.
 */
object StrictJson {
    /** Decoding refuses unknown keys, coercion and explicit nulls (the shape of Swift's encodeIfPresent). */
    val decoder: Json =
        Json {
            ignoreUnknownKeys = false
            coerceInputValues = false
            isLenient = false
            explicitNulls = false
            encodeDefaults = true
        }

    /** Encoding writes every default (Swift Codable always encodes stored properties) so exports stay field-complete. */
    val encoder: Json =
        Json {
            explicitNulls = false
            encodeDefaults = true
            prettyPrint = true
        }

    /**
     * Rejects every key that [allowed] does not list for [path], descending into nested objects.
     * [path] uses the dollar-prefixed notation of the file format ("\$", "\$.collapsed", ...).
     */
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
