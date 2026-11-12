// SPDX-License-Identifier: GPL-3.0-only
package app.songyan.ime.addons

import android.content.ComponentName
import android.content.Context
import app.songyan.ime.addon.AddonConnectResult
import app.songyan.ime.addon.AddonHandshake
import app.songyan.ime.addon.AddonHostClient
import app.songyan.ime.addon.AddonHostInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What one bind-and-negotiate attempt learned about an extension. */
internal sealed interface AddonNegotiation {
    /** The service answered; an incompatible answer carries protocolVersion 0 and the reason. */
    data class Answered(
        val handshake: AddonHandshake,
    ) : AddonNegotiation

    /** No answer: not installed any more, refused the caller, crashed, or timed out. */
    data class Unavailable(
        val reason: String,
    ) : AddonNegotiation
}

internal fun interface AddonNegotiator {
    suspend fun negotiate(
        facts: AddonPackageFacts,
        hostInfo: AddonHostInfo,
    ): AddonNegotiation
}

/**
 * Binds to the extension's explicit service component, asks for the handshake and closes the
 * connection again. The 扩展 page and every enable go through here; the keyboard only binds when a
 * tool actually runs, so opening the page is the only time an idle extension is woken.
 */
internal class AddonHostNegotiator(
    private val context: Context,
) : AddonNegotiator {
    override suspend fun negotiate(
        facts: AddonPackageFacts,
        hostInfo: AddonHostInfo,
    ): AddonNegotiation =
        withContext(Dispatchers.IO) {
            val client = AddonHostClient(context)
            try {
                when (
                    val result =
                        client.connect(
                            component = ComponentName(facts.packageName, facts.serviceClassName),
                            hostInfo = hostInfo,
                            listener = object : AddonHostClient.Listener {},
                            bindFlags = Context.BIND_AUTO_CREATE,
                            timeoutMillis = NEGOTIATE_TIMEOUT_MS,
                        )
                ) {
                    is AddonConnectResult.Connected -> AddonNegotiation.Answered(result.handshake)
                    is AddonConnectResult.Incompatible -> AddonNegotiation.Answered(result.handshake)
                    is AddonConnectResult.Unavailable -> AddonNegotiation.Unavailable(result.reason)
                }
            } catch (failure: Throwable) {
                AddonNegotiation.Unavailable(failure.message ?: "the extension did not answer")
            } finally {
                client.disconnect()
            }
        }

    private companion object {
        /** Short: an extension that cannot answer in this time must not stall the settings page. */
        const val NEGOTIATE_TIMEOUT_MS = 2_000L
    }
}
