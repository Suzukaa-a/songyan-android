package app.songyan.ime.packages

/**
 * Validation and text manipulation shared by recipe parsing and patch application. Ported from the
 * iOS `RecipePlanSupport`; file-system reads stay with the install layer.
 */
internal object RecipePlanSupport {
    /** Prefix of one managed block's marker line; the recipe identity follows, then " {". */
    internal const val MANAGED_MARKER_PREFIX: String = "# Rx: "

    /** Closing line of one managed block. */
    internal const val MANAGED_BLOCK_END: String = "# }"

    private val GLOB_COMPONENT = Regex("""^[\p{L}\p{N}\p{S}_.*?\[\]!-]+$""")
    private val VARIABLE_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    private val VARIABLE_FALLBACK = Regex("^[A-Za-z0-9_./-]*$")

    fun validateYaml(
        text: String,
        label: String,
    ) {
        try {
            BoundedYaml.compose(text)
        } catch (error: Exception) {
            throw RecipeInstallError.InvalidResource(label + " YAML is invalid: " + error.message)
        }
    }

    fun checkPath(
        path: String,
        glob: Boolean = false,
    ) {
        if (!glob) {
            if (!SchemeSourceCheck.safe(path) ||
                path.contains(".userdb") ||
                (path.startsWith("build/") && path != "build/.gitkeep")
            ) {
                throw RecipeInstallError.InvalidResource("unsupported config path: " + path)
            }
            return
        }
        val parts = splitKeepingEmpty(path, "/")
        val invalid =
            path.isEmpty() ||
                parts.any { it.isEmpty() || it == "." || it == ".." || !GLOB_COMPONENT.matches(it) } ||
                path.contains(".userdb") ||
                (path.startsWith("build/") && path != "build/.gitkeep")
        if (invalid) throw RecipeInstallError.InvalidResource("unsupported config path: " + path)
    }

    /**
     * Expands `$name`, `${name}` and `${name:-fallback}`; heredoc evaluation and backslash
     * escapes are refused so a recipe body is never a shell program.
     */
    fun expand(
        text: String,
        variables: Map<String, String>,
    ): String {
        val output = StringBuilder()
        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (character == '`' || character == '\\') {
                throw RecipeInstallError.InvalidManifest("heredoc evaluation and backslash escapes are not supported")
            }
            if (character != '$') {
                output.append(character)
                index++
                continue
            }
            val next = index + 1
            if (next >= text.length) throw RecipeInstallError.InvalidManifest("unsupported \$ variable format")
            val name: String
            val fallback: String?
            if (text[next] == '{') {
                val end = text.indexOf('}', next)
                if (end < 0) throw RecipeInstallError.InvalidManifest("variable expression is not closed (missing })")
                val value = text.substring(next + 1, end)
                val parts = splitKeepingEmpty(value, ":-")
                if (parts.size > 2) throw RecipeInstallError.InvalidManifest("unsupported variable expansion format")
                name = parts[0]
                fallback = if (parts.size == 2) parts[1] else null
                index = end + 1
            } else {
                var end = next
                while (end < text.length && isAsciiVariableCharacter(text[end])) end++
                name = text.substring(next, end)
                fallback = null
                index = end
            }
            if (!VARIABLE_NAME.matches(name) || (fallback != null && !VARIABLE_FALLBACK.matches(fallback))) {
                throw RecipeInstallError.InvalidManifest(
                    "variables support only \$name, \${name} and \${name:-literal}",
                )
            }
            val value = variables[name] ?: ""
            output.append(if (value.isEmpty()) (fallback ?: "") else value)
        }
        return output.toString()
    }

    private fun isAsciiVariableCharacter(character: Char): Boolean =
        character.code < 128 &&
            (character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' || character == '_')

    /**
     * Replaces this invocation's managed block in [original] (or inserts it) with [body], a
     * `patch_files` list body. Surrounding user content, other invocations' blocks and the YAML
     * document stay byte-identical.
     */
    fun patch(
        original: String,
        body: String,
        rx: String,
    ): String {
        if (original.contains('\r') || (original.isNotEmpty() && !original.endsWith("\n"))) {
            throw RecipeInstallError.InvalidResource(
                "patch target files must use LF line endings and end with a newline",
            )
        }
        validateYaml(original, "existing custom configuration")
        validateYaml("__patch:\n" + body, "patch content")
        val lines = splitLinesKeepingEmpty(original).toMutableList()
        if (lines.lastOrNull() == "") lines.removeAt(lines.size - 1)
        val marker = MANAGED_MARKER_PREFIX + rx + " {"
        while (true) {
            val start = lines.indexOf(marker)
            if (start < 0) break
            // The scan must not run past another invocation's marker: if our block lost its
            // terminator the file is damaged, and clearing to the next "# }" would delete that
            // other block. Refuse instead of destroying user configuration.
            val end =
                (start + 1 until lines.size).firstOrNull {
                    lines[it] == MANAGED_BLOCK_END || lines[it].startsWith(MANAGED_MARKER_PREFIX)
                }
            if (end == null || lines[end] != MANAGED_BLOCK_END) {
                throw RecipeInstallError.InvalidResource("an existing managed block is not closed")
            }
            lines.subList(start, end + 1).clear()
        }
        if (!lines.contains("__patch:")) lines.add("__patch:")
        if (lines.count { it == "__patch:" } != 1) {
            throw RecipeInstallError.InvalidResource("multiple __patch top-level keys are not supported")
        }
        val begin = lines.indexOf("__patch:")
        // Inline __patch maps/scalars cannot be safely combined with a new block-list key.
        if (lines.any { it.startsWith("__patch:") && it != "__patch:" }) {
            throw RecipeInstallError.InvalidResource("an existing __patch is not a block list")
        }
        var end = lines.size
        for (index in begin + 1 until lines.size) {
            val line = lines[index]
            if (line.isNotEmpty() && !line.startsWith(" ") && !line.startsWith("#")) {
                end = index
                break
            }
        }
        val block = ArrayList<String>(2 + body.length)
        block.add(marker)
        block.addAll(splitLinesKeepingEmpty(body).dropLast(1))
        block.add("# }")
        lines.addAll(end, block)
        val result = lines.joinToString("\n") + "\n"
        validateYaml(result, "merged custom configuration")
        return result
    }
}
