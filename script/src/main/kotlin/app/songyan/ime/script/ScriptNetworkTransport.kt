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
    private const val CONNECT_TIMEOUT_MS = 15_000

    fun fetch(
        url: String,
        method: String,
        headersJson: String?,
        body: ByteArray?,
        declaredHosts: List<String>,
        settingUrls: List<String>,
        responseLimit: Long,
        timeoutMillis: Long,
        onConnection: (HttpURLConnection) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): ProxyTransport.Reply {
        var current = url
        var currentMethod = method
        var currentHeaders = headersJson
        var currentBody = body
        var redirects = 0
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            // A cancel that lands between redirects, or before the first connection, sends nothing.
            if (cancelled()) return ProxyTransport.Reply(false, 0, null, null, null, null, "cancelled")
            val decision = ScriptAddressPolicy.authorize(current, declaredHosts, settingUrls)
            if (!decision.allowed) {
                return ProxyTransport.Reply(false, 0, null, null, null, null, decision.reason)
            }
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return ProxyTransport.Reply(false, 0, null, null, null, null, "the run's time is up")
            val reply = perform(current, currentMethod, currentHeaders, currentBody, responseLimit, left, onConnection)
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
                    val next = URI(current).resolve(location)
                    // Credentials go only to the host the script addressed, never to a redirect target.
                    if (!next.host.equals(URI(current).host, ignoreCase = true)) {
                        currentHeaders = withoutCredentials(currentHeaders)
                    }
                    // 303, and 301/302 after a POST, continue as a GET without the body (RFC 9110 15.4).
                    if (reply.status == 303 || (reply.status in 301..302 && currentMethod.equals("POST", true))) {
                        currentMethod = "GET"
                        currentBody = null
                    }
                    current = next.toString()
                    redirects++
                    continue
                }
            }
            return reply
        }
    }

    private val credentialHeaders = setOf("authorization", "proxy-authorization", "cookie")

    private fun withoutCredentials(headersJson: String?): String? {
        headersJson ?: return null
        val headers =
            runCatching {
                kotlinx.serialization.json.Json
                    .parseToJsonElement(headersJson)
                    .jsonObject
            }.getOrNull() ?: return null
        return kotlinx.serialization.json
            .JsonObject(headers.filterKeys { it.lowercase() !in credentialHeaders })
            .toString()
    }

    private fun perform(
        url: String,
        method: String,
        headersJson: String?,
        body: ByteArray?,
        responseLimit: Long,
        timeoutMillis: Long,
        onConnection: (HttpURLConnection) -> Unit,
    ): ProxyTransport.Reply {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            // Cancelling the run disconnects this connection from another thread.
            onConnection(connection)
            connection.requestMethod = method.uppercase()
            // The script's own duration bounds the request; a slow model may take minutes.
            val timeout = timeoutMillis.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            connection.connectTimeout = minOf(timeout, CONNECT_TIMEOUT_MS)
            connection.readTimeout = timeout
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
            // Repeated header names join with ", " (RFC 9110 5.3); the JSON encoder escapes the rest.
            val headers =
                kotlinx.serialization.json.JsonObject(
                    connection.headerFields
                        .filterKeys { it != null }
                        .mapKeys { it.key.lowercase() }
                        .mapValues { kotlinx.serialization.json.JsonPrimitive(it.value.joinToString(", ")) },
                )
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
