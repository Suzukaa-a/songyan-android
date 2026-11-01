package app.songyan.ime.content

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * JSON discipline shared by the quick-content documents.
 *
 * The symbol catalog is decoded strictly: it rejects unknown fields exactly like the iOS reader,
 * and every error carries a JSON path. The phrase and clipboard store is decoded field by field
 * instead, which is as tolerant of unknown fields as the iOS `JSONDecoder` it replaces, so a
 * file written on either platform loads on both.
 */
internal object ContentJson {
    /** Strict parser: unknown fields, coercion and explicit nulls are refused. */
    val strict: Json =
        Json {
            ignoreUnknownKeys = false
            coerceInputValues = false
            isLenient = false
            explicitNulls = false
            encodeDefaults = true
        }

    /** Pretty-printed output: exports stay diffable and readable on both platforms. */
    val pretty: Json =
        Json {
            prettyPrint = true
            explicitNulls = false
            encodeDefaults = true
        }

    /**
     * Strict UTF-8 decode of a stored or imported document: null when the bytes are not valid
     * UTF-8. A lenient decode would repair invalid bytes to U+FFFD and the next save would write
     * the replacement characters back into the user's file, so the bytes are refused instead.
     */
    fun decodeUtf8Strict(bytes: ByteArray): String? =
        try {
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }

    /** The document text of [bytes], or a path-accurate malformed-JSON refusal for bad UTF-8. */
    fun decodeText(bytes: ByteArray): String =
        decodeUtf8Strict(bytes) ?: throw ContentException(ContentErrorCode.MALFORMED_JSON, "$")

    fun parse(text: String): JsonElement {
        val trimmed = text.trimStart()
        val first = trimmed.firstOrNull()
        if (first == null || !startsJsonValue(first)) throw ContentException(ContentErrorCode.MALFORMED_JSON, "$")
        return try {
            strict.parseToJsonElement(trimmed)
        } catch (error: Exception) {
            throw ContentException(ContentErrorCode.MALFORMED_JSON, "$", error)
        }
    }

    /**
     * The tree reader accepts a bare word as a string literal, which would turn a truncated or
     * hand-edited file into a "wrong shape" error instead of a malformed one. Requiring a value
     * token first keeps the two failure kinds apart.
     */
    private fun startsJsonValue(first: Char): Boolean =
        first == '{' ||
            first == '[' ||
            first == '"' ||
            first == '-' ||
            first.isDigit() ||
            first == 't' ||
            first == 'f' ||
            first == 'n'

    fun parseObject(element: JsonElement): JsonObject =
        element as? JsonObject ?: throw ContentException(ContentErrorCode.NOT_AN_OBJECT, "$")

    /** The string value of [key], or a path-accurate [ContentErrorCode.INVALID_VALUE]. */
    fun requireString(
        source: JsonObject,
        key: String,
        path: String,
    ): String {
        val value = source[key] ?: throw ContentException(ContentErrorCode.INVALID_VALUE, path + "." + key)
        val primitive =
            value as? JsonPrimitive ?: throw ContentException(ContentErrorCode.INVALID_VALUE, path + "." + key)
        if (!primitive.isString) throw ContentException(ContentErrorCode.INVALID_VALUE, path + "." + key)
        return primitive.content
    }

    /** The int value of [key], or a path-accurate [ContentErrorCode.INVALID_VALUE]. */
    fun requireInt(
        source: JsonObject,
        key: String,
        path: String,
    ): Int {
        val value = source[key] ?: throw ContentException(ContentErrorCode.INVALID_VALUE, path + "." + key)
        val primitive =
            value as? JsonPrimitive ?: throw ContentException(ContentErrorCode.INVALID_VALUE, path + "." + key)
        return primitive.intOrNull ?: throw ContentException(ContentErrorCode.INVALID_VALUE, path + "." + key)
    }

    /** The boolean value of [key], or a path-accurate [ContentErrorCode.INVALID_VALUE]. */
    fun requireBoolean(
        source: JsonObject,
        key: String,
        path: String,
    ): Boolean {
        val value = source[key] ?: throw ContentException(ContentErrorCode.INVALID_VALUE, path + "." + key)
        val primitive =
            value as? JsonPrimitive ?: throw ContentException(ContentErrorCode.INVALID_VALUE, path + "." + key)
        return primitive.booleanOrNull ?: throw ContentException(ContentErrorCode.INVALID_VALUE, path + "." + key)
    }

    fun rejectUnknownFields(
        source: JsonObject,
        path: String,
        allowed: Set<String>,
    ) {
        for (key in source.keys) {
            if (key !in allowed) throw ContentException(ContentErrorCode.UNKNOWN_FIELD, path + "." + key)
        }
    }
}
