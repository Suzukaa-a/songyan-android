package app.songyan.ime.keyboard.engine

import java.util.Locale

/** Saved selections use these option names as stable keys across bundled schemes. */
enum class BuiltinScriptState(
    val optionName: String,
) {
    SIMPLIFIED("simplification"),
    TRADITIONAL("zht"),
    TAIWAN("zh_tw"),
    HONG_KONG("zh_hk"),
}

object TraditionalDefault {
    /** A locale can seed a new installation, never replace an existing user's choice. */
    fun initialState(
        localeTags: List<String>,
        hasExistingSettings: Boolean,
    ): BuiltinScriptState? {
        if (hasExistingSettings) return null
        val locale = Locale.forLanguageTag(localeTags.firstOrNull().orEmpty())
        val traditional =
            locale.script == "Hant" ||
                (locale.script.isEmpty() && locale.language == "zh" && locale.country in TRADITIONAL_REGIONS)
        if (!traditional) return BuiltinScriptState.SIMPLIFIED
        return when (locale.country) {
            "TW" -> BuiltinScriptState.TAIWAN
            "HK", "MO" -> BuiltinScriptState.HONG_KONG
            else -> BuiltinScriptState.TRADITIONAL
        }
    }

    private val TRADITIONAL_REGIONS = setOf("TW", "HK", "MO")
}
