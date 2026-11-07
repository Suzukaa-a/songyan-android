package app.songyan.ime.content

/**
 * The three example tools every install starts with.
 *
 * The examples are the v1 "copy from example" source, so their names are authored in every
 * interface language and their identifiers are stable (a copied tool keeps its own identifier).
 *
 * The script-conversion example does not pick its own conversion: which OpenCC config converts
 * correctly depends on the input language the caller knows and this module does not, so the caller
 * passes the config in. The other two are fully determined.
 */
object BuiltinToolDefinitions {
    /** Stable identifier of the script-conversion example. */
    const val SCRIPT_CONVERSION_ID: String = "builtin-script-conversion"

    /** Stable identifier of the full-width/half-width example. */
    const val WIDTH_ID: String = "builtin-width"

    /** Stable identifier of the letter-case example. */
    const val LETTER_CASE_ID: String = "builtin-letter-case"

    /**
     * The three examples in their default listing order.
     *
     * [simplification] is the OpenCC config the caller selected for the current input language
     * (for example s2t, s2twp, or t2s).
     */
    fun all(simplification: OpenCCToolConfig): List<ToolDefinition> =
        listOf(
            scriptConversion(simplification),
            width(),
            letterCase(),
        )

    /** Converts script with the caller-selected [config]; never picks a config on its own. */
    fun scriptConversion(config: OpenCCToolConfig): ToolDefinition =
        ToolDefinition(
            id = SCRIPT_CONVERSION_ID,
            name =
                mapOf(
                    "zh-Hans" to "繁简转换",
                    "zh-Hant" to "繁簡轉換",
                    "ja" to "簡繁変換",
                    "en" to "Script conversion",
                ),
            steps = listOf(ToolStep.OpenCC(config)),
        ).also { it.validate() }

    /** Turns full-width letters, digits and punctuation into their half-width forms. */
    fun width(): ToolDefinition =
        ToolDefinition(
            id = WIDTH_ID,
            name =
                mapOf(
                    "zh-Hans" to "全角半角",
                    "zh-Hant" to "全形半形",
                    "ja" to "全角半角",
                    "en" to "Full/half width",
                ),
            steps = listOf(ToolStep.Width(ToolStep.WidthMode.TO_HALF)),
        ).also { it.validate() }

    /** Uppercases the text. */
    fun letterCase(): ToolDefinition =
        ToolDefinition(
            id = LETTER_CASE_ID,
            name =
                mapOf(
                    "zh-Hans" to "英文大小写",
                    "zh-Hant" to "英文大小寫",
                    "ja" to "大文字・小文字",
                    "en" to "Letter case",
                ),
            steps = listOf(ToolStep.CaseTransform(ToolStep.CaseMode.UPPER)),
        ).also { it.validate() }
}
