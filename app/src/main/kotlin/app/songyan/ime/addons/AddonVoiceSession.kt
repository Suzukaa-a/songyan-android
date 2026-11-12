// SPDX-License-Identifier: GPL-3.0-only
package app.songyan.ime.addons

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.songyan.ime.addon.AddonBinding
import app.songyan.ime.addon.AddonConnectResult
import app.songyan.ime.addon.AddonHostClient
import app.songyan.ime.addon.AddonHostInfo
import app.songyan.ime.addon.AddonVoiceState
import app.songyan.ime.ime.voice.AddonVoiceListener
import app.songyan.ime.ime.voice.AddonVoiceProvider
import app.songyan.ime.ime.voice.VoiceInputProblem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * One extension voice flow: the extension opens its own microphone and recognizes speech in its
 * process, Songyan binds only while the flow runs, shows the keyboard state from the callbacks and
 * commits the final text. A missing service, a crash or a flow that never answers becomes a typed
 * failure; nothing here throws into the keyboard.
 */
internal class AddonVoiceSession(
    private val context: Context,
    private val registry: AddonRegistry,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var active: ActiveVoice? = null

    private class ActiveVoice(
        val packageName: String,
        val client: AddonHostClient,
        val listener: AddonVoiceListener,
        val startedAt: Long,
    ) {
        var failure: String? = null
        var finished: Boolean = false
        var watchdog: Runnable? = null
    }

    /** The provider with [id] ("addon:<package>") when the extension is enabled and offers voice. */
    fun provider(id: String): AddonVoiceProvider? {
        val packageName = id.removePrefix(AddonVoiceIds.PREFIX)
        val entry =
            registry.enabledCompatible().firstOrNull {
                it.packageName == packageName && it.handshake?.supportsVoice == true
            } ?: return null
        return AddonVoiceProvider(id, entry.facts.label.ifBlank { entry.packageName })
    }

    /** Starts the extension flow; null means started, otherwise why it could not start. */
    fun start(
        providerId: String,
        listener: AddonVoiceListener,
    ): VoiceInputProblem? {
        if (active != null) return VoiceInputProblem.DEVICE_BUSY
        val packageName = providerId.removePrefix(AddonVoiceIds.PREFIX)
        val entry =
            registry.enabledCompatible().firstOrNull {
                it.packageName == packageName && it.handshake?.supportsVoice == true
            } ?: return VoiceInputProblem.SCRIPT_FAILED
        val client = AddonHostClient(context)
        val voice = ActiveVoice(packageName, client, listener, SystemClock.elapsedRealtime())
        active = voice
        scope.launch {
            val connection =
                client.connect(
                    component = ComponentName(entry.facts.packageName, entry.facts.serviceClassName),
                    hostInfo = AddonHostInfo.forHost(context),
                    listener = listenerFor(voice),
                    bindFlags = AddonBinding.IME_FLAGS,
                    timeoutMillis = CONNECT_TIMEOUT_MS,
                )
            if (connection !is AddonConnectResult.Connected) {
                voice.failure =
                    when (connection) {
                        is AddonConnectResult.Incompatible -> "incompatible protocol version"
                        is AddonConnectResult.Unavailable -> connection.reason
                        is AddonConnectResult.Connected -> error("unreachable")
                    }
                handler.post { fail(voice, VoiceInputProblem.SCRIPT_FAILED) }
                return@launch
            }
            val requestId = "voice-" + System.nanoTime()
            val started = runCatching { client.startVoice(requestId) }.getOrDefault(false)
            if (!started) {
                voice.failure = "the extension refused voice input"
                handler.post { fail(voice, VoiceInputProblem.SCRIPT_FAILED) }
                return@launch
            }
            // The flow may run as long as one local take; after stop it gets a short flush window.
            handler.post { armWatchdog(voice, MAX_FLOW_TIMEOUT_MS) }
        }
        return null
    }

    /** Asks the flow to finish; the extension then reports the final text. */
    fun stop() {
        val voice = active ?: return
        // After stop the final text is already recognized in the extension; five seconds covers a
        // slow IPC round trip and its recognizer flush, and the keyboard is showing "transcribing"
        // meanwhile, so a shorter wait risks declaring a slow but working extension dead.
        armWatchdog(voice, STOP_TIMEOUT_MS)
        scope.launch { runCatching { voice.client.stopVoice() } }
    }

    /** Drops the flow: the editor or the keyboard window changed. */
    fun cancel() {
        val voice = active ?: return
        finish(voice, error = null, cancelled = true)
    }

    private fun listenerFor(voice: ActiveVoice): AddonHostClient.Listener =
        object : AddonHostClient.Listener {
            override fun onVoiceText(
                text: String,
                isFinal: Boolean,
            ) {
                handler.post {
                    if (active !== voice || voice.finished) return@post
                    if (isFinal) {
                        voice.listener.onText(text, true)
                        finish(voice, error = null, cancelled = false)
                    } else {
                        voice.listener.onText(text, false)
                    }
                }
            }

            override fun onVoiceState(state: AddonVoiceState) {
                handler.post {
                    if (active !== voice || voice.finished) return@post
                    if (state == AddonVoiceState.FINISHED) {
                        // The extension may close the flow without a text line: time it out quickly so
                        // the toolbar never stays on "transcribing".
                        voice.listener.onText("", true)
                        finish(voice, error = null, cancelled = false)
                    }
                }
            }

            override fun onDisconnected() {
                handler.post {
                    if (active !== voice || voice.finished) return@post
                    voice.failure = "the extension process went away"
                    fail(voice, VoiceInputProblem.SCRIPT_FAILED)
                }
            }

            override fun onWarning(message: String) = Unit
        }

    private fun fail(
        voice: ActiveVoice,
        problem: VoiceInputProblem,
    ) {
        if (active !== voice || voice.finished) return
        voice.listener.onFailed(problem)
        finish(voice, error = voice.failure ?: "voice input failed", cancelled = false)
    }

    private fun armWatchdog(
        voice: ActiveVoice,
        delayMs: Long,
    ) {
        if (active !== voice) return
        voice.watchdog?.let(handler::removeCallbacks)
        val watchdog =
            Runnable {
                if (active !== voice || voice.finished) return@Runnable
                voice.failure = "voice input timed out"
                fail(voice, VoiceInputProblem.SCRIPT_FAILED)
            }
        voice.watchdog = watchdog
        handler.postDelayed(watchdog, delayMs)
    }

    private fun finish(
        voice: ActiveVoice,
        error: String?,
        cancelled: Boolean,
    ) {
        if (voice.finished) return
        voice.finished = true
        voice.watchdog?.let(handler::removeCallbacks)
        voice.watchdog = null
        if (active === voice) active = null
        runCatching { voice.client.closePanel() }
        scope.launch {
            runCatching { voice.client.disconnect() }
            if (!cancelled) {
                registry.recordCall(
                    packageName = voice.packageName,
                    kind = AddonCallKinds.VOICE,
                    durationMs = SystemClock.elapsedRealtime() - voice.startedAt,
                    error = error,
                )
            }
        }
    }

    /** Releases the flow and the scope; the keyboard view is going away. */
    fun destroy() {
        cancel()
        scope.cancel()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 1_500L

        /** The extension is given this long after stop to report the final text. */
        const val STOP_TIMEOUT_MS = 5_000L

        /** One flow may record as long as a local take; the controller's state is visible meanwhile. */
        const val MAX_FLOW_TIMEOUT_MS = 60_000L
    }
}

/** Ids of extension voice providers: "addon:<package>" in the stored voice preference. */
internal object AddonVoiceIds {
    const val PREFIX: String = "addon:"

    fun of(packageName: String): String = PREFIX + packageName
}
