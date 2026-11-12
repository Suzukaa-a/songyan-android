package app.songyan.ime.script;

oneway interface ScriptProxyCallback {
    // ok carries the payload: negotiation -> {"version": n}, list -> {"entries": [...]}, etc.
    // A failure carries {"error": "machine-name", "message": "developer text"}.
    void onResult(boolean ok, String payloadJson);
}
