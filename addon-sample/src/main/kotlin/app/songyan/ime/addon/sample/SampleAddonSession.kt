// SPDX-License-Identifier: Apache-2.0
package app.songyan.ime.addon.sample

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import app.songyan.ime.addon.AddonFailure
import app.songyan.ime.addon.AddonFailureCode
import app.songyan.ime.addon.AddonHost
import app.songyan.ime.addon.AddonPanelContext
import app.songyan.ime.addon.AddonPanelHost
import app.songyan.ime.addon.AddonToolDescriptor
import app.songyan.ime.addon.AddonVoiceState
import app.songyan.ime.addon.SongyanAddonSession
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One keyboard session. Calls arrive on Binder threads, so the panel work is posted to the
 * addon's main thread; the text tools run where they are fast enough and the sample's delayed
 * tool uses its own worker so the Binder thread is never blocked.
 */
internal class SampleAddonSession(
    private val context: Context,
    private val host: AddonHost,
    private val tools: List<AddonToolDescriptor>,
) : SongyanAddonSession {
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val closed = AtomicBoolean(false)
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    private var panel: SamplePanel? = null
    private var panelHost: AddonPanelHost? = null
    private var panelContext: AddonPanelContext? = null

    override fun tools(): List<AddonToolDescriptor> = tools

    override fun runTextTool(
        requestId: String,
        tool: AddonToolDescriptor,
        text: String,
    ) {
        when (tool.id) {
            SampleAddon.TOOL_UPPERCASE -> finish(requestId, listOf(text.uppercase(Locale.ROOT)))
            SampleAddon.TOOL_BOOK_TITLE -> finish(requestId, listOf("《" + text + "》"))
            SampleAddon.TOOL_DELAY ->
                worker.execute {
                    Thread.sleep(2_000)
                    finish(requestId, listOf("delayed: " + text))
                }
            // The crash tool exists for the "addon dies while the keyboard keeps working" case.
            SampleAddon.TOOL_CRASH -> android.os.Process.killProcess(android.os.Process.myPid())
            else ->
                host.onToolFailed(
                    requestId,
                    AddonFailure(AddonFailureCode.NOT_SUPPORTED, "unknown tool: " + tool.id),
                )
        }
    }

    private fun finish(
        requestId: String,
        results: List<String>,
    ) {
        if (cancelled.remove(requestId)) return
        if (closed.get()) return
        host.onToolResults(requestId, results)
    }

    override fun cancelRun(requestId: String) {
        cancelled.add(requestId)
    }

    /**
     * A real voice addon records with AudioRecord here (RECORD_AUDIO permission, the keyboard's
     * foreground capabilities travel with the binding — see the device probe). The sample emits a
     * fixed transcription so the host flow can be exercised without a microphone.
     */
    override fun startVoice(requestId: String) {
        host.onVoiceState(AddonVoiceState.LISTENING)
        mainHandler.postDelayed(
            {
                if (!closed.get()) {
                    host.onVoiceText(context.getString(R.string.voice_partial), false)
                    host.onVoiceState(AddonVoiceState.PROCESSING)
                }
            },
            120,
        )
        mainHandler.postDelayed(
            {
                if (!closed.get()) {
                    host.onVoiceText(context.getString(R.string.voice_final), true)
                    host.onVoiceState(AddonVoiceState.FINISHED)
                }
            },
            350,
        )
    }

    override fun stopVoice() {
        mainHandler.post {
            if (!closed.get()) {
                host.onVoiceText(context.getString(R.string.voice_final), true)
                host.onVoiceState(AddonVoiceState.FINISHED)
            }
        }
    }

    override fun openPanel(
        requestId: String,
        context: AddonPanelContext,
        panelHost: AddonPanelHost,
    ) {
        this.panelContext = context
        this.panelHost = panelHost
        mainHandler.post { showPanel(requestId, context, panelHost) }
    }

    private fun showPanel(
        requestId: String,
        panelContext: AddonPanelContext,
        panelHost: AddonPanelHost,
    ) {
        val token = panelContext.hostWindowToken
        if (token == null) {
            host.onToolFailed(requestId, AddonFailure(AddonFailureCode.INVALID_REQUEST, "no host window token"))
            return
        }
        val displayManager = context.getSystemService(DisplayManager::class.java)
        val display =
            displayManager.getDisplay(panelContext.displayId) ?: displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        if (display == null) {
            host.onToolFailed(requestId, AddonFailure(AddonFailureCode.INVALID_REQUEST, "no display"))
            return
        }
        val width = panelContext.widthPx.coerceAtLeast(1)
        val height = panelContext.heightPx.coerceAtLeast(1)
        runCatching {
            val surfaceHost = android.view.SurfaceControlViewHost(context, display, token)
            val view = SamplePanelView(context, panelHost, panelContext)
            surfaceHost.setView(view, width, height)
            panel = SamplePanel(surfaceHost, view)
            panelHost.onSurfaceReady(surfaceHost.surfacePackage ?: error("no surface package"), width, height)
        }.onFailure { failure ->
            Log.w(TAG, "panel failed", failure)
            host.onToolFailed(requestId, AddonFailure(AddonFailureCode.TOOL_ERROR, failure.message))
        }
    }

    override fun updatePanelContext(context: AddonPanelContext) {
        panelContext = context
        val width = context.widthPx.coerceAtLeast(1)
        val height = context.heightPx.coerceAtLeast(1)
        panel?.surfaceHost?.relayout(width, height)
    }

    override fun closePanel() {
        mainHandler.post {
            panel?.release()
            panel = null
        }
    }

    override fun close() {
        closed.set(true)
        worker.shutdownNow()
        mainHandler.post {
            panel?.release()
            panel = null
        }
        panelHost = null
    }

    private companion object {
        const val TAG = "SampleAddon"
    }
}

/** Keeps the surface host and the panel view together so both are released on close. */
internal class SamplePanel(
    val surfaceHost: android.view.SurfaceControlViewHost,
    val view: SamplePanelView,
) {
    fun release() {
        runCatching { surfaceHost.release() }
    }
}
