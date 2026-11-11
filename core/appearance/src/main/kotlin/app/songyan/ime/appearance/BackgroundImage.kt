package app.songyan.ime.appearance

import java.util.Base64

/**
 * The `data:image/<webp|jpeg|png>;base64,...` payload of an image background.
 *
 * Validation reads the header only and never decodes pixels, so an import cannot be made to allocate
 * a huge bitmap. The header is parsed here rather than with `BitmapFactory` because this module is
 * plain JVM code shared with its unit tests.
 */
object BackgroundImage {
    /** Decoded payload limit. */
    const val MAX_BYTES: Int = 1_048_576

    /** Per-side pixel limit. */
    const val MAX_PIXELS: Int = 4096

    enum class Problem { MALFORMED_DATA_URL, BAD_BASE64, TOO_LARGE, BAD_MAGIC, UNREADABLE, TOO_MANY_PIXELS }

    sealed interface Result {
        data class Ok(
            val mimeType: String,
            val byteCount: Int,
            val pixelWidth: Int,
            val pixelHeight: Int,
        ) : Result

        data class Failure(
            val problem: Problem,
        ) : Result
    }

    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val PNG_END = byteArrayOf(0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte())
    private val TYPES = listOf("webp", "jpeg", "png")

    /** Splits [dataUrl] into its MIME type and base64 payload; null when it has any other shape. */
    private fun split(dataUrl: String): Pair<String, String>? {
        for (type in TYPES) {
            val prefix = "data:image/$type;base64,"
            if (dataUrl.startsWith(prefix)) {
                val payload = dataUrl.substring(prefix.length)
                return if (payload.isEmpty()) null else "image/$type" to payload
            }
        }
        return null
    }

    /**
     * Checks a data URL: canonical base64, magic bytes matching the declared type, decoded size and
     * pixel limits, and a complete file. [verifyContent] false checks only the URL shape.
     */
    fun inspect(
        dataUrl: String,
        verifyContent: Boolean = true,
    ): Result {
        val (mime, payload) = split(dataUrl) ?: return Result.Failure(Problem.MALFORMED_DATA_URL)
        if (!verifyContent) return Result.Ok(mime, 0, 0, 0)
        val bytes = decode(payload)
        return when (bytes) {
            is Decoded.Fail -> Result.Failure(bytes.problem)
            is Decoded.Bytes -> inspectBytes(mime, bytes.value)
        }
    }

    /** The decoded bytes of a data URL that passed [inspect], for the renderer. */
    fun bytesOf(dataUrl: String): ByteArray? = split(dataUrl)?.let { (decode(it.second) as? Decoded.Bytes)?.value }

    private sealed interface Decoded {
        class Bytes(
            val value: ByteArray,
        ) : Decoded

        class Fail(
            val problem: Problem,
        ) : Decoded
    }

    private fun decode(payload: String): Decoded {
        // Longer than the base64 form of the limit cannot decode to an allowed size.
        if (payload.length > (MAX_BYTES + 2) / 3 * 4) return Decoded.Fail(Problem.TOO_LARGE)
        val bytes =
            try {
                Base64.getDecoder().decode(payload)
            } catch (error: IllegalArgumentException) {
                return Decoded.Fail(Problem.BAD_BASE64)
            }
        // Only the canonical encoding is accepted, so one image has one spelling.
        if (Base64.getEncoder().encodeToString(bytes) != payload) return Decoded.Fail(Problem.BAD_BASE64)
        if (bytes.size > MAX_BYTES) return Decoded.Fail(Problem.TOO_LARGE)
        return Decoded.Bytes(bytes)
    }

    private fun inspectBytes(
        mime: String,
        bytes: ByteArray,
    ): Result {
        val magicMatches =
            when (mime) {
                "image/png" -> bytes.startsWith(PNG_MAGIC)
                "image/jpeg" -> bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
                else ->
                    bytes.size >= 12 &&
                        bytes.startsWith("RIFF".toByteArray()) &&
                        String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
            }
        if (!magicMatches) return Result.Failure(Problem.BAD_MAGIC)
        val size =
            when (mime) {
                "image/png" -> pngSize(bytes)
                "image/jpeg" -> jpegSize(bytes)
                else -> webpSize(bytes)
            } ?: return Result.Failure(Problem.UNREADABLE)
        if (size.first <= 0 || size.second <= 0) return Result.Failure(Problem.UNREADABLE)
        if (size.first > MAX_PIXELS || size.second > MAX_PIXELS) return Result.Failure(Problem.TOO_MANY_PIXELS)
        return Result.Ok(mime, bytes.size, size.first, size.second)
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF

    private fun ByteArray.be16(at: Int): Int = (u8(at) shl 8) or u8(at + 1)

    private fun ByteArray.be32(at: Int): Long = (be16(at).toLong() shl 16) or be16(at + 2).toLong()

    private fun ByteArray.le16(at: Int): Int = u8(at) or (u8(at + 1) shl 8)

    private fun ByteArray.le24(at: Int): Int = le16(at) or (u8(at + 2) shl 16)

    private fun ByteArray.le32(at: Int): Long = le16(at).toLong() or (le16(at + 2).toLong() shl 16)

    /** Width and height from IHDR; null unless the file runs through IEND to its last byte. */
    private fun pngSize(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 8 + 25 + PNG_END.size) return null
        if (bytes.be32(8) != 13L || String(bytes, 12, 4, Charsets.US_ASCII) != "IHDR") return null
        val tail = bytes.copyOfRange(bytes.size - PNG_END.size, bytes.size)
        if (!tail.contentEquals(PNG_END)) return null
        val width = bytes.be32(16)
        val height = bytes.be32(20)
        return clamp(width) to clamp(height)
    }

    /** Width and height from the first frame header; null unless the file ends with FF D9. */
    private fun jpegSize(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 4 || bytes.u8(bytes.size - 2) != 0xFF || bytes.u8(bytes.size - 1) != 0xD9) return null
        var at = 2
        while (at + 4 <= bytes.size) {
            if (bytes.u8(at) != 0xFF) return null
            val marker = bytes.u8(at + 1)
            if (marker == 0xFF) {
                at++
                continue
            }
            // Markers without a length field.
            if (marker == 0x01 || marker in 0xD0..0xD8) {
                at += 2
                continue
            }
            val length = bytes.be16(at + 2)
            if (length < 2) return null
            val isFrame = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isFrame) {
                if (at + 9 > bytes.size) return null
                return bytes.be16(at + 7) to bytes.be16(at + 5)
            }
            if (marker == 0xDA) return null
            at += 2 + length
        }
        return null
    }

    /** Width and height from the first chunk; null unless the RIFF size matches the data. */
    private fun webpSize(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 30 || bytes.le32(4) != (bytes.size - 8).toLong()) return null
        return when (String(bytes, 12, 4, Charsets.US_ASCII)) {
            "VP8X" -> (bytes.le24(24) + 1) to (bytes.le24(27) + 1)
            "VP8L" -> {
                if (bytes.u8(20) != 0x2F) return null
                val bits = bytes.le32(21)
                (((bits and 0x3FFF) + 1).toInt()) to ((((bits shr 14) and 0x3FFF) + 1).toInt())
            }
            "VP8 " -> {
                if (bytes.u8(23) != 0x9D || bytes.u8(24) != 0x01 || bytes.u8(25) != 0x2A) return null
                (bytes.le16(26) and 0x3FFF) to (bytes.le16(28) and 0x3FFF)
            }
            else -> null
        }
    }

    private fun clamp(value: Long): Int = value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
