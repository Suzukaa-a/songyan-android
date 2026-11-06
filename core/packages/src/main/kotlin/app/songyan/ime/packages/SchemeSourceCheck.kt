package app.songyan.ime.packages

/**
 * Path and identity checks for scheme sources, mirroring the iOS `SchemeSourceCheck` helpers that
 * recipe planning needs. File-system walking lives with the install layer, not here.
 */
internal object SchemeSourceCheck {
    /** True for a relative path whose components are all plain names (no traversal, no escapes). */
    fun safe(path: String): Boolean {
        if (path.isEmpty() || path.startsWith("/")) return false
        return splitKeepingEmpty(path, "/").all { component ->
            component.isNotEmpty() &&
                component != "." &&
                component != ".." &&
                !component.contains('\\') &&
                !component.contains(':') &&
                component.none { Character.isISOControl(it) }
        }
    }

    fun hash(data: ByteArray): String = sha256Hex(data)

    /**
     * Files a package provides for librime: configuration, dictionaries, Lua, OpenCC data, models and
     * prediction databases. Everything else (licences, readmes, CI scripts) is documentation. Two
     * archives may both ship documentation; a runtime file they fill differently must be refused,
     * because the engine would silently read the wrong upstream files. A licence or notice is
     * documentation whatever its suffix, so `LICENSE.txt` from two upstreams is kept, not refused.
     */
    fun isRuntimeSourceFile(path: String): Boolean {
        if (isNotice(path)) return false
        if (path.split('/').any { it.startsWith(".") }) return false
        val lower = path.lowercase()
        return RUNTIME_EXTENSIONS.any { lower.endsWith(it) } || Prediction.isDatabasePath(path)
    }

    /** A licence, readme or authors file: attribution a package distributes, never engine input. */
    fun isNotice(path: String): Boolean {
        val name = path.substringAfterLast('/').uppercase()
        return name in NOTICE_NAMES || NOTICE_PREFIXES.any { name.startsWith(it) }
    }

    private val RUNTIME_EXTENSIONS = listOf(".yaml", ".yml", ".lua", ".txt", ".json", ".gram", ".ocd", ".ocd2")
    private val NOTICE_NAMES = setOf("AUTHORS", "README")
    private val NOTICE_PREFIXES = listOf("LICENSE", "LICENCE", "COPYING", "NOTICE")
}
