package app.songyan.ime.script

import android.content.Context
import android.util.Log

/** Header parsing runs in the isolated process, so the proxy never loads the engine itself. It
 *  uses the production entry point: the shared CLI exists only in Debug libraries. */
internal object HeaderParser {
    private const val TAG = "SongyanScript"

    fun parse(
        context: Context,
        source: ByteArray,
    ): ScriptHeader {
        val answer =
            IsolatedCall.call(context) { service, callback ->
                service.parseHeader(source, callback)
            }
        return fromAnswer(answer)
    }

    /** Turns the isolated process's answer into a header. A missing, failed or empty answer is a
     *  refusal with code "unavailable" and the real reason in logcat, never a JSON parse error. */
    fun fromAnswer(answer: Triple<Int, String, String>?): ScriptHeader {
        if (answer == null) return unavailable("the isolated service did not answer")
        val (exit, stdout, stderr) = answer
        if (exit != 0 || stdout.isBlank()) {
            return unavailable("header parse exited $exit with no report: $stderr")
        }
        return try {
            ScriptProxyProto.headerFromCliReport(stdout)
        } catch (error: Exception) {
            unavailable("unreadable header report ($error): $stdout")
        }
    }

    private fun unavailable(reason: String): ScriptHeader.Refused {
        Log.w(TAG, reason)
        return ScriptHeader.Refused("unavailable", 0, reason, false)
    }
}
