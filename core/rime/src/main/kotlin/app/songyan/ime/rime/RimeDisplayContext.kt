package app.songyan.ime.rime

import app.songyan.ime.keyboard.display.SchemaDisplayCapability
import app.songyan.ime.keyboard.engine.CompositionDisplayMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * The active schema's display context (`rb_copy_display_context_json`): the declared
 * `keyboard_adapter` capability, the facts of the source schema it names and the producer that is
 * actually running. [authorizedMode] is the single decision whether the capability may drive a
 * candidate decode; everything else stays on the engine's own preedit.
 */
data class RimeDisplayContext(
    val capability: SchemaDisplayCapability?,
    /** A block is present even when it failed the strict decode; it still blocks the legacy spelling. */
    val capabilityPresent: Boolean,
    val source: Source?,
    val producer: Producer?,
) {
    data class Source(
        val sourceSchema: String,
        val translatorNamespace: String,
        val dictionary: String,
        val declaration: String,
    )

    data class Producer(
        val schemaID: String,
        val translatorNamespace: String,
        val dictionary: String,
        val prism: String,
        val producerLoaded: Boolean,
    )

    /**
     * The mode the capability may apply, or null to keep the native preedit. `canonical` and
     * `source_code` need the block, the source and the running producer to agree and the producer's
     * table and prism to be loaded; `canonical` also needs the source to declare canonical pinyin.
     */
    fun authorizedMode(): CompositionDisplayMode? {
        val capability = capability ?: return null
        if (capability.version != SchemaDisplayCapability.SUPPORTED_VERSION) return null
        return when (capability.mode) {
            CompositionDisplayMode.NATIVE, CompositionDisplayMode.SOURCE_RENDERED -> null
            CompositionDisplayMode.CANONICAL, CompositionDisplayMode.SOURCE_CODE -> {
                val producer = producer ?: return null
                val source = source ?: return null
                val agrees =
                    producer.producerLoaded &&
                        producer.prism.isNotEmpty() &&
                        capability.adapterSchema == producer.schemaID &&
                        capability.translatorNamespace == source.translatorNamespace &&
                        capability.translatorNamespace == producer.translatorNamespace &&
                        producer.dictionary == source.dictionary
                if (!agrees) return null
                if (capability.mode == CompositionDisplayMode.CANONICAL &&
                    source.declaration != CANONICAL_PINYIN_DECLARATION
                ) {
                    return null
                }
                capability.mode
            }
        }
    }

    companion object {
        const val CANONICAL_PINYIN_DECLARATION = "canonical_pinyin"

        private val json = Json { ignoreUnknownKeys = true }

        /** Null when the bridge JSON is malformed; a missing block decodes as "no capability". */
        fun fromJson(text: String): RimeDisplayContext? {
            val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
            val capability = (root["capability"] as? JsonObject)?.let { SchemaDisplayCapability.fromWire(it) }
            val present = root.boolean("capability_present") ?: (capability != null)
            val source =
                (root["source"] as? JsonObject)?.let { wire ->
                    Source(
                        sourceSchema = wire.string("source_schema") ?: return@let null,
                        translatorNamespace = wire.string("translator_namespace") ?: return@let null,
                        dictionary = wire.string("dictionary") ?: return@let null,
                        declaration = wire.string("declaration").orEmpty(),
                    )
                }
            val producer =
                (root["producer"] as? JsonObject)?.let { wire ->
                    Producer(
                        schemaID = wire.string("schema_id") ?: return@let null,
                        translatorNamespace = wire.string("translator_namespace") ?: return@let null,
                        dictionary = wire.string("dictionary").orEmpty(),
                        prism = wire.string("prism").orEmpty(),
                        producerLoaded = wire.boolean("producer_loaded") ?: false,
                    )
                }
            return RimeDisplayContext(capability, present, source, producer)
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    }
}
