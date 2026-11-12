// SPDX-License-Identifier: GPL-3.0-only
package app.songyan.ime.addons

import android.content.ComponentName
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Insets
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.SurfaceControlViewHost
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import app.songyan.ime.R
import app.songyan.ime.addon.AddonBinding
import app.songyan.ime.addon.AddonConnectResult
import app.songyan.ime.addon.AddonFailure
import app.songyan.ime.addon.AddonHostClient
import app.songyan.ime.addon.AddonHostInfo
import app.songyan.ime.addon.AddonInsets
import app.songyan.ime.addon.AddonPanelContext
import app.songyan.ime.addon.AddonPanelHost
import app.songyan.ime.addon.AddonReadBlockReason
import app.songyan.ime.addon.AddonTextQuery
import app.songyan.ime.addon.AddonTextResult
import app.songyan.ime.addon.AddonTextScope
import app.songyan.ime.addon.AddonTextWrite
import app.songyan.ime.addon.AddonWriteBlockReason
import app.songyan.ime.addon.AddonWriteResult
import app.songyan.ime.ime.KeyboardPalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Hosts one extension's own panel inside the real keyboard window.
 *
 * The keyboard view is hidden while the panel is shown and a [SurfaceView] takes its place at the
 * same size, so the IME window keeps the height the user already had. The extension draws into a
 * SurfaceControlViewHost of its own process and hands the package back; the host attaches it with
 * [SurfaceView.setChildSurfacePackage]. The SurfaceView must be above its host surface
 * ([SurfaceView.setZOrderOnTop]) or the embedded hierarchy renders but receives no touches — a
 * platform fact measured on API 36 by scripts/probes/android-addon.
 *
 * The panel gets exactly the editor operations the protocol allows: insert text, delete backwards,
 * read the selection or the text before the cursor, ask for a new size and close itself. Nothing
 * here throws into the extension: refused edits answer with a typed result.
 */
internal class AddonPanelController(
    private val context: Context,
    private val container: ViewGroup,
    /** The keyboard view hidden while the panel is shown. */
    private val keyboardView: View,
    private val editorAccess: AddonEditorAccess,
    private val editorInfo: () -> EditorInfo?,
    private val registry: AddonRegistry,
    /** True while a panel is on screen; the service registers Back for exactly that time. */
    private val onShownChanged: (Boolean) -> Unit = {},
    /** A panel that failed or timed out after the user asked for it; the service shows the hint. */
    private val onFailed: () -> Unit = {},
) {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var active: ActivePanel? = null

    /**
     * The surface added for an open whose host token has not arrived yet. A close while this is
     * pending has to undo the surface and the hidden keyboard view itself: there is no [ActivePanel]
     * yet, and the token poll must not bind afterwards.
     */
    private var pending: PendingPanel? = null

    /** The loading chip or the failure chip with its retry, drawn over the keyboard. */
    private var overlay: View? = null

    /** The loading chip that is fading out; a close during the fade must remove it at once. */
    private var fadingOverlay: View? = null

    /** Invalidates a host-token poll that outlived the open or close it belonged to. */
    private var generation = 0

    val isShown: Boolean get() = active != null

    /** The extension's surface package is attached; the panel is drawing (tests and probes). */
    internal fun surfaceAttached(): Boolean = active?.surfaceAttached == true

    private class PendingPanel(
        val packageName: String,
        val surfaceView: SurfaceView,
        val startedAt: Long,
        val generation: Int,
    )

    private class ActivePanel(
        val packageName: String,
        val surfaceView: SurfaceView,
        val client: AddonHostClient,
        val startedAt: Long,
        /** The window token of the first context; later context updates keep it. */
        val hostToken: android.os.IBinder,
        var context: AddonPanelContext,
    ) {
        var failure: String? = null

        /** Fires when the addon never hands back its surface package. */
        var watchdog: Runnable? = null

        @Volatile
        var surfaceAttached: Boolean = false
    }

    /** Opens [packageName]'s panel; false when it is not enabled, incompatible or has no panel. */
    fun open(packageName: String): Boolean {
        close()
        val entry =
            registry.enabledCompatible().firstOrNull {
                it.packageName == packageName && it.handshake?.supportsPanel == true
            } ?: return false
        val width = container.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        val height =
            keyboardView.height.takeIf { it > 0 }
                ?: container.height.takeIf { it > 0 }
                ?: (context.resources.displayMetrics.heightPixels / 3)
        val surfaceView = SurfaceView(context)
        surfaceView.setZOrderOnTop(true)
        // Binding and the extension's first frame take a moment. Keep the keyboard visible under a
        // transparent surface until it draws, so the window is never a blank rectangle; the loading
        // chip says what is happening, and a failure or the watchdog shows the reason plus Retry.
        surfaceView.alpha = 0f
        container.addView(surfaceView, panelLayoutParams(width, height))
        showLoadingOverlay()
        val pendingPanel =
            PendingPanel(
                packageName = packageName,
                surfaceView = surfaceView,
                startedAt = SystemClock.elapsedRealtime(),
                generation = ++generation,
            )
        pending = pendingPanel
        // The host token exists only once the SurfaceView is attached and laid out.
        whenHostToken(pendingPanel, remainingAttempts = 40) { token ->
            if (pending !== pendingPanel || generation != pendingPanel.generation) return@whenHostToken
            if (token == null) {
                failPending(pendingPanel, "no host token")
                return@whenHostToken
            }
            bindPending(pendingPanel, entry, token, width, height)
        }
        return true
    }

    private fun bindPending(
        pendingPanel: PendingPanel,
        entry: AddonEntry,
        token: android.os.IBinder,
        width: Int,
        height: Int,
    ) {
        pending = null
        val client = AddonHostClient(context)
        val panel =
            ActivePanel(
                packageName = pendingPanel.packageName,
                surfaceView = pendingPanel.surfaceView,
                client = client,
                startedAt = pendingPanel.startedAt,
                hostToken = token,
                context = currentPanelContext(token, width, height),
            )
        active = panel
        onShownChanged(true)
        // A connected addon may still never hand back its surface, or may block in openPanel:
        // the watchdog closes the panel instead of leaving a blank window under the keyboard's back.
        armSurfaceWatchdog(panel)
        scope.launch {
            val connected =
                client.connect(
                    component = ComponentName(entry.facts.packageName, entry.facts.serviceClassName),
                    hostInfo = AddonHostInfo.forHost(context),
                    listener = listenerFor(panel),
                    bindFlags = AddonBinding.IME_FLAGS,
                    timeoutMillis = CONNECT_TIMEOUT_MS,
                )
            if (connected !is AddonConnectResult.Connected) {
                panel.failure =
                    when (connected) {
                        is AddonConnectResult.Incompatible -> "incompatible protocol version"
                        is AddonConnectResult.Unavailable -> connected.reason
                        is AddonConnectResult.Connected -> error("unreachable")
                    }
                handler.post { closeActive(panel) }
                return@launch
            }
            val requestId = "panel-" + System.nanoTime()
            val opened =
                runCatching {
                    client.openPanel(requestId, panel.context, PanelHost(panel))
                }.getOrDefault(false)
            if (!opened) {
                panel.failure = "the extension refused the panel"
                handler.post { closeActive(panel) }
            }
        }
    }

    /**
     * Undoes an open that failed before a panel existed: the surface leaves the container, the
     * keyboard view comes back, and the failed call is recorded. Without this the window stays
     * blank until the input view is rebuilt.
     */
    private fun failPending(
        pendingPanel: PendingPanel,
        reason: String,
    ) {
        if (pending !== pendingPanel) return
        pending = null
        generation++
        (pendingPanel.surfaceView.parent as? ViewGroup)?.removeView(pendingPanel.surfaceView)
        keyboardView.visibility = View.VISIBLE
        keyboardView.requestLayout()
        recordPanelFailure(pendingPanel.packageName, pendingPanel.startedAt, reason)
        runCatching { showErrorOverlay(pendingPanel.packageName) }
        onFailed()
    }

    /** The layout parameters of the panel surface in the keyboard window's container. */
    private fun panelLayoutParams(
        width: Int,
        height: Int,
    ): ViewGroup.LayoutParams =
        if (container is FrameLayout) {
            FrameLayout.LayoutParams(width, height)
        } else {
            LinearLayout.LayoutParams(width, height)
        }

    /**
     * A small chip over the keyboard while an extension binds: a spinner and "Opening extension…".
     * It does not cover the keys and, in the keyboard's FrameLayout, does not change the window
     * height while the panel loads.
     */
    private data class OverlayColors(
        val background: Int,
        val text: Int,
        val accent: Int,
    )

    private fun overlayColors(): OverlayColors {
        val palette = runCatching { KeyboardPalette.from(context) }.getOrNull()
        return if (palette == null) {
            OverlayColors(android.graphics.Color.DKGRAY, android.graphics.Color.WHITE, android.graphics.Color.WHITE)
        } else {
            OverlayColors(palette.candidateBackground, palette.text, palette.accent)
        }
    }

    private fun showLoadingOverlay() {
        removeOverlay()
        val density = context.resources.displayMetrics.density
        val palette = overlayColors()
        val chip =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(padding(12), padding(8), padding(16), padding(8))
                background = overlayBackground(palette.background)
            }
        val progress =
            ProgressBar(context).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(palette.accent)
            }
        chip.addView(progress, LinearLayout.LayoutParams((18 * density).toInt(), (18 * density).toInt()))
        chip.addView(
            TextView(context).apply {
                setText(R.string.addon_panel_opening)
                setTextColor(palette.text)
                textSize = 13f
            },
            LinearLayout
                .LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = (8 * density).toInt() },
        )
        addOverlay(chip)
    }

    /** A failure chip with the reason and a Retry button, instead of a silent blank panel. */
    private fun showErrorOverlay(packageName: String) {
        removeOverlay()
        val palette = overlayColors()
        val box =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding(16), padding(12), padding(16), padding(8))
                background = overlayBackground(palette.background)
            }
        box.addView(
            TextView(context).apply {
                setText(R.string.addon_panel_failed)
                setTextColor(palette.text)
                textSize = 14f
            },
        )
        box.addView(
            TextView(context).apply {
                setText(R.string.addon_panel_retry)
                setTextColor(palette.accent)
                textSize = 14f
                isClickable = true
                isFocusable = true
                setPadding(0, padding(6), 0, padding(2))
                setOnClickListener {
                    if (!open(packageName)) runCatching { showErrorOverlay(packageName) }
                }
            },
            LinearLayout
                .LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = padding(4) },
        )
        addOverlay(box)
    }

    private fun addOverlay(view: View) {
        overlay = view
        container.addView(view, overlayLayoutParams())
    }

    private fun removeOverlay() {
        removeFadingOverlay()
        val current = overlay ?: return
        overlay = null
        current.animate().cancel()
        (current.parent as? ViewGroup)?.removeView(current)
    }

    private fun removeFadingOverlay() {
        val current = fadingOverlay ?: return
        fadingOverlay = null
        current.animate().cancel()
        (current.parent as? ViewGroup)?.removeView(current)
    }

    /** The first frame is in: the chip goes away while the surface fades in. */
    private fun fadeOutOverlay() {
        val current = overlay ?: return
        overlay = null
        fadingOverlay = current
        val animator = current.animate().alpha(0f).setDuration(PANEL_FADE_MS)
        animator.withEndAction {
            if (fadingOverlay === current) fadingOverlay = null
            (current.parent as? ViewGroup)?.removeView(current)
        }
        animator.start()
    }

    private fun overlayLayoutParams(): ViewGroup.LayoutParams =
        if (container is FrameLayout) {
            FrameLayout
                .LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                ).apply { topMargin = padding(8) }
        } else {
            LinearLayout
                .LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }

    private fun overlayBackground(color: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = padding(18).toFloat()
            setColor(color)
        }

    private fun padding(dp: Int): Int = (dp * context.resources.displayMetrics.density).toInt()

    private fun armSurfaceWatchdog(panel: ActivePanel) {
        if (active !== panel) return
        panel.watchdog?.let(handler::removeCallbacks)
        val watchdog =
            Runnable {
                if (active !== panel || panel.surfaceAttached) return@Runnable
                panel.failure = "the extension surface never arrived"
                closeActive(panel)
            }
        panel.watchdog = watchdog
        handler.postDelayed(watchdog, SURFACE_TIMEOUT_MS)
    }

    /** Closes the panel from the host side (Back, a new editor, the keyboard hiding). */
    fun close(): Boolean {
        val pendingPanel = pending
        var closed = false
        if (pendingPanel != null) {
            pending = null
            generation++
            (pendingPanel.surfaceView.parent as? ViewGroup)?.removeView(pendingPanel.surfaceView)
            keyboardView.visibility = View.VISIBLE
            keyboardView.requestLayout()
            removeOverlay()
            closed = true
        }
        val current = active
        if (current != null) {
            closeActive(current)
            closed = true
        }
        if (!closed) removeOverlay()
        return closed
    }

    /** Re-sends the panel context after a configuration or inset change. */
    fun updatePanelContext() {
        val current = active ?: return
        // The first context carries the host token; updates keep the same token. Passing a null
        // token here would make the addon's later contexts disagree with the first one.
        val updated =
            currentPanelContext(
                hostWindowToken = current.hostToken,
                widthPx = current.surfaceView.width,
                heightPx = current.surfaceView.height,
            )
        current.context = updated
        val client = current.client
        scope.launch { runCatching { client.updatePanelContext(updated) } }
    }

    private fun closeActive(current: ActivePanel) {
        if (active !== current) return
        active = null
        generation++
        current.watchdog?.let(handler::removeCallbacks)
        current.watchdog = null
        // A fade still running must not hide the keyboard after the panel is gone.
        current.surfaceView.animate().cancel()
        current.surfaceView.alpha = 1f
        onShownChanged(false)
        runCatching { current.client.closePanel() }
        scope.launch {
            runCatching { current.client.disconnect() }
            registry.recordCall(
                packageName = current.packageName,
                kind = AddonCallKinds.PANEL,
                durationMs = SystemClock.elapsedRealtime() - current.startedAt,
                error = current.failure,
            )
        }
        (current.surfaceView.parent as? ViewGroup)?.removeView(current.surfaceView)
        keyboardView.visibility = View.VISIBLE
        keyboardView.requestLayout()
        if (current.failure != null) {
            // The failure is explained in the keyboard window itself; a Toast alone disappears
            // before the user can act, and Retry is the way back.
            runCatching { showErrorOverlay(current.packageName) }
            onFailed()
        } else {
            removeOverlay()
        }
    }

    private fun listenerFor(panel: ActivePanel): AddonHostClient.Listener =
        object : AddonHostClient.Listener {
            override fun onDisconnected() {
                panel.failure = "the extension process went away"
                handler.post { closeActive(panel) }
            }

            override fun onWarning(message: String) = Unit

            override fun onToolFailed(
                requestId: String,
                failure: AddonFailure,
            ) {
                panel.failure = failure.code.wireName + ": " + failure.message
                handler.post { closeActive(panel) }
            }
        }

    private fun whenHostToken(
        pendingPanel: PendingPanel,
        remainingAttempts: Int,
        onReady: (android.os.IBinder?) -> Unit,
    ) {
        // A close (or a newer open) replaced the pending panel; this poll has nothing left to do.
        if (pending !== pendingPanel) return
        val token = pendingPanel.surfaceView.hostToken
        if (token != null || remainingAttempts <= 0) {
            onReady(token)
            return
        }
        handler.postDelayed(
            { whenHostToken(pendingPanel, remainingAttempts - 1, onReady) },
            HOST_TOKEN_POLL_MS,
        )
    }

    private fun currentPanelContext(
        hostWindowToken: android.os.IBinder?,
        widthPx: Int,
        heightPx: Int,
    ): AddonPanelContext {
        val metrics = context.resources.displayMetrics
        val configuration = context.resources.configuration
        val insets = insetsOf(container)
        val info = editorInfo()
        return AddonPanelContext(
            hostWindowToken = hostWindowToken,
            displayId = context.display?.displayId ?: Display.DEFAULT_DISPLAY,
            widthPx = widthPx,
            heightPx = heightPx,
            densityDpi = metrics.densityDpi,
            fontScale = configuration.fontScale,
            insets = insets,
            inputType = info?.inputType ?: 0,
            imeOptions = info?.imeOptions ?: 0,
            // The panel follows the keyboard surface, not the system: a dark choice in a light
            // system (and the reverse) must hand the extension the same tone it is painted in.
            darkMode = !KeyboardPalette.from(context).isLight,
            keyboardColor = KeyboardPalette.from(context).key,
        )
    }

    private fun insetsOf(view: View): AddonInsets {
        val windowInsets = view.rootWindowInsets ?: return AddonInsets.ZERO
        return AddonInsets(
            leftPx = windowInsets.insetLeft(),
            topPx = windowInsets.insetTop(),
            rightPx = windowInsets.insetRight(),
            bottomPx = windowInsets.insetBottom(),
        )
    }

    private fun WindowInsets.insetLeft(): Int = bars().left

    private fun WindowInsets.insetTop(): Int = bars().top

    private fun WindowInsets.insetRight(): Int = bars().right

    private fun WindowInsets.insetBottom(): Int = bars().bottom

    private fun WindowInsets.bars(): Insets =
        getInsets(
            WindowInsets.Type.navigationBars() or
                WindowInsets.Type.statusBars() or
                WindowInsets.Type.displayCutout(),
        )

    private fun recordPanelFailure(
        packageName: String,
        startedAt: Long,
        reason: String,
    ) {
        scope.launch {
            registry.recordCall(
                packageName = packageName,
                kind = AddonCallKinds.PANEL,
                durationMs = SystemClock.elapsedRealtime() - startedAt,
                error = reason,
            )
        }
    }

    /** The editor operations the panel may use, answered on the main thread. */
    private inner class PanelHost(
        private val panel: ActivePanel,
    ) : AddonPanelHost {
        override fun onSurfaceReady(
            surface: SurfaceControlViewHost.SurfacePackage,
            widthPx: Int,
            heightPx: Int,
        ) {
            handler.post {
                if (active !== panel) return@post
                runCatching { panel.surfaceView.setChildSurfacePackage(surface) }
                    .onSuccess {
                        panel.surfaceAttached = true
                        panel.watchdog?.let(handler::removeCallbacks)
                        panel.watchdog = null
                        fadeOutOverlay()
                        // The first frame can be black while the extension lays its hierarchy out.
                        // The keyboard stays visible underneath and the surface fades in over it;
                        // only then is the keyboard view hidden, so there is no instant swap or
                        // blank window during the transition.
                        panel.surfaceView.animate().cancel()
                        panel.surfaceView
                            .animate()
                            .alpha(1f)
                            .setDuration(PANEL_FADE_MS)
                            .setInterpolator(DecelerateInterpolator())
                            .withEndAction {
                                if (active === panel) {
                                    keyboardView.visibility = View.GONE
                                    keyboardView.requestLayout()
                                }
                            }.start()
                    }.onFailure { panel.failure = it.message }
            }
        }

        override fun insertText(write: AddonTextWrite): AddonWriteResult =
            onEditor {
                if (!editorAccess.hasEditor) return@onEditor AddonWriteResult(false, AddonWriteBlockReason.NO_EDITOR)
                if (editorAccess.isPassword) return@onEditor AddonWriteResult(false, AddonWriteBlockReason.SECURE_ENTRY)
                if (editorAccess.isComposing) return@onEditor AddonWriteResult(false, AddonWriteBlockReason.NOT_APPLIED)
                if (write.deleteBefore > 0) editorAccess.deleteBefore(write.deleteBefore)
                if (write.text.isNotEmpty()) editorAccess.insert(write.text)
                AddonWriteResult.APPLIED
            } ?: AddonWriteResult(false, AddonWriteBlockReason.NOT_APPLIED)

        override fun readText(query: AddonTextQuery): AddonTextResult =
            read(query) ?: AddonTextResult(blockReason = AddonReadBlockReason.NO_EDITOR)

        override fun panelContext(): AddonPanelContext = panel.context

        override fun requestPanelSize(
            widthPx: Int,
            heightPx: Int,
        ): AddonPanelContext {
            var applied = panel.context
            onMain {
                if (active !== panel) return@onMain
                val metrics = context.resources.displayMetrics
                val width = widthPx.coerceIn(1, metrics.widthPixels)
                val height = heightPx.coerceIn(1, maxOf(1, metrics.heightPixels / 2))
                val params = panel.surfaceView.layoutParams
                params.width = width
                params.height = height
                panel.surfaceView.layoutParams = params
                panel.surfaceView.requestLayout()
                panel.context = currentPanelContext(panel.hostToken, width, height)
                applied = panel.context
            }
            return applied
        }

        override fun closePanel() {
            handler.post { closeActive(panel) }
        }

        private fun read(query: AddonTextQuery): AddonTextResult? =
            onEditor {
                if (!editorAccess.hasEditor) {
                    return@onEditor AddonTextResult(blockReason = AddonReadBlockReason.NO_EDITOR)
                }
                if (editorAccess.isPassword) {
                    return@onEditor AddonTextResult(blockReason = AddonReadBlockReason.SECURE_ENTRY)
                }
                val maxUnits = query.maxUnits.coerceIn(1, MAX_READ_UNITS)
                when (query.mode) {
                    AddonTextScope.SELECTION -> {
                        val range =
                            editorAccess.selection
                                ?: return@onEditor AddonTextResult(blockReason = AddonReadBlockReason.NO_TEXT)
                        if (range.isEmpty()) {
                            return@onEditor AddonTextResult(blockReason = AddonReadBlockReason.NO_TEXT)
                        }
                        val text =
                            editorAccess.selectedText()
                                ?: return@onEditor AddonTextResult(blockReason = AddonReadBlockReason.NO_TEXT)
                        val truncated = text.length > maxUnits
                        AddonTextResult(
                            text = if (truncated) text.substring(0, maxUnits) else text,
                            scope = AddonTextScope.SELECTION,
                            isTruncated = truncated,
                        )
                    }
                    AddonTextScope.BEFORE_CURSOR -> {
                        val text =
                            editorAccess.textBeforeCursor(maxUnits + 1)
                                ?: return@onEditor AddonTextResult(blockReason = AddonReadBlockReason.NO_TEXT)
                        if (text.isEmpty()) {
                            return@onEditor AddonTextResult(blockReason = AddonReadBlockReason.NO_TEXT)
                        }
                        val truncated = text.length > maxUnits
                        AddonTextResult(
                            text = if (truncated) text.takeLast(maxUnits) else text,
                            scope = AddonTextScope.BEFORE_CURSOR,
                            isTruncated = truncated,
                        )
                    }
                }
            }
    }

    /** Runs [block] on the main thread and waits for its answer (Binder callbacks are off-main). */
    private fun <T> onMain(block: () -> T) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        handler.post {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        latch.await(EDITOR_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun <T> onEditor(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: T? = null
        var done = false
        val latch = CountDownLatch(1)
        handler.post {
            try {
                result = block()
                done = true
            } finally {
                latch.countDown()
            }
        }
        latch.await(EDITOR_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return if (done) result else null
    }

    /** Releases everything and stops the background scope; the view is owned by the service. */
    fun destroy() {
        close()
        scope.cancel()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 1_500L
        const val HOST_TOKEN_POLL_MS = 25L

        /** A connected addon gets this long to hand back its surface before the panel is closed. */
        const val SURFACE_TIMEOUT_MS = 4_000L

        /** The panel fades in over the visible keyboard; hiding it at the end avoids a blank swap. */
        const val PANEL_FADE_MS = 180L
        const val EDITOR_TIMEOUT_MS = 750L
        const val MAX_READ_UNITS = 4_000
    }
}

/**
 * What the panel may do to the focused editor, expressed without the keyboard's internal types: the
 * service implements it on top of its EditorConnection and the current InputConnection. Keeping it
 * here lets the panel logic stay testable and keeps the :ime internals inside their module.
 */
internal interface AddonEditorAccess {
    val hasEditor: Boolean

    val isPassword: Boolean

    val isComposing: Boolean

    /** The current selection as an IntRange, or null when the editor cannot report it. */
    val selection: IntRange?

    fun selectedText(): String?

    fun textBeforeCursor(maxUnits: Int): String?

    fun insert(text: String)

    fun deleteBefore(units: Int)
}
