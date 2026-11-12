package app.songyan.ime.script

/** How one shared case is turned into a command line for the embedded CLI. The rule comes from the
 * iOS harnesses (Tools/Script/tests/run.sh and run-execution.sh) and lives in one place so the
 * device run and any future host-side runner cannot drift apart. */
object ScriptCaseRun {
    /** Production limits, identical to iOS ScriptToolConfiguration.provisional so both platforms
     *  enforce the same contract. Provisional: they make the shipped example runnable; S0 device
     *  measurements replace them on both platforms together. */
    const val PROVISIONAL_SOURCE_BYTES = 262144L
    const val PROVISIONAL_HEAP_BYTES = 8388608L
    const val PROVISIONAL_DURATION_MS = 4000L
    const val PROVISIONAL_REQUEST_COUNT = 1L
    const val PROVISIONAL_RESPONSE_BYTES = 65536L
    const val PROVISIONAL_INPUT_TEXT_BYTES = 65536L

    /** Default limits of the shared execution harness: fixed test values, not shipped policy. */
    const val DEFAULT_SOURCE_BYTES = 1048576L
    const val DEFAULT_HEAP_BYTES = 8388608L
    const val DEFAULT_DURATION_MS = 4000L
    const val DEFAULT_REQUEST_COUNT = 8L
    const val DEFAULT_RESPONSE_BYTES = 1048576L
    const val DEFAULT_INPUT_TEXT_BYTES = 20000L
    const val DEFAULT_LOG_LIMIT = 1048576L

    /** The exit status a golden JSON deserves: 64 for a caller mistake, 1 for a refused script,
     * 0 otherwise. Mirrors the harness exit conventions. */
    fun exitCodeFor(goldenJson: String): Int =
        when {
            goldenJson.contains("cliError") -> 64
            goldenJson.contains("\"ok\": false") -> 1
            else -> 0
        }

    /** Builds the CLI argument list (after the program name) for one contract case: the header
     * command with the case's capabilities, pointing --script at [scriptPath]. */
    fun contractArguments(
        scriptPath: String,
        capabilities: String,
    ): List<String> = listOf("header", "--script", scriptPath, "--capabilities", capabilities)
}
