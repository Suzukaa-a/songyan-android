package app.songyan.ime.keyboard.kana12

/**
 * The direction a twelve-key press resolved to. [Center] is a tap; the other four name where the
 * finger went, never the kana that comes out. The keyboard surface reports one of these names
 * with a row id, so this module never has to own the surface's own direction type.
 */
enum class Kana12FlickDirection {
    Center,
    Left,
    Up,
    Right,
    Down,
}

/**
 * What a grid cell produces. A [Literal] is inserted by the front end as text; it is never forged
 * into an engine key token, because the schema key map does not define those marks.
 */
sealed interface Kana12KeyOutput {
    /** A kana cell, typed through the engine as the row's verified romaji. */
    data class Kana(
        val kana: String,
    ) : Kana12KeyOutput

    /** A mark the front end inserts literally, ending any engine composition. */
    data class Literal(
        val literal: String,
    ) : Kana12KeyOutput
}

/**
 * Static grid, tap cycles and 小゛゜ variant chains of the twelve-key kana keyboard.
 *
 * Rows are addressed by the ids the keyboard surface reports ("a", "ka", ..., "punctuation"), so
 * the surface's row type stays out of this module. A tap types the row's あ段 kana; left is い段,
 * up う段, right え段, down お段. The や and punctuation rows put brackets and marks in the empty
 * vowel cells, and the わ row has no down glyph: the frozen data's only extra candidate (～) has
 * no verified code, so that cell stays empty instead of inventing one.
 */
object Kana12Layout {
    /** The eleven kana-producing rows, in the grid's reading order. */
    val rowIds: List<String> =
        listOf("a", "ka", "sa", "ta", "na", "ha", "ma", "ya", "ra", "wa", "punctuation")

    private val outputs: Map<String, Map<Kana12FlickDirection, Kana12KeyOutput>> =
        mapOf(
            "a" to
                mapOf(
                    Kana12FlickDirection.Center to Kana12KeyOutput.Kana("あ"),
                    Kana12FlickDirection.Left to Kana12KeyOutput.Kana("い"),
                    Kana12FlickDirection.Up to Kana12KeyOutput.Kana("う"),
                    Kana12FlickDirection.Right to Kana12KeyOutput.Kana("え"),
                    Kana12FlickDirection.Down to Kana12KeyOutput.Kana("お"),
                ),
            "ka" to
                mapOf(
                    Kana12FlickDirection.Center to Kana12KeyOutput.Kana("か"),
                    Kana12FlickDirection.Left to Kana12KeyOutput.Kana("き"),
                    Kana12FlickDirection.Up to Kana12KeyOutput.Kana("く"),
                    Kana12FlickDirection.Right to Kana12KeyOutput.Kana("け"),
                    Kana12FlickDirection.Down to Kana12KeyOutput.Kana("こ"),
                ),
            "sa" to
                mapOf(
                    Kana12FlickDirection.Center to Kana12KeyOutput.Kana("さ"),
                    Kana12FlickDirection.Left to Kana12KeyOutput.Kana("し"),
                    Kana12FlickDirection.Up to Kana12KeyOutput.Kana("す"),
                    Kana12FlickDirection.Right to Kana12KeyOutput.Kana("せ"),
                    Kana12FlickDirection.Down to Kana12KeyOutput.Kana("そ"),
                ),
            "ta" to
                mapOf(
                    Kana12FlickDirection.Center to Kana12KeyOutput.Kana("た"),
                    Kana12FlickDirection.Left to Kana12KeyOutput.Kana("ち"),
                    Kana12FlickDirection.Up to Kana12KeyOutput.Kana("つ"),
                    Kana12FlickDirection.Right to Kana12KeyOutput.Kana("て"),
                    Kana12FlickDirection.Down to Kana12KeyOutput.Kana("と"),
                ),
            "na" to
                mapOf(
                    Kana12FlickDirection.Center to Kana12KeyOutput.Kana("な"),
                    Kana12FlickDirection.Left to Kana12KeyOutput.Kana("に"),
                    Kana12FlickDirection.Up to Kana12KeyOutput.Kana("ぬ"),
                    Kana12FlickDirection.Right to Kana12KeyOutput.Kana("ね"),
                    Kana12FlickDirection.Down to Kana12KeyOutput.Kana("の"),
                ),
            "ha" to
                mapOf(
                    Kana12FlickDirection.Center to Kana12KeyOutput.Kana("は"),
                    Kana12FlickDirection.Left to Kana12KeyOutput.Kana("ひ"),
                    Kana12FlickDirection.Up to Kana12KeyOutput.Kana("ふ"),
                    Kana12FlickDirection.Right to Kana12KeyOutput.Kana("へ"),
                    Kana12FlickDirection.Down to Kana12KeyOutput.Kana("ほ"),
                ),
            "ma" to
                mapOf(
                    Kana12FlickDirection.Center to Kana12KeyOutput.Kana("ま"),
                    Kana12FlickDirection.Left to Kana12KeyOutput.Kana("み"),
                    Kana12FlickDirection.Up to Kana12KeyOutput.Kana("む"),
                    Kana12FlickDirection.Right to Kana12KeyOutput.Kana("め"),
                    Kana12FlickDirection.Down to Kana12KeyOutput.Kana("も"),
                ),
            "ya" to
                mapOf(
                    Kana12FlickDirection.Center to Kana12KeyOutput.Kana("や"),
                    Kana12FlickDirection.Left to Kana12KeyOutput.Literal("（"),
                    Kana12FlickDirection.Up to Kana12KeyOutput.Kana("ゆ"),
                    Kana12FlickDirection.Right to Kana12KeyOutput.Literal("）"),
                    Kana12FlickDirection.Down to Kana12KeyOutput.Kana("よ"),
                ),
            "ra" to
                mapOf(
                    Kana12FlickDirection.Center to Kana12KeyOutput.Kana("ら"),
                    Kana12FlickDirection.Left to Kana12KeyOutput.Kana("り"),
                    Kana12FlickDirection.Up to Kana12KeyOutput.Kana("る"),
                    Kana12FlickDirection.Right to Kana12KeyOutput.Kana("れ"),
                    Kana12FlickDirection.Down to Kana12KeyOutput.Kana("ろ"),
                ),
            "wa" to
                mapOf(
                    Kana12FlickDirection.Center to Kana12KeyOutput.Kana("わ"),
                    Kana12FlickDirection.Left to Kana12KeyOutput.Kana("を"),
                    Kana12FlickDirection.Up to Kana12KeyOutput.Kana("ん"),
                    Kana12FlickDirection.Right to Kana12KeyOutput.Kana("ー"),
                ),
            "punctuation" to
                mapOf(
                    Kana12FlickDirection.Center to Kana12KeyOutput.Literal("、"),
                    Kana12FlickDirection.Left to Kana12KeyOutput.Literal("。"),
                    Kana12FlickDirection.Up to Kana12KeyOutput.Literal("？"),
                    Kana12FlickDirection.Right to Kana12KeyOutput.Literal("！"),
                    Kana12FlickDirection.Down to Kana12KeyOutput.Literal("…"),
                ),
        )

    /**
     * Tapping the same key again inside the toggle window walks this list and wraps. It is the
     * row's five 段 kana first, then the row's variants in the same order (small vowels for あ/や,
     * dakuten for か/さ/た/は, then handakuten for は). つ keeps the row variant づ here; the
     * 小゛゜ key walks つ -> っ -> づ instead. ゎ is a variant of わ, so it closes the わ list after ー.
     */
    private val toggleCycles: Map<String, List<String>> =
        mapOf(
            "a" to listOf("あ", "い", "う", "え", "お", "ぁ", "ぃ", "ぅ", "ぇ", "ぉ"),
            "ka" to listOf("か", "き", "く", "け", "こ", "が", "ぎ", "ぐ", "げ", "ご"),
            "sa" to listOf("さ", "し", "す", "せ", "そ", "ざ", "じ", "ず", "ぜ", "ぞ"),
            "ta" to listOf("た", "ち", "つ", "て", "と", "だ", "ぢ", "づ", "で", "ど"),
            "na" to listOf("な", "に", "ぬ", "ね", "の"),
            "ha" to listOf("は", "ひ", "ふ", "へ", "ほ", "ば", "び", "ぶ", "べ", "ぼ", "ぱ", "ぴ", "ぷ", "ぺ", "ぽ"),
            "ma" to listOf("ま", "み", "む", "め", "も"),
            "ya" to listOf("や", "ゆ", "よ", "ゃ", "ゅ", "ょ"),
            "ra" to listOf("ら", "り", "る", "れ", "ろ"),
            "wa" to listOf("わ", "を", "ん", "ー", "ゎ"),
            "punctuation" to listOf(),
        )

    /**
     * The 小゛゜ chain for every reachable kana. Each list is a closed cycle; kana that never
     * react to the modifier are absent (な/ま/ら rows, を, ん, ー).
     */
    private val variantCycles: List<List<String>> =
        listOf(
            listOf("あ", "ぁ"),
            listOf("い", "ぃ"),
            listOf("う", "ぅ"),
            listOf("え", "ぇ"),
            listOf("お", "ぉ"),
            listOf("か", "が"),
            listOf("き", "ぎ"),
            listOf("く", "ぐ"),
            listOf("け", "げ"),
            listOf("こ", "ご"),
            listOf("さ", "ざ"),
            listOf("し", "じ"),
            listOf("す", "ず"),
            listOf("せ", "ぜ"),
            listOf("そ", "ぞ"),
            listOf("た", "だ"),
            listOf("ち", "ぢ"),
            listOf("つ", "っ", "づ"),
            listOf("て", "で"),
            listOf("と", "ど"),
            listOf("は", "ば", "ぱ"),
            listOf("ひ", "び", "ぴ"),
            listOf("ふ", "ぶ", "ぷ"),
            listOf("へ", "べ", "ぺ"),
            listOf("ほ", "ぼ", "ぽ"),
            listOf("や", "ゃ"),
            listOf("ゆ", "ゅ"),
            listOf("よ", "ょ"),
            listOf("わ", "ゎ"),
        )

    private val variantSuccessors: Map<String, String> =
        variantCycles
            .flatMap { cycle ->
                cycle.indices.map { index -> cycle[index] to cycle[(index + 1) % cycle.size] }
            }.toMap()

    /** The cell output, or null when the row or direction has no glyph. */
    fun output(
        rowId: String,
        direction: Kana12FlickDirection,
    ): Kana12KeyOutput? = outputs[rowId]?.get(direction)

    /** The kana of a cell, or null when the cell is a literal or empty. */
    fun kana(
        rowId: String,
        direction: Kana12FlickDirection,
    ): String? = (output(rowId, direction) as? Kana12KeyOutput.Kana)?.kana

    /** The literal of a cell, or null when the cell is kana or empty. */
    fun literal(
        rowId: String,
        direction: Kana12FlickDirection,
    ): String? = (output(rowId, direction) as? Kana12KeyOutput.Literal)?.literal

    /** The repeated-tap walk for a row; empty for rows the model does not tap-cycle. */
    fun toggleCycle(rowId: String): List<String> = toggleCycles[rowId].orEmpty()

    /** The kana the 小゛゜ key turns [kana] into, or null when it has no variant. */
    fun nextVariant(kana: String): String? = variantSuccessors[kana]
}
