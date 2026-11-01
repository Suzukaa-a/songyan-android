package app.songyan.ime.userconfig

/**
 * The file a new `xxx.custom.yaml` starts as: valid YAML that patches nothing, with two comment
 * lines (words from the string resources, so they follow the app language) and a commented example.
 * It is the user's file content, not interface text.
 */
object CustomPatchTemplate {
    fun text(
        hint: String,
        example: String,
    ): String =
        buildString {
            append("# ").append(oneLine(hint)).append('\n')
            append("# ").append(oneLine(example)).append('\n')
            append("# patch:\n")
            append("#   menu/page_size: 7\n")
            append("patch: {}\n")
        }

    private fun oneLine(text: String) = text.replace(Regex("\\s*[\\r\\n]+\\s*"), " ").trim()
}
