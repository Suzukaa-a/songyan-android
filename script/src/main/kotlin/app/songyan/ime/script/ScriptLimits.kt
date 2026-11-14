package app.songyan.ime.script

/**
 * The limits both apps ship. They are defined once, as the SONGYAN_SCRIPT_SHIPPED_* constants of
 * the shared contract header (cpp/include/songyan_script.h); the proxy process cannot load the
 * engine library to read them, so they are repeated here and ScriptLimitsTest fails when the two
 * disagree. A script's @timeout replaces [DURATION_MS] for that script.
 */
object ScriptLimits {
    const val SOURCE_BYTES = 262144L
    const val HEAP_BYTES = 8388608L
    const val DURATION_MS = 30000L
    const val COMPUTE_MS = 4000L
    const val REQUEST_COUNT = 4L
    const val RESPONSE_BYTES = 65536L
    const val INPUT_TEXT_BYTES = 65536L
    const val MAX_TIMEOUT_SECONDS = 600L

    /** The wall-clock budget of one run of a script, as the engine computes it. */
    fun durationMillis(timeoutSeconds: Int?): Long = timeoutSeconds?.let { it * 1000L } ?: DURATION_MS
}
