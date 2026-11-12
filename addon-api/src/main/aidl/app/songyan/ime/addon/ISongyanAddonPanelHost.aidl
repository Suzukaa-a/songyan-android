// SPDX-License-Identifier: Apache-2.0
package app.songyan.ime.addon;

import android.os.Bundle;
import android.view.SurfaceControlViewHost.SurfacePackage;

/**
 * The editor operations an addon panel may use. This is the whole surface a panel gets: it can
 * insert text, delete backwards, close itself and read the selection or the text before the
 * cursor when the user asks for it. Candidates, key events, clipboard history, learning data, the
 * host app's package name and input-scheme switching are not exposed. Synchronous calls run on
 * Binder threads in the keyboard process, so implementations must be thread-safe and quick.
 */
interface ISongyanAddonPanelHost {
    /**
     * Hands the addon's surface to the host, which places it in the keyboard window and gives it
     * the measured size. The addon keeps its SurfaceControlViewHost alive until the panel closes.
     */
    void onSurfaceReady(in SurfacePackage surface, int widthPx, int heightPx);

    /** Inserts text and/or deletes before the cursor, as one edit. request: write.text, write.deleteBefore. */
    Bundle insertText(in Bundle request);

    /** Reads the selection, or the text before the cursor: query.mode, query.maxUnits. */
    Bundle readText(in Bundle query);

    /** The current panel context (editor type, colors, size, insets). */
    Bundle panelContext();

    /** Asks the host to give the panel a different available size; returns the applied context. */
    Bundle requestPanelSize(int widthPx, int heightPx);

    /** The addon closes its own panel; the host is expected to hide it. */
    void closePanel();
}
