package app.songyan.ime.keyboard.support

import app.songyan.ime.keyboard.engine.EngineSnapshot

/**
 * Next-word guesses for the ABC page after a finished word (`I've` -> been, got, never).
 *
 * The engine's own post-commit prediction only works on engine commits, never on letters typed
 * directly on the ABC page, so the keyboard carries a small built-in table. It also remembers word
 * pairs typed during this process lifetime (those rank first). Nothing is written to disk.
 */
class EnglishNextWordModel {
    private val learned = HashMap<String, MutableMap<String, Int>>()
    private val learnedOrder = ArrayDeque<String>()

    /** Up to [limit] continuations of [previous] (case-insensitive), learned ones first. */
    fun predictions(
        after: String,
        limit: Int = 3,
    ): List<String> {
        val key = normalized(after)
        if (key.isEmpty() || limit <= 0) return emptyList()
        val result = LinkedHashSet<String>()
        val typed =
            learned[key]?.entries?.sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key },
            )
        typed?.forEach { (word, _) -> result += word }
        SEED[key]?.forEach { result += it }
        return result.take(limit)
    }

    /** Remembers that the user typed [next] right after [previous]. */
    fun learn(
        previous: String,
        next: String,
    ) {
        val key = normalized(previous)
        val value = normalized(next)
        if (key.isEmpty() || value.isEmpty() || value.length > 24) return
        if (learned[key] == null) {
            learnedOrder.addLast(key)
            if (learnedOrder.size > LEARNED_CAPACITY) {
                learned.remove(learnedOrder.removeFirst())
            }
        }
        learned.getOrPut(key) { HashMap() }.merge(value, 1, Int::plus)
    }

    companion object {
        /** Learned pairs kept, oldest dropped first. */
        const val LEARNED_CAPACITY = 512

        /** Lowercased with typographic apostrophes folded, so `I’ve` and `i've` match. */
        fun normalized(word: String): String =
            word.replace('’', '\'').lowercase().trim { it.isWhitespace() || it == '\'' || it == '-' }

        /** Common continuations of frequent English words and contractions. */
        private val SEED: Map<String, List<String>> =
            mapOf(
                "i" to listOf("am", "have", "think", "will", "don't"),
                "i've" to listOf("been", "got", "never", "seen", "had"),
                "i'm" to listOf("not", "going", "so", "sure", "sorry"),
                "i'll" to listOf("be", "have", "see", "never", "try"),
                "i'd" to listOf("like", "love", "be", "rather", "have"),
                "you" to listOf("can", "are", "have", "know", "will"),
                "you're" to listOf("not", "welcome", "right", "going", "so"),
                "you've" to listOf("been", "got", "never", "done", "seen"),
                "you'll" to listOf("be", "see", "have", "need", "find"),
                "we" to listOf("are", "have", "can", "will", "need"),
                "we're" to listOf("going", "not", "all", "here", "still"),
                "we've" to listOf("been", "got", "had", "never", "done"),
                "we'll" to listOf("be", "see", "have", "need", "get"),
                "they" to listOf("are", "have", "were", "will", "can"),
                "they're" to listOf("not", "going", "all", "still", "just"),
                "they've" to listOf("been", "got", "never", "had", "done"),
                "he" to listOf("is", "was", "has", "said", "will"),
                "she" to listOf("is", "was", "has", "said", "will"),
                "he's" to listOf("not", "a", "been", "going", "just"),
                "she's" to listOf("not", "a", "been", "going", "just"),
                "it" to listOf("is", "was", "will", "has", "would"),
                "it's" to listOf("a", "not", "been", "the", "just"),
                "that" to listOf("is", "was", "the", "you", "it"),
                "that's" to listOf("a", "not", "the", "what", "why"),
                "there" to listOf("is", "are", "was", "were", "will"),
                "there's" to listOf("a", "no", "nothing", "something", "the"),
                "this" to listOf("is", "was", "one", "time", "week"),
                "what" to listOf("is", "are", "do", "about", "happened"),
                "what's" to listOf("the", "up", "going", "your", "new"),
                "how" to listOf("are", "to", "about", "do", "much"),
                "how's" to listOf("it", "your", "the", "everything", "work"),
                "where" to listOf("are", "is", "do", "did", "can"),
                "when" to listOf("you", "I", "is", "we", "are"),
                "why" to listOf("not", "is", "do", "did", "are"),
                "who" to listOf("is", "are", "was", "knows", "did"),
                "can" to listOf("you", "I", "we", "be", "not"),
                "can't" to listOf("wait", "be", "believe", "find", "help"),
                "could" to listOf("you", "be", "not", "have", "I"),
                "would" to listOf("you", "be", "like", "have", "love"),
                "should" to listOf("be", "have", "I", "we", "not"),
                "will" to listOf("be", "you", "not", "have", "do"),
                "won't" to listOf("be", "have", "let", "work", "happen"),
                "don't" to listOf("know", "have", "want", "think", "worry"),
                "doesn't" to listOf("matter", "work", "have", "mean", "make"),
                "didn't" to listOf("know", "have", "want", "see", "get"),
                "isn't" to listOf("it", "a", "the", "that", "working"),
                "wasn't" to listOf("a", "sure", "able", "the", "it"),
                "haven't" to listOf("been", "seen", "had", "got", "heard"),
                "do" to listOf("you", "not", "it", "we", "they"),
                "does" to listOf("it", "not", "that", "this", "anyone"),
                "did" to listOf("you", "not", "it", "I", "they"),
                "is" to listOf("it", "a", "the", "not", "there"),
                "are" to listOf("you", "we", "they", "not", "there"),
                "was" to listOf("a", "the", "not", "it", "so"),
                "were" to listOf("you", "not", "the", "in", "they"),
                "have" to listOf("a", "you", "been", "to", "the"),
                "has" to listOf("been", "a", "the", "to", "anyone"),
                "had" to listOf("a", "to", "been", "the", "no"),
                "been" to listOf("a", "the", "working", "there", "so"),
                "be" to listOf("a", "the", "able", "there", "careful"),
                "get" to listOf("the", "a", "it", "to", "back"),
                "got" to listOf("it", "a", "to", "the", "home"),
                "go" to listOf("to", "back", "home", "out", "for"),
                "going" to listOf("to", "on", "out", "home", "back"),
                "want" to listOf("to", "a", "it", "you", "the"),
                "need" to listOf("to", "a", "help", "the", "you"),
                "like" to listOf("a", "the", "to", "it", "this"),
                "love" to listOf("you", "it", "the", "to", "this"),
                "thank" to listOf("you", "god", "goodness"),
                "thanks" to listOf("for", "a", "so", "again", "man"),
                "let" to listOf("me", "us", "it", "you", "them"),
                "let's" to listOf("go", "do", "see", "get", "talk"),
                "see" to listOf("you", "the", "it", "what", "if"),
                "know" to listOf("what", "that", "how", "the", "you"),
                "think" to listOf("it", "so", "about", "that", "you"),
                "make" to listOf("sure", "it", "a", "the", "me"),
                "take" to listOf("a", "care", "the", "it", "your"),
                "come" to listOf("on", "back", "to", "here", "over"),
                "tell" to listOf("me", "you", "him", "her", "them"),
                "look" to listOf("at", "for", "like", "forward", "good"),
                "looking" to listOf("for", "forward", "at", "good", "into"),
                "talk" to listOf("to", "about", "later", "soon", "with"),
                "call" to listOf("me", "you", "it", "back", "him"),
                "please" to listOf("let", "send", "call", "check", "help"),
                "good" to listOf("morning", "night", "luck", "job", "idea"),
                "very" to listOf("much", "good", "well", "nice", "important"),
                "so" to listOf("much", "I", "the", "it", "that"),
                "not" to listOf("sure", "a", "the", "really", "yet"),
                "just" to listOf("a", "wanted", "the", "want", "got"),
                "all" to listOf("the", "of", "right", "day", "good"),
                "one" to listOf("of", "more", "day", "thing", "time"),
                "some" to listOf("of", "time", "people", "more", "other"),
                "any" to listOf("other", "more", "time", "of", "questions"),
                "no" to listOf("problem", "one", "worries", "longer", "idea"),
                "a" to listOf("lot", "few", "little", "good", "great"),
                "an" to listOf("hour", "idea", "email", "issue", "example"),
                "the" to listOf("same", "best", "first", "other", "end"),
                "my" to listOf("own", "friend", "name", "life", "phone"),
                "your" to listOf("own", "name", "help", "time", "phone"),
                "our" to listOf("own", "team", "new", "first", "best"),
                "their" to listOf("own", "new", "first", "best", "way"),
                "to" to listOf("be", "the", "do", "get", "see"),
                "of" to listOf("the", "a", "my", "course", "it"),
                "in" to listOf("the", "a", "my", "your", "order"),
                "on" to listOf("the", "a", "my", "your", "time"),
                "at" to listOf("the", "a", "least", "home", "work"),
                "for" to listOf("the", "a", "you", "me", "your"),
                "with" to listOf("the", "a", "you", "me", "my"),
                "from" to listOf("the", "a", "my", "your", "home"),
                "about" to listOf("the", "it", "a", "this", "that"),
                "and" to listOf("the", "I", "a", "then", "it"),
                "but" to listOf("I", "it", "the", "not", "we"),
                "or" to listOf("not", "the", "a", "something", "two"),
                "if" to listOf("you", "I", "it", "we", "the"),
                "because" to listOf("I", "it", "of", "the", "you"),
                "as" to listOf("well", "a", "soon", "the", "much"),
                "more" to listOf("than", "and", "time", "of", "information"),
                "right" to listOf("now", "away", "here", "there", "back"),
                "happy" to listOf("birthday", "new", "to", "holidays", "hour"),
                "nice" to listOf("to", "one", "work", "day", "job"),
                "sorry" to listOf("for", "I", "about", "to", "but"),
                "hope" to listOf("you", "it", "to", "that", "we"),
                "miss" to listOf("you", "it", "the", "my"),
                "wait" to listOf("for", "a", "until", "and", "there"),
                "every" to listOf("day", "time", "one", "week", "night"),
                "next" to listOf("week", "time", "year", "day", "month"),
                "last" to listOf("night", "week", "time", "year", "one"),
                "today" to listOf("is", "I", "and", "was", "we"),
                "tomorrow" to listOf("morning", "I", "is", "we", "night"),
                "yes" to listOf("I", "it", "we", "please", "sir"),
                "ok" to listOf("I", "thanks", "so", "let's", "sure"),
                "okay" to listOf("I", "thanks", "so", "let's", "sure"),
            )
    }
}

/**
 * What the ABC page's three suggestion slots show.
 *
 * Engine predictions exist only right after an engine commit and close on the next typed letter, so
 * when present they are the most recent context and take the row. Otherwise the controller uses
 * [EnglishNextWordModel] and word completions.
 */
object ABCSuggestionSlots {
    /** Distinct, non-empty prediction texts with their page-local candidate indices. */
    fun enginePredictions(
        snapshot: EngineSnapshot,
        limit: Int,
    ): List<Pair<Int, String>> {
        if (!snapshot.isPrediction || limit <= 0) return emptyList()
        val seen = HashSet<String>()
        return snapshot.candidates
            .filter { it.text.isNotEmpty() && seen.add(it.text) }
            .map { it.index to it.text }
            .take(limit)
    }
}
