package app.songyan.ime.content

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Locale

/**
 * A learning-data backup in the iOS `LearningBackupArchive` wire format (version 1), so a backup
 * made on either platform is read by the other.
 *
 * ```
 * { "version": 1, "producer": "keyboard-extension", "sourceID": "...", "resourceRevision": "...",
 *   "sourceName": "...", "engineVersion": "...", "createdAt": "2026-01-01T00:00:00Z",
 *   "dictionaries": [ { "name": "...", "sha256": "<64 lowercase hex>", "data": "<base64>" } ] }
 * ```
 *
 * Each dictionary's data is a librime `.userdb.txt` snapshot; this type checks only the envelope
 * (bounds, names, digests) and never the snapshot rows, which the engine layer validates. Every
 * construction path runs [validate], so an archive object is always a valid one.
 */
class LearningBackupArchive(
    val sourceId: String,
    val resourceRevision: String,
    val sourceName: String,
    val engineVersion: String,
    createdAt: Instant,
    val dictionaries: List<Dictionary>,
    val version: Int = CURRENT_VERSION,
    val producer: String = EXPECTED_PRODUCER,
) {
    /** Whole seconds: the wire format has no fractions, so a round trip keeps the value. */
    val createdAt: Instant = createdAt.truncatedTo(ChronoUnit.SECONDS)

    init {
        validate()
    }

    /** One dictionary snapshot; [sha256] is always computed from [data] by the public factory. */
    class Dictionary internal constructor(
        val name: String,
        val sha256: String,
        val data: ByteArray,
    ) {
        constructor(name: String, data: ByteArray) : this(validName(name), sha256Hex(data), data)

        override fun equals(other: Any?): Boolean =
            other is Dictionary && other.name == name && other.sha256 == sha256 && other.data.contentEquals(data)

        override fun hashCode(): Int = 31 * name.hashCode() + sha256.hashCode()
    }

    /** The JSON bytes, with sorted keys so the same archive always encodes to the same bytes. */
    fun encoded(): ByteArray {
        validate()
        val json =
            buildJsonObject {
                put("createdAt", ISO_SECONDS.format(createdAt.atOffset(ZoneOffset.UTC)))
                put(
                    "dictionaries",
                    buildJsonArray {
                        for (dictionary in dictionaries) {
                            add(
                                buildJsonObject {
                                    put("data", Base64.getEncoder().encodeToString(dictionary.data))
                                    put("name", dictionary.name)
                                    put("sha256", dictionary.sha256)
                                },
                            )
                        }
                    },
                )
                put("engineVersion", engineVersion)
                put("producer", producer)
                put("resourceRevision", resourceRevision)
                put("sourceID", sourceId)
                put("sourceName", sourceName)
                put("version", version)
            }
        val bytes = Json.encodeToString(JsonObject.serializer(), json).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAXIMUM_ENCODED_BYTES) fail(LearningBackupError.ENCODED_TOO_LARGE)
        return bytes
    }

    override fun equals(other: Any?): Boolean =
        other is LearningBackupArchive &&
            other.version == version &&
            other.producer == producer &&
            other.sourceId == sourceId &&
            other.resourceRevision == resourceRevision &&
            other.sourceName == sourceName &&
            other.engineVersion == engineVersion &&
            other.createdAt == createdAt &&
            other.dictionaries == dictionaries

    override fun hashCode(): Int = sourceId.hashCode() * 31 + createdAt.hashCode()

    private fun validate() {
        if (version != CURRENT_VERSION) fail(LearningBackupError.UNSUPPORTED_VERSION)
        if (producer != EXPECTED_PRODUCER) fail(LearningBackupError.UNEXPECTED_PRODUCER)
        validateMetadata(sourceId, MAXIMUM_SOURCE_ID_BYTES)
        validateMetadata(resourceRevision, MAXIMUM_RESOURCE_REVISION_BYTES)
        validateMetadata(sourceName, MAXIMUM_SOURCE_NAME_BYTES)
        validateMetadata(engineVersion, MAXIMUM_ENGINE_VERSION_BYTES)
        if (dictionaries.size > MAXIMUM_DICTIONARY_COUNT) fail(LearningBackupError.TOO_MANY_DICTIONARIES)
        if (dictionaries.isEmpty()) fail(LearningBackupError.EMPTY_DICTIONARIES)
        val seen = HashSet<String>()
        var total = 0L
        dictionaries.forEachIndexed { index, dictionary ->
            validName(dictionary.name)
            // Names become file names: ones equal after normalization and case folding would
            // overwrite each other on a case-insensitive file system.
            if (!seen.add(uniquenessKey(dictionary.name))) fail(LearningBackupError.DUPLICATE_DICTIONARY_NAME)
            if (dictionary.data.size > MAXIMUM_DICTIONARY_BYTES) fail(LearningBackupError.DICTIONARY_TOO_LARGE, index)
            total += dictionary.data.size
            if (total > MAXIMUM_TOTAL_DICTIONARY_BYTES) fail(LearningBackupError.DICTIONARIES_TOO_LARGE, index)
            validateDigest(dictionary.sha256, index)
            if (sha256Hex(dictionary.data) != dictionary.sha256) fail(LearningBackupError.DIGEST_MISMATCH, index)
        }
    }

    companion object {
        const val CURRENT_VERSION = 1

        /** The producer iOS writes and requires; written here too so iOS accepts Android backups. */
        const val EXPECTED_PRODUCER = "keyboard-extension"
        const val MAXIMUM_DICTIONARY_COUNT = 32
        const val MAXIMUM_DICTIONARY_BYTES = 4 * 1024 * 1024
        const val MAXIMUM_TOTAL_DICTIONARY_BYTES = 8 * 1024 * 1024
        const val MAXIMUM_ENCODED_BYTES = 12 * 1024 * 1024
        const val MAXIMUM_DICTIONARY_NAME_BYTES = 256
        const val MAXIMUM_SOURCE_ID_BYTES = 512
        const val MAXIMUM_RESOURCE_REVISION_BYTES = 256
        const val MAXIMUM_SOURCE_NAME_BYTES = 256
        const val MAXIMUM_ENGINE_VERSION_BYTES = 128
        private const val DIGEST_HEX_LENGTH = 64

        private val ISO_SECONDS: DateTimeFormatter =
            DateTimeFormatter.ofPattern(
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                Locale.ROOT,
            )

        /** What iOS `ISO8601DateFormatter` accepts: whole seconds with `Z` or a `±hh:mm` offset. */
        private val ISO_PATTERN = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(Z|[+-]\d{2}:\d{2})$""")

        /**
         * Reads and fully validates an archive. The size gate runs before any parsing, and the
         * dictionary list is checked item by item, so a hostile file cannot make this allocate
         * beyond the format's bounds.
         */
        fun decode(bytes: ByteArray): LearningBackupArchive {
            if (bytes.size > MAXIMUM_ENCODED_BYTES) fail(LearningBackupError.ENCODED_TOO_LARGE)
            val text = ContentJson.decodeUtf8Strict(bytes) ?: fail(LearningBackupError.MALFORMED)
            val root =
                try {
                    Json.parseToJsonElement(text)
                } catch (_: SerializationException) {
                    fail(LearningBackupError.MALFORMED)
                } catch (_: IllegalArgumentException) {
                    fail(LearningBackupError.MALFORMED)
                } as? JsonObject ?: fail(LearningBackupError.MALFORMED)
            val version = root.int("version")
            val producer = root.string("producer")
            val sourceId = root.string("sourceID")
            val revision = root.string("resourceRevision")
            val sourceName = root.string("sourceName")
            val engineVersion = root.string("engineVersion")
            val createdAt = parseInstant(root.string("createdAt"))
            return LearningBackupArchive(
                sourceId = sourceId,
                resourceRevision = revision,
                sourceName = sourceName,
                engineVersion = engineVersion,
                createdAt = createdAt,
                dictionaries = decodeDictionaries(root),
                version = version,
                producer = producer,
            )
        }

        private fun decodeDictionaries(root: JsonObject): List<Dictionary> {
            val element = root["dictionaries"]
            if (element == null || element is JsonNull) fail(LearningBackupError.MISSING_FIELD)
            val items = element as? JsonArray ?: fail(LearningBackupError.MALFORMED)
            if (items.size > MAXIMUM_DICTIONARY_COUNT) fail(LearningBackupError.TOO_MANY_DICTIONARIES)
            var total = 0L
            return items.mapIndexed { index, item ->
                val entry = item as? JsonObject ?: fail(LearningBackupError.MALFORMED)
                val name = validName(entry.string("name"))
                val sha256 = entry.string("sha256")
                val data =
                    try {
                        Base64.getDecoder().decode(entry.string("data"))
                    } catch (_: IllegalArgumentException) {
                        fail(LearningBackupError.MALFORMED)
                    }
                if (data.size > MAXIMUM_DICTIONARY_BYTES) fail(LearningBackupError.DICTIONARY_TOO_LARGE, index)
                total += data.size
                if (total > MAXIMUM_TOTAL_DICTIONARY_BYTES) fail(LearningBackupError.DICTIONARIES_TOO_LARGE, index)
                validateDigest(sha256, index)
                Dictionary(name, sha256, data)
            }
        }

        private fun JsonObject.field(key: String): JsonPrimitive {
            val value = this[key]
            if (value == null || value is JsonNull) fail(LearningBackupError.MISSING_FIELD)
            return value as? JsonPrimitive ?: fail(LearningBackupError.MALFORMED)
        }

        private fun JsonObject.string(key: String): String {
            val value = field(key)
            if (!value.isString) fail(LearningBackupError.MALFORMED)
            return value.content
        }

        private fun JsonObject.int(key: String): Int {
            val value = field(key)
            if (value.isString) fail(LearningBackupError.MALFORMED)
            return value.content.toIntOrNull() ?: fail(LearningBackupError.MALFORMED)
        }

        private fun parseInstant(text: String): Instant {
            if (!ISO_PATTERN.matches(text)) fail(LearningBackupError.MALFORMED)
            return try {
                OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
            } catch (_: DateTimeParseException) {
                fail(LearningBackupError.MALFORMED)
            }
        }

        /**
         * Dictionary-name rules shared with iOS: non-empty, at most 256 UTF-8 bytes, no path
         * separator, no control or format character, not `.`/`..`, no leading dot.
         */
        internal fun validName(name: String): String {
            val bytes = name.toByteArray(Charsets.UTF_8).size
            if (bytes == 0 || bytes > MAXIMUM_DICTIONARY_NAME_BYTES) fail(LearningBackupError.INVALID_DICTIONARY_NAME)
            if ('/' in name || '\\' in name) fail(LearningBackupError.INVALID_DICTIONARY_NAME)
            if (hasControlCharacter(name)) fail(LearningBackupError.INVALID_DICTIONARY_NAME)
            if (name.startsWith(".")) fail(LearningBackupError.INVALID_DICTIONARY_NAME)
            return name
        }

        private fun validateMetadata(
            value: String,
            limit: Int,
        ) {
            val bytes = value.toByteArray(Charsets.UTF_8).size
            if (bytes == 0) fail(LearningBackupError.EMPTY_METADATA)
            if (bytes > limit) fail(LearningBackupError.METADATA_TOO_LONG)
            if (hasControlCharacter(value)) fail(LearningBackupError.METADATA_CONTROL_CHARACTER)
        }

        private fun validateDigest(
            digest: String,
            index: Int,
        ) {
            if (digest.length != DIGEST_HEX_LENGTH || digest.any { it !in '0'..'9' && it !in 'a'..'f' }) {
                fail(LearningBackupError.MALFORMED_DIGEST, index)
            }
        }

        /** Foundation's `CharacterSet.controlCharacters`: general categories Cc and Cf. */
        private fun hasControlCharacter(value: String): Boolean =
            value.codePoints().anyMatch {
                val type = Character.getType(it)
                type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()
            }

        private fun uniquenessKey(name: String): String =
            Normalizer.normalize(name, Normalizer.Form.NFC).lowercase(Locale.ROOT)

        internal fun sha256Hex(data: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

        private fun fail(
            error: LearningBackupError,
            index: Int? = null,
        ): Nothing = throw LearningBackupException(error, index)
    }
}

/** Why an archive was refused; the UI maps each onto fixed copy that names no dictionary or entry. */
enum class LearningBackupError {
    UNSUPPORTED_VERSION,
    UNEXPECTED_PRODUCER,
    MISSING_FIELD,
    MALFORMED,
    MALFORMED_DIGEST,
    DIGEST_MISMATCH,
    EMPTY_DICTIONARIES,
    TOO_MANY_DICTIONARIES,
    DUPLICATE_DICTIONARY_NAME,
    INVALID_DICTIONARY_NAME,
    DICTIONARY_TOO_LARGE,
    DICTIONARIES_TOO_LARGE,
    ENCODED_TOO_LARGE,
    EMPTY_METADATA,
    METADATA_TOO_LONG,
    METADATA_CONTROL_CHARACTER,
}

class LearningBackupException(
    val error: LearningBackupError,
    /** The offending dictionary's position, for the errors that concern one dictionary. */
    val dictionaryIndex: Int? = null,
) : Exception(error.name + (dictionaryIndex?.let { " at dictionary $it" } ?: ""))
