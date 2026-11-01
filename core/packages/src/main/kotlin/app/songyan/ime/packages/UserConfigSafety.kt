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
        )
    private val LUA_COMPONENT = Regex("""^lua_(?:processor|translator|filter|segmentor)@""")

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
     * `import_preset`, `dictionary`, `import_tables`, `prism`, `user_dict`, `vocabulary`,
     * `opencc_config`, `db` and `grammar/language`.
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
                        val name = (key as? String)?.substringAfterLast('/')
                        when {
                            key == "__include" || key == "__patch" -> firstUnsafeReference(value)?.let { return it }
                            name in FILE_KEYS -> firstUnsafeName(value)?.let { return it }
                        }
                        if (value is Map<*, *> || value is List<*>) stack.addLast(value)
                    }
                is List<*> -> node.forEach { if (it is Map<*, *> || it is List<*>) stack.addLast(it) }
                else -> Unit
            }
        }
        return null
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
            is Map<*, *> -> null
            null -> null
            else -> value.toString().takeIf { it.isNotEmpty() && (!SchemeSourceCheck.safe(it) || !safePath(it)) }
        }

    /** What a quote can follow, for it to begin a scalar. */
    private val SCALAR_STARTS = setOf('\n', ':', ',', '[', '{', '-', '?')
}
