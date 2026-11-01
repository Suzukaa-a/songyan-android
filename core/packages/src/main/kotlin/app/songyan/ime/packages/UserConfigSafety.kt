package app.songyan.ime.packages

/**
 * What the user's own configuration files may name and how deep they may nest, checked on the
 * parsed document and never by following anything in it. The files are untrusted (any app the
 * user lets into the folder can write them), and librime opens whatever file name a config
 * value gives it, so a name that leaves the data folders is refused before a deployment sees it.
 * Names follow the import rules ([SchemeSourceCheck.safe], [SchemePackageImportBuilder.validatePath]).
 */
object UserConfigSafety {
    /** More nested flow collections than any real configuration has; librime's reader recurses on them. */
    const val MAX_FLOW_DEPTH = 64

    private const val MAX_NODES = 1_000_000

    /** Config keys whose value names a file (or a database) librime opens, by the last part of the key. */
    private val FILE_KEYS =
        setOf(
            "import_preset",
            "dictionary",
            "prism",
            "user_dict",
            "vocabulary",
            "opencc_config",
            "import_tables",
            "db",
            "language",
            "schema",
            "dependencies",
            "packs",
        )
    private val LUA_COMPONENT = Regex("""^lua_(?:processor|translator|filter|segmentor)(?:@|$)""")

    /** Whether [path] is a relative path a source or the user's folder may hold. */
    fun safePath(path: String): Boolean = runCatching { SchemePackageImportBuilder.validatePath(path) }.isSuccess

    /** Whether the text nests flow collections (`[`, `{`) deeper than [MAX_FLOW_DEPTH], outside comments and quotes. */
    fun tooDeep(text: String): Boolean {
        var depth = 0
        for (line in text.lineSequence()) {
            var quote = '\u0000'
            var previous = ' '
            var previousSignificant = '\n'
            for (char in line) {
                if (quote != '\u0000') {
                    if (char == quote) quote = '\u0000'
                } else if (char == '#' && previous.isWhitespace()) {
                    break
                } else if ((char == '"' || char == '\'') && previousSignificant in SCALAR_STARTS) {
                    quote = char
                } else if (char == '[' || char == '{') {
                    if (++depth > MAX_FLOW_DEPTH) return true
                } else if ((char == ']' || char == '}') && depth > 0) {
                    depth--
                }
                previous = char
                if (!char.isWhitespace()) previousSignificant = char
            }
        }
        return false
    }

    /**
     * The first value in the parsed document [root] that names a file outside the data folders (or
     * is not a plain relative name), or null. Covers include and patch references (`file:/node`),
     * `import_preset`, `dictionary`, `import_tables`, `prism`, `packs`, `user_dict`, `vocabulary`,
     * `opencc_config`, `db`, `grammar/language`, `schema` (a `schema_list` entry) and
     * `dependencies`. Patch operators do not hide a key: `a/user_dict/=`, `a/user_dict/+` and list
     * selectors (`@0`, `@last`, `@before 1`, `@after 1`, `@next`) are read as the key they act on, and
     * appending a string to one of these values is refused outright (it can build a path out of
     * pieces). The same walk over a compiled build file finds the keys at their real paths.
     */
    fun unsafeReference(root: Any?): String? {
        var budget = MAX_NODES
        val stack = ArrayDeque<Any?>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            if (--budget < 0) return "too many nodes"
            when (val node = stack.removeLast()) {
                is Map<*, *> ->
                    for ((key, value) in node) {
                        if (key is String) unsafeEntry(key, value)?.let { return it }
                        if (value is Map<*, *> || value is List<*>) stack.addLast(value)
                    }
                is List<*> -> node.forEach { if (it is Map<*, *> || it is List<*>) stack.addLast(it) }
                else -> Unit
            }
        }
        return null
    }

    private fun unsafeEntry(
        key: String,
        value: Any?,
    ): String? {
        // A repeated key arrives as `key/=`: the same key again.
        var base = key
        while (base.endsWith("/=")) base = base.removeSuffix("/=")
        if (base == "__include" || base == "__patch") return firstUnsafeReference(value)
        val parts = key.split('/')
        val appended = parts.lastOrNull() == "+"
        // A string can be built from pieces (`@next: lu`, then `@last/+: a_filter@*x`): lists are what Rime appends to.
        if ((appended || base == "__append" || base == "__merge") && isScalar(value)) return key
        // The key the operators act on: without `=`, `+` and list selectors.
        val name = parts.lastOrNull { it.isNotEmpty() && it != "=" && it != "+" && !it.startsWith("@") } ?: return null
        if (name !in FILE_KEYS) return null
        return firstUnsafeName(value)
    }

    /** Every Lua component entry (`lua_filter@name`, `lua_translator@*module`) in the parsed document. */
    fun luaComponents(root: Any?): List<String> {
        val found = sortedSetOf<String>()
        var budget = MAX_NODES
        val stack = ArrayDeque<Any?>()
        stack.addLast(root)
        while (stack.isNotEmpty() && --budget >= 0) {
            when (val node = stack.removeLast()) {
                is Map<*, *> -> node.values.forEach { stack.addLast(it) }
                is List<*> -> node.forEach { stack.addLast(it) }
                is String -> if (LUA_COMPONENT.containsMatchIn(node)) found += node.trim()
                else -> Unit
            }
        }
        return found.toList()
    }

    /** A `file:/node` reference (or a list of them, as `__patch` takes) whose file part is unsafe, or null. */
    private fun isScalar(value: Any?): Boolean = value != null && value !is List<*> && value !is Map<*, *>

    private fun firstUnsafeReference(value: Any?): String? =
        when (value) {
            is List<*> -> value.firstNotNullOfOrNull { firstUnsafeReference(it) }
            is Map<*, *> -> null
            null -> null
            else -> {
                val text = value.toString()
                val colon = text.indexOf(':')
                // Without a file part the reference is to the file itself.
                if (colon <= 0) {
                    null
                } else {
                    val name = text.substring(0, colon)
                    val file = if (name.endsWith(".yaml")) name else "$name.yaml"
                    text.takeIf { !SchemeSourceCheck.safe(file) || !safePath(file) }
                }
            }
        }

    private fun firstUnsafeName(value: Any?): String? =
        when (value) {
            is List<*> -> value.firstNotNullOfOrNull { firstUnsafeName(it) }
            // `{__append: x}` / `{__merge: x}` spell the same value another way.
            is Map<*, *> ->
                value.entries.firstNotNullOfOrNull { (k, v) ->
                    if (k is String &&
                        k.startsWith("__")
                    ) {
                        firstUnsafeName(v)
                    } else {
                        null
                    }
                }
            null -> null
            else -> value.toString().takeIf { it.isNotEmpty() && (!SchemeSourceCheck.safe(it) || !safePath(it)) }
        }

    /** What a quote can follow, for it to begin a scalar. */
    private val SCALAR_STARTS = setOf('\n', ':', ',', '[', '{', '-', '?')
}
