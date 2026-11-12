package app.songyan.ime.script;

interface ScriptIsolatedCallback {
    void finished(int exitCode, String stdout, String stderr);
}
