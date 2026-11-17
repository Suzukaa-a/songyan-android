package app.songyan.ime.keyboard.engine

/**
 * What a schema's typing produces; it decides key names, punctuation and glyph forms.
 *
 * [languageTag] is the BCP-47 identifier whose glyph forms the language uses, for text runtimes
 * that select localized glyphs from a locale: Chinese and Japanese share code points but not
 * their shapes.
 */
enum class InputLanguage(
    val languageTag: String,
) {
    ZH_HANS("zh-Hans"),
    ZH_HANT("zh-Hant"),
    JA("ja"),
    KO("ko"),

    /** The dictionary has no Chinese, Japanese or Korean: no glyph locale is forced, the system's own applies. */
    UNSPECIFIED(""),
}

/**
 * How a schema is typed. [GENERIC] means no family restriction applies: the schema keeps whatever
 * it does today, so an unproven schema is never treated as pinyin or zhuyin.
 */
enum class InputSchemeFamily {
    GENERIC,
    JAPANESE,
    ZHUYIN,
    CANGJIE_RADICALS,
    LATIN_PHONETIC,
}

data class InputLanguageProfile(
    val language: InputLanguage,
    val family: InputSchemeFamily,
)

/**
 * Decides a schema's input language and family from what the schema itself declares.
 *
 * Only the schema id, its speller alphabet and the script conversion the engine currently has in
 * force take part; the app or system language never does. The family is decided first and no
 * conversion changes it. A known Chinese family writes traditional Chinese unless its own declared
 * simplification is on (Cangjie with simplified output still types Cangjie radicals, but its text
 * and glyphs are simplified). Everything unlisted falls back to today's behavior: simplified
 * Chinese, no family restriction.
 */
object InputLanguageResolver {
    /**
     * [declaredConversion] is the OpenCC config name of the schema's declared script switch that is
     * currently in force (e.g. "s2t"), already normalized by the caller to the bare name. Callers
     * pass a value only when they read it from the schema's own declared switch; null means no
     * conversion is on.
     */
    fun resolve(
        schemaID: String,
        alphabet: String,
        declaredConversion: String?,
        /**
         * What the deployment read from the input method's dictionary ([DETECTED_NONE] when it has no
         * Chinese, Japanese or Korean text; null when it did not look, as in a build made before it did).
         * Only an input method no family claims takes it.
         */
        detected: String? = null,
    ): InputLanguageProfile =
        when {
            schemaID.startsWith("kagiroi") -> InputLanguageProfile(InputLanguage.JA, InputSchemeFamily.JAPANESE)
            schemaID.startsWith("bopomofo") || isBopomofoAlphabet(alphabet) ->
                InputLanguageProfile(traditionalUnlessSimplified(declaredConversion), InputSchemeFamily.ZHUYIN)
            schemaID.startsWith("cangjie") || schemaID.startsWith("quick") ->
                InputLanguageProfile(
                    traditionalUnlessSimplified(declaredConversion),
                    InputSchemeFamily.CANGJIE_RADICALS,
                )
            schemaID.startsWith("jyut6ping3") ->
                InputLanguageProfile(traditionalUnlessSimplified(declaredConversion), InputSchemeFamily.LATIN_PHONETIC)
            detected != null ->
                InputLanguageProfile(
                    detectedLanguage(detected, declaredConversion),
                    InputSchemeFamily.GENERIC,
                )
            isTraditionalConversion(declaredConversion) ->
                InputLanguageProfile(InputLanguage.ZH_HANT, InputSchemeFamily.GENERIC)
            else -> InputLanguageProfile(InputLanguage.ZH_HANS, InputSchemeFamily.GENERIC)
        }

    /** The language a dictionary's text proves; a declared script switch in force still flips Chinese. */
    private fun detectedLanguage(
        detected: String,
        declaredConversion: String?,
    ): InputLanguage {
        val chinese =
            when (detected) {
                "zh-Hans" -> InputLanguage.ZH_HANS
                "zh-Hant" -> InputLanguage.ZH_HANT
                DETECTED_SYSTEM_CHINESE -> systemChinese()
                else -> null
            }
        if (chinese != null) {
            return when {
                isTraditionalConversion(declaredConversion) -> InputLanguage.ZH_HANT
                declaredConversion in SIMPLIFIED_CONVERSIONS -> InputLanguage.ZH_HANS
                else -> chinese
            }
        }
        return when (detected) {
            "ja" -> InputLanguage.JA
            "ko" -> InputLanguage.KO
            else -> InputLanguage.UNSPECIFIED
        }
    }

    /** The Chinese script of the system language; Simplified when the system is not Chinese. */
    private fun systemChinese(): InputLanguage {
        val locale = java.util.Locale.getDefault()
        if (locale.language != "zh") return InputLanguage.ZH_HANS
        return if (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")) {
            InputLanguage.ZH_HANT
        } else {
            InputLanguage.ZH_HANS
        }
    }

    /** The dictionary's Chinese is about evenly Simplified and Traditional: the system language decides. */
    const val DETECTED_SYSTEM_CHINESE = "zh"

    /** The deployment read the dictionary and found no Chinese, Japanese or Korean text. */
    const val DETECTED_NONE = "none"

    // The Dachen layout carries this run in its alphabet while its punctuation keys sit elsewhere,
    // so each of them is required on its own rather than as one contiguous sequence.
    private fun isBopomofoAlphabet(alphabet: String): Boolean =
        alphabet.contains("1qaz2wsx") && BOPOMOFO_PUNCTUATION.all { it in alphabet }

    // Only these configurations turn a simplified native scheme into traditional output; other
    // declared conversions (t2s and friends) still produce simplified text.
    private fun isTraditionalConversion(declaredConversion: String?): Boolean =
        declaredConversion != null && declaredConversion in TRADITIONAL_CONVERSIONS

    private fun traditionalUnlessSimplified(declaredConversion: String?): InputLanguage =
        if (declaredConversion in SIMPLIFIED_CONVERSIONS) InputLanguage.ZH_HANS else InputLanguage.ZH_HANT

    private const val BOPOMOFO_PUNCTUATION = "-;,./"

    private val SIMPLIFIED_CONVERSIONS = setOf("t2s", "tw2s", "tw2sp", "hk2s")

    private val TRADITIONAL_CONVERSIONS = setOf("s2t", "s2tw", "s2twp", "s2hk", "t2tw", "t2hk")
}
