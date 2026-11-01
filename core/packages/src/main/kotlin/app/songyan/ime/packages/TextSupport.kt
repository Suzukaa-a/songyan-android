package app.songyan.ime.packages

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Strict UTF-8 decode; null when the bytes are not valid UTF-8 (Foundation returns nil there too). */
internal fun decodeUtf8(bytes: ByteArray): String? =
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

/** Lenient UTF-8 decode that replaces invalid input, like `String(decoding:as:)`. */
internal fun decodeUtf8Lenient(bytes: ByteArray): String = String(bytes, StandardCharsets.UTF_8)

/**
 * `components(separatedBy:)` equivalent: every separator occurrence splits and empty components
 * are kept, so callers that count empty lines behave the same as the iOS code.
 */
internal fun splitKeepingEmpty(
    text: String,
    separator: String,
): List<String> {
    require(separator.isNotEmpty()) { "separator must not be empty" }
    val parts = ArrayList<String>()
    var start = 0
    while (true) {
        val index = text.indexOf(separator, start)
        if (index < 0) {
            parts.add(text.substring(start))
            return parts
        }
        parts.add(text.substring(start, index))
        start = index + separator.length
    }
}

/** `components(separatedBy: "\n")` equivalent, keeping empty lines. */
internal fun splitLinesKeepingEmpty(text: String): List<String> = splitKeepingEmpty(text, "\n")

/** Lowercase hex SHA-256, the identity hash used for manifests and resource receipts. */
internal fun sha256Hex(data: ByteArray): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(data)
        .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

/**
 * `trimmingCharacters(in: .whitespaces)`: spaces, tabs and the Unicode space separators, but not
 * line breaks, unlike Kotlin's `isWhitespace` (which also trims newlines and misses U+00A0).
 */
internal fun String.trimFoundationWhitespace(): String =
    trim { it == '\t' || Character.getType(it).toByte() == Character.SPACE_SEPARATOR }
