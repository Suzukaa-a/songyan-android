// SPDX-License-Identifier: Apache-2.0
package app.songyan.ime.addon

import android.view.SurfaceControlViewHost

/**
 * What one installed APK implements to be a Songyan addon. The service base class
 * ([SongyanAddonService]) owns the Binder plumbing, the version negotiation and the caller check;
 * an addon only implements this interface and its session.
 *
 * Every method runs on a Binder thread in the addon's own process. Implementations that touch UI
 * post to their own main thread; nothing here may block for long, because the keyboard process is
 * waiting on the other end of the call.
 */
public interface SongyanAddon {
    /** Stable identity of the addon, unique among installed addons. */
    public val addonId: String

    /** User-visible name shown in Songyan's addon list. */
    public val addonName: String

    /** Protocol versions this addon can speak. The host and the addon use the highest common one. */
    public val supportedProtocolVersions: IntRange

    /** Declares tools, capabilities and the optional settings page for the negotiated version. */
    public fun handshake(host: AddonHost): AddonHandshake

    /** Opens the per-keyboard-session state; null when the addon cannot serve a session now. */
    public fun openSession(host: AddonHost): SongyanAddonSession?

    /** The keyboard went away or the process is being reclaimed. Release sessions. */
    public fun onDisconnect(): Unit
}

/** One addon session, matching one keyboard session. */
public interface SongyanAddonSession {
    /** The tools this session offers. The host also receives them in the handshake. */
    public fun tools(): List<AddonToolDescriptor>

    /** Runs one text tool. Report exactly one result or failure for [requestId]. */
    public fun runTextTool(
        requestId: String,
        tool: AddonToolDescriptor,
        text: String,
    ): Unit

    /** Cancels one run. No callback for [requestId] may follow. */
    public fun cancelRun(requestId: String): Unit

    /** Starts the addon's own voice flow; the host shows the state and puts final text on screen. */
    public fun startVoice(requestId: String): Unit

    /** Ends the voice flow; the addon reports its last text with isFinal = true. */
    public fun stopVoice(): Unit

    /**
     * Draws the addon's panel in the keyboard window. The context carries the window token to
     * create the SurfaceControlViewHost against; the surface goes back through
     * [AddonPanelHost.onSurfaceReady]. The panel entirely owns its own view hierarchy.
     */
    public fun openPanel(
        requestId: String,
        context: AddonPanelContext,
        panelHost: AddonPanelHost,
    ): Unit

    /** The available space, insets, editor type or colors changed. */
    public fun updatePanelContext(context: AddonPanelContext): Unit

    /** The host hides the panel; release the view hierarchy and the SurfaceControlViewHost. */
    public fun closePanel(): Unit

    /** The keyboard session ended. */
    public fun close(): Unit
}

/** Host identity and the callbacks an addon may call while serving one session. */
public interface AddonHost {
    public val hostInfo: AddonHostInfo

    public val protocolVersion: Int

    public fun onToolResults(
        requestId: String,
        results: List<String>,
    ): Unit

    public fun onToolFailed(
        requestId: String,
        failure: AddonFailure,
    ): Unit

    public fun onVoiceText(
        text: String,
        isFinal: Boolean,
    ): Unit

    public fun onVoiceState(state: AddonVoiceState): Unit

    /** A non-fatal problem worth showing to the user. */
    public fun onWarning(message: String): Unit
}

/**
 * The editor operations an addon panel may use. Implemented by the host; an addon panel only ever
 * sees this object. The host guarantees fail-closed results: a refused edit answers with an
 * [AddonWriteResult] rather than an exception, and a password field reads as a blocked reason.
 */
public interface AddonPanelHost {
    /** Hands the panel surface to the host, with the size the addon laid the view out at. */
    public fun onSurfaceReady(
        surface: SurfaceControlViewHost.SurfacePackage,
        widthPx: Int,
        heightPx: Int,
    ): Unit

    /** Deletes before the cursor (when requested) and then inserts text, as one edit. */
    public fun insertText(write: AddonTextWrite): AddonWriteResult

    /** Reads the selection or the text before the cursor, capped by the query's maxUnits. */
    public fun readText(query: AddonTextQuery): AddonTextResult

    /** The current panel context; valid even when no update has arrived yet. */
    public fun panelContext(): AddonPanelContext

    /** Asks for a different available size; returns the size the host actually gives. */
    public fun requestPanelSize(
        widthPx: Int,
        heightPx: Int,
    ): AddonPanelContext

    /** Closes the panel from the addon side; the user is back at the keyboard. */
    public fun closePanel(): Unit
}
