package app.songyan.ime.keyboard.engine

/**
 * A real absolute candidate plus its optional provenance-checked pronunciation.
 *
 * [readingPaths] come from the engine (each path is one complete syllable sequence for [text]);
 * null means the engine could not prove a reading, and [readingsComplete] is then false.
 */
data class ReadingCandidate(
    val index: Int,
    val text: String,
    val readingPaths: List<List<String>>? = null,
    val readingsComplete: Boolean = true,
    val comment: String = "",
    val selectKey: String? = null,
    val selectLabel: String? = null,
) {
    /** The first proven pronunciation, if any. */
    val syllables: List<String>? get() = readingPaths?.firstOrNull()

    init {
        require(readingsComplete || readingPaths == null || readingPaths.isEmpty()) {
            "an incomplete reading has no complete path"
        }
    }

    companion object {
        fun of(
            index: Int,
            text: String,
            syllables: List<String>? = null,
            readingPaths: List<List<String>>? = null,
            readingsComplete: Boolean = true,
            comment: String = "",
            selectKey: String? = null,
            selectLabel: String? = null,
        ): ReadingCandidate {
            val paths = readingPaths ?: syllables?.let { listOf(it) }
            return ReadingCandidate(
                index = index,
                text = text,
                readingPaths = paths,
                readingsComplete = readingsComplete && paths != null,
                comment = comment,
                selectKey = selectKey,
                selectLabel = selectLabel,
            )
        }
    }
}
