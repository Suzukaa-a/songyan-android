# The script JNI layer (script/src/main/cpp/android/script_jni.c) finds this class and these
# members by name on the engine thread. R8 may rename or remove them otherwise, which only shows
# up in a release build: debug builds are not shrunk.
-keep class app.songyan.ime.script.ScriptRunHost {
    static app.songyan.ime.script.ScriptRunHost use();
    void fetch(java.lang.String, java.lang.String, java.lang.String, byte[], long);
    boolean authorize(java.lang.String, int);
    void log(byte[]);
}

# The native methods bind by name (Java_app_songyan_ime_script_ScriptNative_*). The default
# ProGuard file keeps native names, but say it here so the contract is visible next to the JNI.
-keep class app.songyan.ime.script.ScriptNative {
    native <methods>;
}
