package app.songyan.ime.tools

/**
 * The limits one run obeys. Every value is injected: the shared bridge header defines the
 * production numbers, tests inject small ones, and this model never guesses a limit.
 */
data class ToolLimits(
    /** Longest input text the run may process, in UTF-16 code units. */
    val maxInputUnits: Int,
    /** How long a run may stay in [ToolPhase.RUNNING] before it is timed out. */
    val timeoutMillis: Long,
    /** How long undo stays offered; null keeps it until the user closes the result. */
    val undoWindowMillis: Long?,
    /** How many returned results are kept for preview. */
    val maxResults: Int,
) {
    init {
        require(maxInputUnits > 0) { "maxInputUnits must be positive" }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        require(maxResults > 0) { "maxResults must be positive" }
        require(undoWindowMillis == null || undoWindowMillis >= 0) { "undoWindowMillis must not be negative" }
    }
}
