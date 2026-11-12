// The proxy-process API: storage, import, settings and runs live in one ordinary process
// (:script). The JS engine itself only ever runs in the isolated service; this proxy owns the
// storage, the Android Keystore wrapping and the network transport. Version is negotiated on
// connect so a newer app can talk to an older proxy (or vice versa) without guessing.
package app.songyan.ime.script;

oneway interface ScriptProxy {
    // Negotiation: both sides exchange their protocol version and keep the common minimum. The
    // capability names say what this host can offer a run (ScriptHostCapability); the proxy keeps
    // them for its catalog answers and for the snapshot the IME process reads without binding.
    void hello(int clientVersion, in java.util.List<String> capabilities,
               app.songyan.ime.script.ScriptProxyCallback negotiation);

    // Catalog operations. Byte blobs travel as byte[]; entries are serialized JSON objects so the
    // wire stays open for additions without breaking old clients.
    void list(app.songyan.ime.script.ScriptProxyCallback reply);

    void inspect(in byte[] source, app.songyan.ime.script.ScriptProxyCallback reply);

    void add(in byte[] source, in String originJson, app.songyan.ime.script.ScriptProxyCallback reply);

    void confirm(String id, in android.os.Bundle settings, app.songyan.ime.script.ScriptProxyCallback reply);

    void setEnabled(String id, boolean enabled, app.songyan.ime.script.ScriptProxyCallback reply);

    void reorder(in java.util.List<String> orderedIds, app.songyan.ime.script.ScriptProxyCallback reply);

    void source(String id, app.songyan.ime.script.ScriptProxyCallback reply);

    void delete(String id, app.songyan.ime.script.ScriptProxyCallback reply);

    // Re-fetches a link origin; the reply entry carries confirmationPending when declarations moved.
    void checkUpdate(String id, app.songyan.ime.script.ScriptProxyCallback reply);

    void runForTest(String id, String text, app.songyan.ime.script.ScriptProxyCallback reply);

    void run(String id, String text, app.songyan.ime.script.ScriptProxyCallback reply);

    // An audio run: the recorded WAV crosses as an open descriptor (a 60 s 16 kHz mono WAV is
    // 1.92 MB, larger than one Binder transaction) and the proxy hands the same descriptor to the
    // isolated process after it has checked the stored header.
    void runAudio(String id, in android.os.ParcelFileDescriptor audio, app.songyan.ime.script.ScriptProxyCallback reply);

    void cancel(String id);
}
