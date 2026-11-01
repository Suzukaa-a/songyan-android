# Engine JNI: libsongyan_rime.so binds Java_app_songyan_ime_rime_RimeNative_* by name, so the
# class and its native methods must keep their names. The native code calls no other Java
# members (its only FindClass is java/lang/String), so nothing else needs keeping.
-keep class app.songyan.ime.rime.RimeNative {
    native <methods>;
}
