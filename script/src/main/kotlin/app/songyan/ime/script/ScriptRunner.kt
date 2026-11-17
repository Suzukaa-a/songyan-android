package app.songyan.ime.script

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection

/** Runs one stored script through the isolated process on the production path: the C state
 *  machine drives the engine, and fetch travels Binder-to-proxy for the real network. The
 *  canonical JSON result (the same shape the shared CLI prints) maps to [ScriptRunOutcome]. */
internal class ScriptRunner(
    private val context: Context,
    private val store: ScriptStore,
) {
    /** The run in progress. Runs are sequential, so one slot is enough; cancel acts on it. */
    private class Active(
        val id: String,
    ) {
        @Volatile var cancelled = false

        @Volatile var isolated: ScriptIsolatedProcess? = null

        @Volatile var connection: HttpURLConnection? = null

        fun cancel() {
            cancelled = true
            runCatching { isolated?.cancelRun() }
            runCatching { connection?.disconnect() }
        }
    }

    @Volatile private var active: Active? = null

    /** Keeps the local transport binder alive while the isolated process holds its proxy. */
    private var activeTransport: ScriptTransport.Stub? = null

    /** Stops [id] if it is running: the engine stops, and the request it waits on is dropped. */
    fun cancel(id: String) {
        active?.takeIf { it.id == id }?.cancel()
    }

    /** Stops whatever is running; the service is going away and nobody will read the answer. */
    fun cancelAll() {
        active?.cancel()
    }

    fun run(
        id: String,
        text: String,
    ): ScriptRunOutcome = runStored(id, text.toByteArray(Charsets.UTF_8), null, 0)

    /** Runs a stored `@input audio` script. [audio] is the open descriptor of the recorded WAV:
     *  the host keeps it out of the Binder transaction because a minute of 16 kHz mono audio is
     *  larger than one, and the isolated process reads the bytes from the descriptor. */
    fun runAudio(
        id: String,
        audio: ParcelFileDescriptor,
    ): ScriptRunOutcome {
        val audioSize =
            runCatching { Os.fstat(audio.fileDescriptor).st_size }.getOrElse {
                return ScriptRunOutcome.Failed("unavailable", "the audio input could not be read", 0)
            }
        return runStored(id, null, audio, audioSize)
    }

    private fun runStored(
        id: String,
        text: ByteArray?,
        audio: ParcelFileDescriptor?,
        audioSize: Long,
    ): ScriptRunOutcome {
        val run = Active(id)
        active = run
        try {
            return runActive(run, text, audio, audioSize)
        } finally {
            if (active === run) active = null
        }
    }

    private fun runActive(
        run: Active,
        text: ByteArray?,
        audio: ParcelFileDescriptor?,
        audioSize: Long,
    ): ScriptRunOutcome {
        val id = run.id
        val source =
            store.source(id) ?: return ScriptRunOutcome.Failed(
                "unavailable",
                "no script " + id,
                0,
            )
        val header =
            when (val parsed = HeaderParser.parse(context, source)) {
                is ScriptHeader.Parsed -> parsed
                is ScriptHeader.Refused -> return ScriptRunOutcome.Failed(parsed.code, parsed.message, parsed.line)
            }
        if (audio != null) {
            ScriptAudioInput.refusal(header.needsAudio, audioSize)?.let { return it }
        }
        val settings = store.settingsOf(id, header.settings)
        // The user's own service: hosts typed into url settings are reachable under the shared rule.
        val settingUrls =
            header.settings
                .filter { it.kind == ScriptSettingKind.URL && !it.secret }
                .mapNotNull { settings[it.key]?.takeIf(String::isNotBlank) }
        val transport =
            object : ScriptTransport.Stub() {
                override fun fetch(
                    url: String,
                    method: String,
                    headersJson: String?,
                    bodyFd: ParcelFileDescriptor?,
                    timeoutMillis: Long,
                    callback: ScriptTransportCallback,
                ) {
                    val reply =
                        runCatching {
                            val body = bodyFd?.let(::readBody)
                            ScriptNetworkTransport.fetch(
                                url,
                                method,
                                headersJson,
                                body,
                                header.connects,
                                settingUrls,
                                ScriptLimits.RESPONSE_BYTES,
                                timeoutMillis,
                                onConnection = { run.connection = it },
                                cancelled = { run.cancelled },
                            )
                        }.getOrElse { error ->
                            ProxyTransport.Reply(
                                false,
                                0,
                                null,
                                null,
                                null,
                                null,
                                error.javaClass.simpleName + ": " + (error.message ?: ""),
                            )
                        }
                    run.connection = null
                    callback.onResult(
                        reply.accepted,
                        reply.status,
                        reply.body,
                        reply.contentType,
                        reply.finalUrl,
                        reply.headersJson,
                        reply.error,
                    )
                }
            }
        activeTransport = transport
        val answer =
            try {
                // The run's own duration (its @timeout, or the shipped one) plus room to start.
                val wait = ScriptLimits.durationMillis(header.timeoutSeconds) + ISOLATED_SLACK_MS
                IsolatedCall.call(context, wait) { service, callback ->
                    service.runScript(
                        source,
                        settingsToJson(settings).toByteArray(Charsets.UTF_8),
                        text,
                        audio,
                        header.connects,
                        settingUrls,
                        transport,
                        callback,
                    )
                    // One-way calls on one binder arrive in order, so a cancel sent now (or one that
                    // came in while the run was being sent) lands after the run has started.
                    run.isolated = service
                    if (run.cancelled) service.cancelRun()
                }
            } finally {
                activeTransport = null
                run.isolated = null
            }
        if (answer == null) {
            return ScriptRunOutcome.Failed("unavailable", "the isolated service did not answer", 0)
        }
        if (run.cancelled || answer.first == 125) {
            return ScriptRunOutcome.Cancelled
        }
        if (answer.second.isEmpty()) {
            Log.w(TAG, "script run exited " + answer.first + " without a report: " + answer.third)
        }
        return outcomeFromReport(answer.second)
    }

    /** Reads a request body the isolated process wrote, bounded by the engine's heap: a body
     *  cannot be larger than what the script could hold. */
    private fun readBody(descriptor: ParcelFileDescriptor): ByteArray =
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                check(out.size() <= ScriptLimits.HEAP_BYTES) { "the request body is too large" }
            }
            out.toByteArray()
        }

    private fun settingsToJson(settings: Map<String, String>): String {
        if (settings.isEmpty()) return "{}"
        return settings.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
            jsonString(key) + ":" + jsonString(value)
        }
    }

    private fun jsonString(text: String): String {
        val builder = StringBuilder()
        builder.append('"')
        for (character in text) {
            when (character) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else ->
                    if (character.code < 0x20) {
                        builder.append(String.format("\\u%04x", character.code))
                    } else {
                        builder.append(character)
                    }
            }
        }
        return builder.append('"').toString()
    }

    companion object {
        /** Room for binding the isolated process and starting the engine beyond the run's duration. */
        private const val ISOLATED_SLACK_MS = 30_000L

        private const val TAG = "SongyanScript"

        /** Maps the run report: ok -> outputs/logs, ok:false -> the contract error code. */
        fun outcomeFromReport(report: String): ScriptRunOutcome {
            if (report.isEmpty()) {
                return ScriptRunOutcome.Failed("unavailable", "the run returned nothing", 0)
            }
            val root = Json.parseToJsonElement(report).jsonObject
            if (root["ok"]?.jsonPrimitive?.contentOrNull == "true") {
                return ScriptRunOutcome.Success(
                    outputs =
                        (root["outputs"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList()))
                            .map { it.jsonPrimitive.content },
                    consoleLines =
                        (root["logs"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList()))
                            .map { it.jsonPrimitive.content },
                    consoleTruncated = root["logsTruncated"]?.jsonPrimitive?.contentOrNull == "true",
                )
            }
            return ScriptRunOutcome.Failed(
                code = root["error"]?.jsonPrimitive?.contentOrNull ?: "script",
                message = root["message"]?.jsonPrimitive?.contentOrNull ?: "",
                line = (root["line"]?.jsonPrimitive?.contentOrNull ?: "0").toInt(),
            )
        }
    }
}
