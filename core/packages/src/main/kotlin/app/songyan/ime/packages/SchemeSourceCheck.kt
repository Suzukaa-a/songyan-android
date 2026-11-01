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
}
