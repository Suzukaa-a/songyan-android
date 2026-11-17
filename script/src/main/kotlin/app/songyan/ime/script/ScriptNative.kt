package app.songyan.ime.script

/** Entry points of libsongyan-script.so. The library refuses to load outside an isolated process
 * (the guard lives in JNI_OnLoad), so this object is only usable from [ScriptIsolatedService]. */
internal object ScriptNative {
    init {
        System.loadLibrary("songyan-script")
    }

    /** Runs the shared command-line front end in-process. [arguments] are the CLI arguments after
     * the program name (for example ["header", "--script", path]). Returns the process exit code,
     * stdout and stderr as raw bytes: JNI strings are modified UTF-8, which would corrupt 4-byte
     * UTF-8, so the bytes are decoded strictly by the caller. Debug libraries only: a release
     * library has no such symbol and the call throws UnsatisfiedLinkError. */
    external fun runCli(arguments: Array<String>): Array<Any>

    /** Parses the header of [source] and returns the report as UTF-8 JSON with the keys of the CLI
     * `header` command (`ok`, `error`, `line`, `message`, `header`), or null when memory ran out.
     * Unlike [runCli] this entry point exists in release libraries. */
    external fun parseHeader(source: ByteArray): ByteArray?

    // The production run path: direct C state machine, host callbacks in [ScriptRunHost].
    external fun createRun(
        source: ByteArray,
        settingsJson: ByteArray?,
        text: ByteArray?,
        audio: ByteArray?,
        sourceBytes: Int,
        heapBytes: Int,
        durationMs: Int,
        computeMs: Int,
        requestCount: Int,
        responseBytes: Int,
        inputTextBytes: Int,
    ): Long

    external fun stepRun(handle: Long): Int

    external fun setFetchReply(
        accepted: Boolean,
        status: Int,
        body: ByteArray?,
        contentType: String?,
        finalUrl: String?,
        headersJson: String?,
        error: String?,
    )

    external fun cancelRun(handle: Long)

    external fun destroyRun(handle: Long)

    external fun takeResultJson(
        handle: Long,
        step: Int,
    ): ByteArray?
}
