package app.songyan.ime.script

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** The proxy's real network transport: applies the address policy, performs the request and
 *  enforces the response-size limit from the injected contract. Bound from
 *  [ScriptProxyService]'s transport binder entry; the isolated process never opens a socket. */
internal object ScriptNetworkTransport {
    const val MAX_REDIRECTS = 3

    fun fetch(
        url: String,
        method: String,
        headersJson: String?,
        body: ByteArray?,
        declaredHosts: List<String>,
        responseLimit: Long,
    ): ProxyTransport.Reply {
        var current = url
        var redirects = 0
        while (true) {
            val decision = ScriptAddressPolicy.authorize(current, declaredHosts)
            if (!decision.allowed) {
                return ProxyTransport.Reply(false, 0, null, null, null, null, decision.reason)
            }
            val reply = perform(current, method, headersJson, body, responseLimit)
            if (reply.accepted && reply.status / 100 == 3 && redirects < MAX_REDIRECTS) {
                val location =
                    reply.headersJson?.let { headers ->
                        runCatching {
                            kotlinx.serialization.json.Json
                                .parseToJsonElement(headers)
                                .jsonObject
                                .entries
                                .firstOrNull { it.key.equals("location", ignoreCase = true) }
                                ?.value
                                ?.jsonPrimitive
                                ?.contentOrNull
                        }.getOrNull()
                    } ?: reply.finalUrl
                if (location != null) {
                    current = URI(current).resolve(location).toString()
                    redirects++
                    continue
                }
            }
            return reply
        }
    }

    private fun perform(
        url: String,
        method: String,
        headersJson: String?,
        body: ByteArray?,
        responseLimit: Long,
    ): ProxyTransport.Reply {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = method.uppercase()
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            headersJson?.let { headers ->
                runCatching {
                    kotlinx.serialization.json.Json
                        .parseToJsonElement(headers)
                        .jsonObject
                }.getOrNull()?.forEach { (name, value) ->
                    connection.setRequestProperty(name, value.jsonPrimitive.content)
                }
            }
            if (body != null && body.isNotEmpty()) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val headers = StringBuilder("{")
            var first = true
            for ((name, valuess) in connection.headerFields) {
                if (name == null) continue
                for (value in valuess) {
                    if (!first) headers.append(",")
                    first = false
                    headers.append(
                        String.format(
                            "\"%s\":\"%s\"",
                            name.replace("\"", ""),
                            value.replace("\\", "\\\\").replace("\"", ""),
                        ),
                    )
                }
            }
            headers.append("}")
            val stream = if (status / 100 == 2) connection.inputStream else connection.errorStream
            val collected =
                stream?.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (total <= responseLimit) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        total += read
                        if (total > responseLimit) {
                            return ProxyTransport.Reply(
                                false,
                                status,
                                null,
                                null,
                                null,
                                null,
                                "the response exceeds the injected limit",
                            )
                        }
                    }
                    out.toByteArray()
                }
            ProxyTransport.Reply(
                accepted = true,
                status = status,
                body = collected,
                contentType = connection.contentType,
                finalUrl = connection.url.toString(),
                headersJson = headers.toString(),
                error = null,
            )
        } catch (error: Throwable) {
            ProxyTransport.Reply(
                false,
                0,
                null,
                null,
                null,
                null,
                error.message ?: error.javaClass.simpleName,
            )
        }
    }
}
