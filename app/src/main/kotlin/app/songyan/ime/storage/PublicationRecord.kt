package app.songyan.ime.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * What built a generation: the app build, the librime version linked into it and the version of
 * the bundled Rime data. A generation built by anything else must be rebuilt before the keyboard
 * relies on it (a librime update makes old compiled tables unloadable; a data update leaves the
 * keyboard on stale data).
 */
data class BuildStamp(
    val app: String,
    val engine: String,
    val data: String,
)

/**
 * The single persisted selection record: which immutable generation the input
 * method and settings should use. Written atomically; readers never observe a
 * partially written file.
 */
data class PublicationRecord(
    val version: Int,
    val generation: String,
    val sourceId: String,
    val schemaId: String,
    val revision: String,
    val createdAtEpochMs: Long,
    /** Monotonic publish counter; orders generations independently of names and clocks. */
    val sequence: Long = 0L,
    /** Input methods this generation offers, primary first. Empty for a legacy record. */
    val schemaIds: List<String> = emptyList(),
    /** Null for a record written before stamps existed: always treated as out of date. */
    val stamp: BuildStamp? = null,
    /**
     * Only the input method in use has its 14-, 18- and 9-key layouts yet; the deploy process
     * builds a complete generation of the same source right after.
     */
    val companionsPending: Boolean = false,
    /** Applied configuration identity; empty for none, null for publications predating this field. */
    val configDigest: String? = null,
) {
    fun toJson(): String =
        buildJsonObject {
            put("version", version)
            put("generation", generation)
            put("sourceId", sourceId)
            put("schemaId", schemaId)
            put("revision", revision)
            put("createdAt", createdAtEpochMs)
            put("sequence", sequence)
            put("schemaIds", JsonArray(schemaIds.map { JsonPrimitive(it) }))
            stamp?.let {
                put(
                    "stamp",
                    buildJsonObject {
                        put("app", it.app)
                        put("engine", it.engine)
                        put("data", it.data)
                    },
                )
            }
            if (companionsPending) put("companionsPending", true)
            configDigest?.let { put("configDigest", it) }
        }.toString()

    companion object {
        const val CURRENT_VERSION = 2
        private const val LEGACY_VERSION = 1

        fun parse(json: String?): PublicationRecord? {
            if (json.isNullOrBlank()) return null
            val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return null
            val version = (root["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: return null
            if (version != CURRENT_VERSION && version != LEGACY_VERSION) return null
            val stamp =
                (root["stamp"] as? JsonObject)?.let { stamp ->
                    BuildStamp(
                        app = stamp.string("app") ?: return@let null,
                        engine = stamp.string("engine") ?: return@let null,
                        data = stamp.string("data") ?: return@let null,
                    )
                }
            return PublicationRecord(
                version = version,
                generation = root.string("generation") ?: return null,
                sourceId = root.string("sourceId") ?: return null,
                schemaId = root.string("schemaId") ?: return null,
                revision = root.string("revision") ?: return null,
                createdAtEpochMs = (root["createdAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
                sequence = (root["sequence"] as? JsonPrimitive)?.longOrNull ?: 0L,
                schemaIds =
                    (root["schemaIds"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.contentOrNull }
                        .orEmpty(),
                stamp = stamp,
                companionsPending = (root["companionsPending"] as? JsonPrimitive)?.booleanOrNull == true,
                configDigest = root.string("configDigest"),
            )
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }
}
