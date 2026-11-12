// SPDX-License-Identifier: Apache-2.0
package app.songyan.ime.addon;

import android.os.Bundle;
import app.songyan.ime.addon.ISongyanAddonHost;
import app.songyan.ime.addon.ISongyanAddonSession;

/**
 * The one service an addon APK publishes so Songyan can bind to it.
 *
 * Songyan discovers addons with a PackageManager query for {@code app.songyan.ime.addon.SERVICE}
 * and binds to the service; it never downloads, installs or loads addon code. The addon service is
 * exported and must verify that the caller is the Songyan keyboard itself (AddonCallerPolicy in
 * the API library implements the check).
 *
 * Compatibility: this interface is append-only. Methods are never removed, reordered or changed;
 * new behavior arrives as new methods plus new optional keys in the {@link Bundle} payloads, and
 * the two sides agree on a protocol version in {@link #negotiate} before anything runs. Every
 * payload is a Bundle so adding a key never needs a Binder interface change.
 */
interface ISongyanAddonService {
    /**
     * Version negotiation and capability handshake. The host sends an AddonHostInfo bundle and the
     * addon answers with an AddonHandshake bundle whose protocolVersion is 0 when the required
     * version is not supported (the host then shows the addon as incompatible and runs nothing).
     */
    Bundle negotiate(in Bundle hostInfo);

    /**
     * Opens one keyboard session. hostInfo is the same AddonHostInfo bundle that was negotiated;
     * the host callbacks come back through the given binder. Returns null when the caller is not
     * the keyboard or the session cannot be created.
     */
    ISongyanAddonSession openSession(in Bundle hostInfo, ISongyanAddonHost host);

    /** The host is going away: release sessions; the process may be reclaimed afterwards. */
    void disconnect();
}
