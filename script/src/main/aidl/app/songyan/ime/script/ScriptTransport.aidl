// One fetch performed by the proxy process on behalf of a run in the isolated process. The
// proxy applies the address policy (declared @connect hosts, HTTPS, LAN-only cleartext) and the
// contract's response-size limit; the answer returns in one call.
package app.songyan.ime.script;

oneway interface ScriptTransport {
    void fetch(String url, String method, in String headersJson, in byte[] body,
               app.songyan.ime.script.ScriptTransportCallback callback);
}
