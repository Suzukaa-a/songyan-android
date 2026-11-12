// SPDX-License-Identifier: Apache-2.0
package app.songyan.ime.addon;

import android.os.Bundle;
import app.songyan.ime.addon.ISongyanAddonPanelHost;

/**
 * One addon session, bound to one keyboard session. Append-only like ISongyanAddonService: the
 * methods below stay in this order, new methods go last, and new data travels as optional Bundle
 * keys.
 */
interface ISongyanAddonSession {
    /** The text tools this addon currently offers; one AddonToolDescriptor bundle per tool. */
    List<Bundle> tools();

    /**
     * Runs one text tool. requestId is the run identity the host issued and echoed in the callback
     * that carries the result; request carries the input under "text". Results arrive through
     * ISongyanAddonHost.onToolResults / onToolFailed.
     */
    void runTextTool(String requestId, String toolId, in Bundle request);

    /** Cancels one run; the addon must not report a result for that request afterwards. */
    void cancelRun(String requestId);

    /** Starts the addon's own voice input flow; results arrive as onVoiceText / onVoiceState. */
    void startVoice(String requestId, in Bundle options);

    /** Stops the voice flow and lets it finish; the last onVoiceText carries isFinal = true. */
    void stopVoice();

    /**
     * Shows the addon's own panel inside the keyboard window. context carries the host window
     * token, the display, the available size and the editor presentation; panelHost is the small
     * set of editor operations the panel may use. The addon reports its surface through
     * ISongyanAddonPanelHost.onSurfaceReady.
     */
    void openPanel(String requestId, in Bundle context, ISongyanAddonPanelHost panelHost);

    /** The available size, insets, editor type or colors changed while the panel is open. */
    void updatePanelContext(in Bundle context);

    /** The host hides the panel (keyboard closed, editor changed, user navigated away). */
    void closePanel();

    /** The keyboard session ended; drop per-session state. */
    void close();
}
