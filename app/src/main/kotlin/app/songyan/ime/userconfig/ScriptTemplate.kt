package app.songyan.ime.userconfig

/**
 * The file a new script starts as: a comment on how to name it from a configuration file (words from
 * the string resources, so they follow the app language), the line that does it, and a filter that
 * passes every candidate through. It is the user's file content, not interface text.
 */
object ScriptTemplate {
    fun text(
        name: String,
        hint: String,
    ): String =
        buildString {
            append("-- ").append(hint.replace(Regex("\\s*[\\r\\n]+\\s*"), " ").trim()).append('\n')
            append("-- engine/filters/+: [lua_filter@*").append(name).append("]\n")
            append("return {\n")
            append("  func = function(input, env)\n")
            append("    for cand in input:iter() do\n")
            append("      yield(cand)\n")
            append("    end\n")
            append("  end,\n")
            append("}\n")
        }
}
