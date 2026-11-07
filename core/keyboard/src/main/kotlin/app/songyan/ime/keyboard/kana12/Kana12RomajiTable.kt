package app.songyan.ime.keyboard.kana12

/**
 * Compiled kana -> romaji table for the twelve-key Japanese (kana12) layout.
 *
 * The engine's speller converts the romanization to kana inside the composition, so the keyboard
 * only ever sends ordinary code keys and the composition already shows kana when BackSpace
 * arrives. Every entry was verified against the frozen engine data: each surface types alone as
 * itself, and every ordered pair of surfaces types as the two surfaces concatenated (82 singles
 * and 82 x 82 ordered pairs, no failures), so two adjacent kana never get split differently.
 * The table is a Kotlin literal on purpose: the keyboard thread must never load JSON.
 *
 * Surfaces the frozen data also produces but the twelve-key grid cannot reach (ゔ, ゐ, ゑ, ゕ,
 * ゖ, いぇ, ...) are deliberately absent; adding one needs new native pair evidence first.
 */
object Kana12RomajiTable {
    data class Entry(
        val kana: String,
        val romaji: String,
    )

    /** The verified surfaces in native artifact order: base, dakuten, handakuten, small. */
    val entries: List<Entry> =
        listOf(
            Entry("あ", "a"),
            Entry("い", "i"),
            Entry("う", "u"),
            Entry("え", "e"),
            Entry("お", "o"),
            Entry("か", "ka"),
            Entry("き", "ki"),
            Entry("く", "ku"),
            Entry("け", "ke"),
            Entry("こ", "ko"),
            Entry("さ", "sa"),
            Entry("し", "si"),
            Entry("す", "su"),
            Entry("せ", "se"),
            Entry("そ", "so"),
            Entry("た", "ta"),
            Entry("ち", "ti"),
            Entry("つ", "tu"),
            Entry("て", "te"),
            Entry("と", "to"),
            Entry("な", "na"),
            Entry("に", "ni"),
            Entry("ぬ", "nu"),
            Entry("ね", "ne"),
            Entry("の", "no"),
            Entry("は", "ha"),
            Entry("ひ", "hi"),
            Entry("ふ", "hu"),
            Entry("へ", "he"),
            Entry("ほ", "ho"),
            Entry("ま", "ma"),
            Entry("み", "mi"),
            Entry("む", "mu"),
            Entry("め", "me"),
            Entry("も", "mo"),
            Entry("や", "ya"),
            Entry("ゆ", "yu"),
            Entry("よ", "yo"),
            Entry("ら", "ra"),
            Entry("り", "ri"),
            Entry("る", "ru"),
            Entry("れ", "re"),
            Entry("ろ", "ro"),
            Entry("わ", "wa"),
            Entry("を", "wo"),
            Entry("ん", "nn"),
            Entry("ー", "-"),
            Entry("が", "ga"),
            Entry("ぎ", "gi"),
            Entry("ぐ", "gu"),
            Entry("げ", "ge"),
            Entry("ご", "go"),
            Entry("ざ", "za"),
            Entry("じ", "zi"),
            Entry("ず", "zu"),
            Entry("ぜ", "ze"),
            Entry("ぞ", "zo"),
            Entry("だ", "da"),
            Entry("ぢ", "di"),
            Entry("づ", "du"),
            Entry("で", "de"),
            Entry("ど", "do"),
            Entry("ば", "ba"),
            Entry("び", "bi"),
            Entry("ぶ", "bu"),
            Entry("べ", "be"),
            Entry("ぼ", "bo"),
            Entry("ぱ", "pa"),
            Entry("ぴ", "pi"),
            Entry("ぷ", "pu"),
            Entry("ぺ", "pe"),
            Entry("ぽ", "po"),
            Entry("ぁ", "la"),
            Entry("ぃ", "li"),
            Entry("ぅ", "lu"),
            Entry("ぇ", "le"),
            Entry("ぉ", "lo"),
            Entry("ゃ", "lya"),
            Entry("ゅ", "lyu"),
            Entry("ょ", "lyo"),
            Entry("っ", "ltu"),
            Entry("ゎ", "lwa"),
        )

    private val lookup: Map<String, String> = entries.associate { it.kana to it.romaji }

    /** The code to send to the engine for [kana], or null for a glyph outside the verified set. */
    fun romaji(kana: String): String? = lookup[kana]
}
