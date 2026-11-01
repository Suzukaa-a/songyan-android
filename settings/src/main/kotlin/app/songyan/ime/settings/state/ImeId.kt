package app.songyan.ime.settings.state

/** Package and class of an input method, parsed from a system input-method id. */
data class ImeId(
    val packageName: String,
    val className: String,
)

/**
 * Parses a system input-method id such as `com.example/.ImeService` or
 * `com.example/com.example.ImeService`. Returns null for empty or malformed ids.
 *
 * A trailing subtype suffix (`:...`) is ignored; the class name cannot contain a colon.
 */
fun parseImeId(raw: String?): ImeId? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val withoutSubtype = trimmed.substringBefore(':')
    val slash = withoutSubtype.indexOf('/')
    if (slash <= 0 || slash == withoutSubtype.lastIndex) return null
    val packageName = withoutSubtype.substring(0, slash)
    val classPart = withoutSubtype.substring(slash + 1)
    val className = if (classPart.startsWith(".")) packageName + classPart else classPart
    if (className.isEmpty()) return null
    return ImeId(packageName = packageName, className = className)
}

/** True when [selectedId] points at the same package and class as [target]. */
fun selectedImeMatches(
    selectedId: String?,
    target: ImeId,
): Boolean {
    val parsed = parseImeId(selectedId) ?: return false
    return parsed.packageName == target.packageName && parsed.className == target.className
}
