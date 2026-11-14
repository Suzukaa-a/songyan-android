package app.songyan.ime.script

import java.util.concurrent.atomic.AtomicReference

/**
 * The Java side of the C host callbacks (authorize/fetch/log). The isolated service installs one
 * instance before each run; fetch forwards to the proxy process over Binder, which owns the real
 * network. The C layer calls these synchronously on the engine thread.
 */
class ScriptRunHost(
    private val declaredHosts: List<String>,
    private val settingUrls: List<String>,
    private val transport: (
        url: String,
        method: String,
        headersJson: String?,
        body: ByteArray?,
        timeoutMillis: Long,
    ) -> FetchReply,
) {
    /** What the transport did: [accepted] with the response parts, or the refusal reason. */
    data class FetchReply(
        val accepted: Boolean,
        val status: Int = 0,
        val body: ByteArray? = null,
        val contentType: String? = null,
        val finalUrl: String? = null,
        val headersJson: String? = null,
        val error: String? = null,
    )

    fun authorize(
        url: String,
        phase: Int,
    ): Boolean = ScriptAddressPolicy.authorize(url, declaredHosts, settingUrls).allowed

    /** Called from C during the run; the reply is handed back through [ScriptNative.setFetchReply]. */
    fun fetch(
        url: String,
        method: String,
        headersJson: String?,
        body: ByteArray?,
        timeoutMillis: Long,
    ) {
        val reply =
            runCatching { transport(url, method, headersJson, body, timeoutMillis) }.getOrElse { error ->
                FetchReply(accepted = false, error = error.javaClass.simpleName + ": " + (error.message ?: ""))
            }
        ScriptNative.setFetchReply(
            reply.accepted,
            reply.status,
            reply.body,
            reply.contentType,
            reply.finalUrl,
            reply.headersJson,
            reply.error,
        )
    }

    fun log(line: ByteArray) {
        currentLog.add(String(line, Charsets.UTF_8))
    }

    /** The console lines of the run being driven; cleared by the next [use]. */
    val consoleLines: List<String> get() = currentLog.toList()

    private val currentLog = ArrayList<String>()

    companion object {
        private val installed = AtomicReference<ScriptRunHost?>(null)

        /** The C callbacks are static; exactly one host lives at a time. */
        @JvmStatic
        fun use(): ScriptRunHost = installed.get() ?: error("no run host installed")

        fun install(host: ScriptRunHost) {
            installed.set(host)
        }

        fun clear() {
            installed.set(null)
        }
    }
}
