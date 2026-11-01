package app.songyan.ime.userconfig

/**
 * Commented-out grammar model parameters appended to the file an input method's grammar settings
 * open, so the user sees what can be tuned. Comments only: nothing changes until the user removes
 * the `#`. The lines work under whatever `patch:` the file already has, and the heading says so.
 */
object GrammarParameterExample {
    private val PARAMETERS =
        listOf(
            "grammar/collocation_max_length: 6",
            "grammar/collocation_min_length: 2",
            "grammar/collocation_penalty: -14",
            "grammar/non_collocation_penalty: -6",
            "grammar/weak_collocation_penalty: -100",
            "grammar/rear_penalty: -18",
            "translator/contextual_suggestions: false",
            "translator/max_homophones: 8",
        )
    private val BLOCK_PATCH = Regex("(?m)^patch:\\s*(#.*)?$")
    private val EMPTY_PATCH = Regex("(?m)^patch:\\s*\\{\\s*\\}\\s*(#.*)?$")
    private val HAS_GRAMMAR = Regex("grammar/|(?m)^\\s+grammar:")

    /** Which heading fits the file: it has no `patch:`, one with entries, or the empty `patch: {}` of a new file. */
    enum class Heading { NEW_PATCH, UNDER_PATCH, EMPTY_PATCH }

    /** The heading [text] needs, or null when it already holds grammar parameters (written or as this example). */
    fun headingFor(text: String): Heading? =
        when {
            HAS_GRAMMAR.containsMatchIn(text) -> null
            EMPTY_PATCH.containsMatchIn(text) -> Heading.EMPTY_PATCH
            BLOCK_PATCH.containsMatchIn(text) -> Heading.UNDER_PATCH
            else -> Heading.NEW_PATCH
        }

    /** [text] with the example appended; unchanged when it has grammar parameters already. */
    fun appendedTo(
        text: String,
        headings: (Heading) -> String,
    ): String {
        val heading = headingFor(text) ?: return text
        val body =
            buildString {
                append("# ").append(headings(heading).replace(Regex("\\s*[\\r\\n]+\\s*"), " ").trim()).append('\n')
                if (heading == Heading.NEW_PATCH) append("# patch:\n")
                for (line in PARAMETERS) append("#   ").append(line).append('\n')
            }
        val separator =
            if (text.isEmpty() || text.endsWith("\n\n")) {
                ""
            } else if (text.endsWith("\n")) {
                "\n"
            } else {
                "\n\n"
            }
        return text + separator + body
    }
}
