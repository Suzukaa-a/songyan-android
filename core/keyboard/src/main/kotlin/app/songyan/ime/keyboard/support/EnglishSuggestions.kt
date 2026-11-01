package app.songyan.ime.keyboard.support

/**
 * Follows the English word being typed on the ABC page and the word before it, from the text the
 * keyboard commits. Letters (and inner apostrophes, `I've`) extend the word; a space finishes it and
 * makes it the context for next-word guesses; anything else ends both.
 */
class EnglishWordTracker(
    private val nextWords: EnglishNextWordModel,
) {
    /** The word being typed; empty between words. */
    var word = ""
        private set

    /** The last finished word, the context of next-word guesses; null when unknown. */
    var previous: String? = null
        private set

    fun note(text: String) {
        for (character in text) {
            when {
                character.isAsciiLetter() || (character == '\'' || character == '’') && word.isNotEmpty() ->
                    word += character
                character == ' ' -> {
                    if (word.isEmpty()) continue
                    previous?.let { nextWords.learn(it, word) }
                    previous = word
                    word = ""
                }
                else -> reset()
            }
        }
    }

    /** One character was deleted before the caret: it came off the word, or the context is lost. */
    fun backspace() {
        if (word.isNotEmpty()) word = word.dropLast(1) else reset()
    }

    /** The caret moved or the document changed: nothing known about the text before it holds. */
    fun reset() {
        word = ""
        previous = null
    }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'
}

/** Filtering and casing of the word completions shown in the ABC page's suggestion slots. */
object EnglishSuggestionShaping {
    /** Slots in the suggestion row. */
    const val LIMIT = 3

    /** Raw candidates scanned per query: a pinyin scheme mixes Chinese results in. */
    const val SCAN = 40

    /** Only single English words (letters with inner apostrophes or hyphens) are suggestions. */
    fun englishWords(candidates: List<String>): List<String> =
        candidates.filter { word ->
            word.isNotEmpty() &&
                word[0].let { it.code < 128 && it.isLetter() } &&
                word.all { it.code < 128 && (it.isLetter() || it == '\'' || it == '-') }
        }

    /** Matches the typed capitalization, drops duplicates, keeps the engine's order. */
    fun shape(
        words: List<String>,
        typed: String,
        limit: Int = LIMIT,
    ): List<String> {
        val allCaps = typed.length > 1 && typed.all { it.isUpperCase() }
        val capitalized = typed.firstOrNull()?.isUpperCase() == true
        val seen = HashSet<String>()
        val result = ArrayList<String>()
        for (word in words) {
            val shaped =
                when {
                    allCaps -> word.uppercase()
                    capitalized -> capitalizeFirst(word)
                    else -> word
                }
            if (!seen.add(shaped.lowercase())) continue
            result += shaped
            if (result.size == limit) break
        }
        return result
    }

    fun capitalizeFirst(word: String): String = word.replaceFirstChar { it.uppercaseChar() }
}
