package app.songyan.ime.tools.regex

/** Which of the three inputs a worker would receive is over its limit. */
enum class RegexField { PATTERN, INPUT, REPLACEMENT }

/** What one run of a pattern over one text produced. */
sealed interface RegexRun {
    /**
     * The pattern ran to its end: [text] is the replacement result, the input itself for a match
     * only run, and [matches] counts the matches found.
     */
    data class Done(
        val text: String,
        val matches: Int,
    ) : RegexRun

    /** Nothing matched and no replacement was asked for. */
    data object NoMatch : RegexRun

    /**
     * The deadline passed and the worker's death was observed, which is what ended the match. A
     * deadline whose worker did not confirm its death is reported as [WorkerFailed] instead.
     */
    data class TimedOut(
        val workerPid: Int,
        val elapsedMs: Long,
    ) : RegexRun

    /** The pattern does not compile under the platform's own regular expression rules. */
    data object PatternInvalid : RegexRun

    /** Nothing was sent: [field] is longer than its limit. */
    data class TooLong(
        val field: RegexField,
    ) : RegexRun

    /** The worker answered with an error, died without answering, or could not be started. */
    data object WorkerFailed : RegexRun
}

/**
 * Caps on what one step may send to the worker, checked before anything is bound, so an oversized
 * text is refused as too long and can never be reported as a deadline.
 */
data class RegexLimits(
    val pattern: Int = 1024,
    val input: Int = 1000,
    val replacement: Int = 1024,
) {
    init {
        require(pattern > 0 && input > 0 && replacement >= 0) { "regex limits must be positive" }
    }

    fun rejectionOf(
        pattern: String,
        input: String,
        replacement: String?,
    ): RegexRun.TooLong? =
        when {
            pattern.length > this.pattern -> RegexRun.TooLong(RegexField.PATTERN)
            input.length > this.input -> RegexRun.TooLong(RegexField.INPUT)
            replacement != null && replacement.length > this.replacement -> RegexRun.TooLong(RegexField.REPLACEMENT)
            else -> null
        }
}
