package app.songyan.ime.script

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.Os
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/** Runs one stored script through the isolated process on the production path: the C state
 *  machine drives the engine, and fetch travels Binder-to-proxy for the real network. The
 *  canonical JSON result (the same shape the shared CLI prints) maps to [ScriptRunOutcome]. */
internal class ScriptRunner(
    private val context: Context,
    private val store: ScriptStore,
) {
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    /** Keeps the local transport binder alive while the isolated process holds its proxy. */
    private var activeTransport: ScriptTransport.Stub? = null

    fun cancel(id: String) {
        cancelled.add(id)
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
        cancelled.remove(id)
        val source =
            store.source(id) ?: return ScriptRunOutcome.Failed(
                "unavailable",
                "no script " + id,
                0,
            )
        val header =
            HeaderParser.parse(context, source) as? ScriptHeader.Parsed
                ?: return ScriptRunOutcome.Failed("header", "the stored header is refused", 0)
        if (audio != null) {
            ScriptAudioInput.refusal(header.needsAudio, audioSize)?.let { return it }
        }
        val settings = store.settingsOf(id, header.settings)
        val transport =
            object : ScriptTransport.Stub() {
                override fun fetch(
                    url: String,
                    method: String,
                    headersJson: String?,
                    body: ByteArray?,
                    callback: ScriptTransportCallback,
                ) {
                    val reply =
                        runCatching {
                            ScriptNetworkTransport.fetch(
                                url,
                                method,
                                headersJson,
                                body,
                                header.connects,
                                ScriptCaseRun.PROVISIONAL_RESPONSE_BYTES,
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
                IsolatedCall.call(context) { service, callback ->
                    service.runScript(
                        source,
                        settingsToJson(settings).toByteArray(Charsets.UTF_8),
                        text,
                        audio,
                        header.connects,
                        transport,
                        callback,
                    )
                }
            } finally {
                activeTransport = null
            }
        if (answer == null) {
            return ScriptRunOutcome.Failed("unavailable", "the isolated service did not answer", 0)
        }
        if (cancelled.remove(id) || answer.first == 125) {
            return ScriptRunOutcome.Cancelled
        }
        return outcomeFromReport(answer.second)
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
