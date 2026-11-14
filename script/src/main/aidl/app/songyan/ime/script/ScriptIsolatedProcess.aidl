// Runs shared script cases in the isolated process. The host (a normal process that can read
// the case assets) sends the command line plus the case files as open descriptors: the isolated
// process cannot read the app's assets, so the bytes travel over Binder as memfds. Each
// placeholder in the command line ("@fd0", "@fd1", ...) is replaced with the isolated process's
// own /proc/self/fd/<n> path before the shared CLI runs.
package app.songyan.ime.script;

import app.songyan.ime.script.ScriptIsolatedCallback;
import app.songyan.ime.script.ScriptTransport;

oneway interface ScriptIsolatedProcess {
    void run(in List<String> arguments, in List<android.os.ParcelFileDescriptor> files, ScriptIsolatedCallback callback);

    // The production run: fetch travels back to the caller over the [transport] binder, because an
    // isolated process may not bind services itself. The caller applies the address policy too;
    // [declaredHosts] and [settingUrls] let this side refuse early with the same rule.
    // The recorded audio for @input audio travels as an open descriptor: a 60 s 16 kHz mono WAV is
    // larger than one Binder transaction, and the bytes are read in this process before the run.
    void runScript(in byte[] source, in byte[] settingsJson, in byte[] text,
                   in android.os.ParcelFileDescriptor audio, in List<String> declaredHosts,
                   in List<String> settingUrls, ScriptTransport transport,
                   ScriptIsolatedCallback callback);

    // Stops the run in progress: a request it waits on is abandoned and the run ends cancelled.
    void cancelRun();
}
