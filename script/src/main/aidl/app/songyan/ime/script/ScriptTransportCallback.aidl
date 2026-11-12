package app.songyan.ime.script;

oneway interface ScriptTransportCallback {
    // accepted=false carries the refusal reason in error.
    void onResult(boolean accepted, int status, in byte[] body, String contentType,
                  String finalUrl, String headersJson, String error);
}
