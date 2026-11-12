// SPDX-License-Identifier: GPL-3.0-only
package app.songyan.ime.addons

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import app.songyan.ime.addon.AddonBinding
import app.songyan.ime.addon.AddonCapability
import app.songyan.ime.addon.AddonConnectResult
import app.songyan.ime.addon.AddonFailure
import app.songyan.ime.addon.AddonHostClient
import app.songyan.ime.addon.AddonHostInfo
import app.songyan.ime.addon.AddonToolDescriptor
import app.songyan.ime.ime.tools.ToolCatalog
import app.songyan.ime.ime.tools.ToolExecutor
import app.songyan.ime.tools.ToolCapability
import app.songyan.ime.tools.ToolDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Tool ids of extension tools, namespaced so they cannot collide with a script's. */
internal object AddonToolIds {
    const val PREFIX: String = "addon:"

    fun of(
        packageName: String,
        toolId: String,
    ): String = PREFIX + packageName + ":" + toolId

    /** Splits [id] back into the extension package and its own tool id. */
    fun parse(id: String): Pair<String, String>? {
        if (!id.startsWith(PREFIX)) return null
        val rest = id.removePrefix(PREFIX)
        val separator = rest.indexOf(':')
        if (separator <= 0 || separator == rest.length - 1) return null
        return rest.substring(0, separator) to rest.substring(separator + 1)
    }
}

/** A failed extension tool run; the message is diagnostic and the panel keeps its own wording. */
internal class AddonToolException(
    message: String,
) : IllegalStateException(message)

/**
 * The text tools of enabled, compatible extensions, as the keyboard's tool list sees them. Only the
 * cached handshake is read here: opening the tool list binds nothing and starts no process.
 */
internal class AddonToolCatalog(
    private val registry: AddonRegistry,
) : ToolCatalog {
    override fun tools(): List<ToolDescriptor> =
        registry.enabledCompatible().flatMap { entry ->
            entry.tools.mapNotNull { tool ->
                if (!isTextTool(tool)) return@mapNotNull null
                ToolDescriptor(
                    id = AddonToolIds.of(entry.packageName, tool.id),
                    // The extension's name keeps two tools with the same title apart in the panel.
                    title = entry.facts.label.ifBlank { entry.packageName } + " · " + tool.title,
                    requiredCapabilities = setOf(ToolCapability.TEXT),
                )
            }
        }

    /** Audio and panel entries have their own access points; this list is the text pipeline only. */
    private fun isTextTool(tool: AddonToolDescriptor): Boolean =
        AddonCapability.TEXT in tool.requiredCapabilities &&
            AddonCapability.AUDIO !in tool.requiredCapabilities &&
            AddonCapability.PANEL !in tool.requiredCapabilities
}

/** Runs one extension text tool through a fresh bind, as the keyboard's tool pipeline asks. */
internal class AddonToolExecutor(
    private val context: Context,
    private val registry: AddonRegistry,
    private val timeoutMillis: Long,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ToolExecutor {
    override suspend fun run(
        tool: ToolDescriptor,
        input: String,
    ): List<String> =
        withContext(ioDispatcher) {
            val (packageName, toolId) =
                AddonToolIds.parse(tool.id) ?: throw AddonToolException("unknown extension tool: " + tool.id)
            val entry =
                registry.enabledCompatible().firstOrNull { it.packageName == packageName }
                    ?: throw AddonToolException("the extension is disabled or incompatible: " + packageName)
            if (entry.tools.none { it.id == toolId }) {
                throw AddonToolException("the extension no longer offers this tool: " + toolId)
            }
            val startedAt = SystemClock.elapsedRealtime()
            val requestId = "addon-" + System.nanoTime()
            val outcome = CompletableDeferred<Outcome>()
            val client = AddonHostClient(context)
            var failure: String? = null
            try {
                val result =
                    withTimeoutOrNull(timeoutMillis) {
                        run(client, entry, toolId, input, requestId, outcome)
                    }
                when (result) {
                    is Outcome.Success -> result.results
                    is Outcome.Failed -> {
                        failure = result.message
                        throw AddonToolException(result.message)
                    }
                    null, Outcome.TimedOut -> {
                        failure = "timed out"
                        throw AddonToolException("the extension did not answer in time")
                    }
                }
            } catch (cancelled: CancellationException) {
                failure = "cancelled"
                throw cancelled
            } catch (failureThrown: Throwable) {
                if (failure == null) failure = failureThrown.message ?: "failed"
                throw failureThrown
            } finally {
                client.disconnect()
                registry.recordCall(
                    packageName = packageName,
                    kind = AddonCallKinds.TEXT,
                    durationMs = SystemClock.elapsedRealtime() - startedAt,
                    error = failure,
                )
            }
        }

    private suspend fun run(
        client: AddonHostClient,
        entry: AddonEntry,
        toolId: String,
        input: String,
        requestId: String,
        outcome: CompletableDeferred<Outcome>,
    ): Outcome {
        val listener = listenerFor(requestId, outcome)
        val component = ComponentName(entry.facts.packageName, entry.facts.serviceClassName)
        var connection =
            client.connect(
                component = component,
                hostInfo = AddonHostInfo.forHost(context),
                listener = listener,
                bindFlags = AddonBinding.IME_FLAGS,
                timeoutMillis = CONNECT_TIMEOUT_MS,
            )
        if (connection is AddonConnectResult.Unavailable) {
            // An extension the system is still restarting (it crashed a moment ago) refuses the
            // first bind; one short retry keeps a crashed extension from failing the next run too.
            delay(RESTART_RETRY_DELAY_MS)
            connection =
                client.connect(
                    component = component,
                    hostInfo = AddonHostInfo.forHost(context),
                    listener = listener,
                    bindFlags = AddonBinding.IME_FLAGS,
                    timeoutMillis = CONNECT_TIMEOUT_MS,
                )
        }
        if (connection !is AddonConnectResult.Connected) {
            return Outcome.Failed(
                when (connection) {
                    is AddonConnectResult.Incompatible -> "incompatible protocol version"
                    is AddonConnectResult.Unavailable -> connection.reason
                    is AddonConnectResult.Connected -> error("unreachable")
                },
            )
        }
        if (!client.runTextTool(requestId, toolId, input)) {
            return Outcome.Failed("the extension refused the call")
        }
        return outcome.await()
    }

    private fun listenerFor(
        expectedRequestId: String,
        outcome: CompletableDeferred<Outcome>,
    ): AddonHostClient.Listener =
        object : AddonHostClient.Listener {
            override fun onToolResults(
                requestId: String,
                results: List<String>,
            ) {
                if (requestId == expectedRequestId) outcome.complete(Outcome.Success(results))
            }

            override fun onToolFailed(
                requestId: String,
                failure: AddonFailure,
            ) {
                if (requestId == expectedRequestId) {
                    val message = failure.message
                    outcome.complete(
                        Outcome.Failed(
                            if (message.isNullOrBlank()) {
                                failure.code.wireName
                            } else {
                                failure.code.wireName +
                                    ": " +
                                    message
                            },
                        ),
                    )
                }
            }

            override fun onDisconnected() {
                outcome.complete(Outcome.Failed("the extension process went away"))
            }
        }

    internal sealed interface Outcome {
        data class Success(
            val results: List<String>,
        ) : Outcome

        data class Failed(
            val message: String,
        ) : Outcome

        data object TimedOut : Outcome
    }

    private companion object {
        /** Binding an installed service is fast; anything slower is a broken extension. */
        const val CONNECT_TIMEOUT_MS = 1_500L

        /** A process the system is still restarting after a crash needs a moment before it binds. */
        const val RESTART_RETRY_DELAY_MS = 250L
    }
}

/** The script tools and the extension tools behind one entry, as the keyboard expects one list. */
internal class CombinedToolCatalog(
    private val catalogs: List<ToolCatalog>,
) : ToolCatalog {
    override fun tools(): List<ToolDescriptor> = catalogs.flatMap { it.tools() }
}

/** Dispatches by tool-id namespace: scripts keep theirs, extensions answer for `addon:`. */
internal class CombinedToolExecutor(
    private val scriptExecutor: ToolExecutor,
    private val addonExecutor: ToolExecutor,
) : ToolExecutor {
    override suspend fun run(
        tool: ToolDescriptor,
        input: String,
    ): List<String> =
        if (tool.id.startsWith(AddonToolIds.PREFIX)) {
            addonExecutor.run(tool, input)
        } else {
            scriptExecutor.run(tool, input)
        }
}
