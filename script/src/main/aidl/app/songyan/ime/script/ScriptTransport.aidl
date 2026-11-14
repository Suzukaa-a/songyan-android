// One fetch performed by the proxy process on behalf of a run in the isolated process. The
// proxy applies the address policy (declared @connect hosts, HTTPS, LAN-only cleartext) and the
// contract's response-size limit; the answer returns in one call.
package app.songyan.ime.script;

oneway interface ScriptTransport {
    // [timeoutMillis] is what is left of the run's duration; the request may not outlive it. The
    // body crosses as a descriptor: an uploaded recording is larger than a one-way transaction.
    void fetch(String url, String method, in String headersJson, in android.os.ParcelFileDescriptor body,
               long timeoutMillis, app.songyan.ime.script.ScriptTransportCallback callback);
}
