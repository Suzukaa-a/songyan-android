package app.songyan.ime.script

/** What a host process can offer a script run. A stored script is kept even when the host cannot
 *  provide one of its declared inputs; [ScriptEntry.availableOnThisHost] is computed from this set
 *  and decides whether the keyboard panel and the settings pages offer the script.
 *
 *  The set travels from the host that installs [ScriptProxyRegistry] to the proxy process in the
 *  [ScriptProxy] `hello` call, so the catalog snapshot the IME process reads without binding
 *  carries the same answer. A proxy that never hears a hello offers [DEFAULT]. */
enum class ScriptHostCapability {
    /** Text input; every script requires it. */
    TEXT,

    /** Recorded audio input for a header that declares `@input audio`. */
    AUDIO,
    ;

    companion object {
        /** What a host that declares nothing offers: text only. */
        val DEFAULT: Set<ScriptHostCapability> = setOf(TEXT)

        /** The capabilities a script requires: text always, audio when the header declares it. */
        fun required(needsAudio: Boolean): Set<ScriptHostCapability> =
            if (needsAudio) setOf(TEXT, AUDIO) else setOf(TEXT)

        /** Wire names to capabilities. Unknown names are dropped so a newer host cannot break an
         *  older proxy; a declaration with no known name therefore offers nothing. */
        fun parse(names: List<String>): Set<ScriptHostCapability> = entries.filter { it.name in names }.toSet()

        /** The wire form the proxy's `hello` carries. */
        fun names(capabilities: Set<ScriptHostCapability>): List<String> = capabilities.map { it.name }
    }
}
