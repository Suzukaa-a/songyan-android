package app.songyan.ime.packages

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The user's choices for the engine features a deployment patches into input methods: typo
 * correction, fuzzy pinyin, next-word prediction, Lua components and the grammar model.
 *
 * Choices are kept per input method id (fuzzy pinyin, Lua, grammar) or for every input method
 * (typo correction, prediction), never per stored source, so a new version of a scheme package keeps
 * them. The settings screen writes this; the deployment reads it and patches what each compiled
 * input method can take ([EngineFeatureReport] says what it took).
 */
@Serializable
data class EngineFeatureSettings(
    @SerialName("version") val version: Int = VERSION,
    /** Typo correction on every input method that supports it; on unless the user turned it off. */
    @SerialName("typoCorrection") val typoCorrection: Boolean = true,
    /** Next-word prediction once a database is imported. */
    @SerialName("prediction") val prediction: Boolean = true,
    /** The imported prediction database, or null when there is none. */
    @SerialName("predictionDatabase") val predictionDatabase: StoredFile? = null,
    /** Input method id to the fuzzy pinyin pair ids ([FuzzyPinyin.Pair.id]) it mixes. */
    @SerialName("fuzzyPinyin") val fuzzyPinyin: Map<String, List<String>> = emptyMap(),
    /** Input method id to the Lua component prescriptions the user turned off. */
    @SerialName("disabledLua") val disabledLua: Map<String, List<String>> = emptyMap(),
    /** Imported grammar models. */
    @SerialName("grammarModels") val grammarModels: List<StoredFile> = emptyList(),
    /** Input method id to its grammar choice; absent keeps what the scheme itself configures. */
    @SerialName("grammar") val grammar: Map<String, GrammarChoice> = emptyMap(),
) {
    /** A file the app stored for the engine, with its identity for the published revision. */
    @Serializable
    data class StoredFile(
        @SerialName("name") val name: String,
        @SerialName("size") val size: Long,
        @SerialName("sha256") val sha256: String,
        /** What the user picked, for display. */
        @SerialName("displayName") val displayName: String? = null,
    )

    /** Off, or one of [grammarModels] by [StoredFile.name]. */
    @Serializable
    data class GrammarChoice(
        @SerialName("model") val model: String? = null,
    ) {
        val isOff: Boolean get() = model == null
    }

    /** The fuzzy pairs of [schemaId] that this build knows, in [FuzzyPinyin.allPairs] order. */
    fun fuzzyPairs(schemaId: String): Set<String> =
        fuzzyPinyin[schemaId]
            .orEmpty()
            .filter { FuzzyPinyin.pair(it) != null }
            .toSet()

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        const val VERSION = 1

        /** File name of the prediction database in the engine's data directory. */
        const val PREDICTION_DATABASE = Prediction.DATABASE_PATH

        private val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }

        /** The settings in [text]; defaults when it is not readable, so a damaged file never blocks input. */
        fun decode(text: String?): EngineFeatureSettings =
            text?.let {
                try {
                    json.decodeFromString(serializer(), it)
                } catch (_: IllegalArgumentException) {
                    null
                }
            } ?: EngineFeatureSettings()
    }
}

/**
 * What one deployed build did with [EngineFeatureSettings], per input method, read from the
 * compiled schemas before and after the patches. The settings screen shows this, never the wish.
 */
@Serializable
data class EngineFeatureReport(
    @SerialName("version") val version: Int = EngineFeatureSettings.VERSION,
    @SerialName("schemas") val schemas: Map<String, SchemaFeatures> = emptyMap(),
) {
    /** Whether the app may change a feature of one input method. */
    @Serializable
    enum class Availability {
        /** The app sets it. */
        @SerialName("available")
        AVAILABLE,

        /** The scheme (or its own custom file) configures it; the app leaves it alone. */
        @SerialName("scheme")
        SCHEME,

        /** The input method cannot take it. */
        @SerialName("unsupported")
        UNSUPPORTED,
    }

    @Serializable
    data class LuaComponent(
        @SerialName("list") val list: String,
        @SerialName("prescription") val prescription: String,
        @SerialName("displayName") val displayName: String,
        @SerialName("enabled") val enabled: Boolean,
    )

    @Serializable
    data class SchemaFeatures(
        @SerialName("typoCorrection") val typoCorrection: Availability = Availability.UNSUPPORTED,
        @SerialName("typoCorrectionOn") val typoCorrectionOn: Boolean = false,
        @SerialName("fuzzyPinyin") val fuzzyPinyin: Availability = Availability.UNSUPPORTED,
        @SerialName("fuzzyPairs") val fuzzyPairs: List<String> = emptyList(),
        @SerialName("prediction") val prediction: Availability = Availability.UNSUPPORTED,
        @SerialName("predictionOn") val predictionOn: Boolean = false,
        @SerialName("lua") val lua: Availability = Availability.UNSUPPORTED,
        @SerialName("luaComponents") val luaComponents: List<LuaComponent> = emptyList(),
        @SerialName("grammar") val grammar: Availability = Availability.UNSUPPORTED,
        /** The compiled `grammar/language` after the patches; null when unset, empty when off. */
        @SerialName("grammarLanguage") val grammarLanguage: String? = null,
    )

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        /** File name inside a build directory. */
        const val FILE_NAME = "songyan-features.json"

        private val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }

        fun decode(text: String?): EngineFeatureReport? =
            text?.let {
                try {
                    json.decodeFromString(serializer(), it)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
    }
}
