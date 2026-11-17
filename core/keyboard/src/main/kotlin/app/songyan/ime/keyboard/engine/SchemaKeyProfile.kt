package app.songyan.ime.keyboard.engine

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/**
 * What a schema's own speller says about the keys it is typed on: which codes it needs and what kind
 * of coding that is. Layouts are offered by comparing this with the codes they send, so a scheme
 * whose letters a 26-key board does not cover never gets a board that silently drops them.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SchemaKeyProfile(
    val kind: Kind,
    /** The codes the speller needs, in declared order, without duplicates. */
    val codes: List<String> = emptyList(),
    /** The stroke keys with their captions; only for [Kind.STROKES]. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val strokes: List<Stroke> = emptyList(),
    /** Section of the design note on candidate language: filled by the deployment when it reads the dictionary. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val language: String? = null,
) {
    @Serializable(with = KindSerializer::class)
    enum class Kind(
        val wire: String,
    ) {
        /** Spelled syllable by syllable (pinyin, zhuyin, jyutping): folded 14/18/9-key layouts can run it. */
        SYLLABIC("syllabic"),

        /** One code per character part (wubi, English): only layouts that send each code can run it. */
        SHAPE_CODE("shapeCode"),

        /** A handful of stroke symbols mapped to captions by the scheme. */
        STROKES("strokes"),

        /** No speller or no translator that is provably the primary one. */
        UNKNOWN("unknown"),
    }

    @Serializable
    data class Stroke(
        val code: String,
        val label: String,
    )

    /** Whether the folded 14/18/9-key layouts can be generated for this scheme. */
    val allowsFolding: Boolean get() = kind == Kind.SYLLABIC

    private object KindSerializer : KSerializer<Kind> {
        override val descriptor = PrimitiveSerialDescriptor("SchemaKeyProfile.Kind", PrimitiveKind.STRING)

        override fun serialize(
            encoder: Encoder,
            value: Kind,
        ) = encoder.encodeString(value.wire)

        // "nonLatin" was a kind of the shared fixtures before alphabets beyond ASCII were typed through
        // the same speller as any other; it reads as syllabic. A kind this build does not know reads as
        // unknown, so a newer file never breaks an older reader.
        override fun deserialize(decoder: Decoder): Kind {
            val wire = decoder.decodeString()
            return Kind.entries.firstOrNull { it.wire == wire }
                ?: if (wire == "nonLatin") Kind.SYLLABIC else Kind.UNKNOWN
        }
    }

    companion object {
        /** File name inside a build directory. */
        const val FILE_NAME = "key-profiles.json"

        private val json = Json { ignoreUnknownKeys = true }
        private val mapSerializer = MapSerializer(String.serializer(), serializer())

        fun encode(profiles: Map<String, SchemaKeyProfile>): String = json.encodeToString(mapSerializer, profiles)

        fun decode(text: String): Map<String, SchemaKeyProfile>? =
            try {
                json.decodeFromString(mapSerializer, text)
            } catch (_: IllegalArgumentException) {
                null
            }

        fun decodeOne(text: String): SchemaKeyProfile? =
            try {
                json.decodeFromString(serializer(), text)
            } catch (_: IllegalArgumentException) {
                null
            }
    }
}
