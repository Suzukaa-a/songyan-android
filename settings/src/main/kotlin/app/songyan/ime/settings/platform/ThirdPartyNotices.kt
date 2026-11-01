package app.songyan.ime.settings.platform

/** One component described in the bundled third-party notice document. */
data class NoticeComponent(
    val number: Int,
    /** Name and version as the notice writes them, e.g. "librime 1.17.0". */
    val title: String,
    /** Licence as the notice names it, e.g. "BSD 3-Clause". */
    val licence: String?,
    val copyright: String?,
    val upstream: String?,
    /** What the component is used for. */
    val role: String?,
    /** The notice's "Pinned" line: where the exact version comes from (release, tag or commit). */
    val pinned: String? = null,
    /** Notice text that does not map to one of the fields above; kept so nothing is dropped. */
    val notes: String?,
    /** Paths or file names the notice points at for the full licence text. */
    val fullTextReferences: List<String>,
)

/**
 * The parsed bundling notice: the component list plus the verbatim licence texts that the notice
 * carries in its appendix. Parsing keeps the licence wording untouched while giving the screen a
 * structure to render: the raw document is not readable as plain paragraphs.
 */
data class ThirdPartyNotices(
    val components: List<NoticeComponent>,
    /** Verbatim appendix texts keyed by the path the appendix names. */
    val appendix: Map<String, String>,
)

/** Parses the synced third-party notice document. Pure: no Android types, so it is unit tested. */
object ThirdPartyNoticesParser {
    private val heading = Regex("""^(\d+)\.\s+(\S.*)$""")

    // Field bullets are "- License: ..." style; a copyright line has no colon and is matched below.
    private val bullet = Regex("""^\s+-\s+([A-Za-z][^:]{0,60}):\s*(.*)$""")

    private val copyrightBullet = Regex("""^\s+-\s+(Copyright.*)$""")
    private val appendixStart = Regex("""^\s*=+.*[Aa]ppendix.*$""")
    private val separator = Regex("""^\s*=+\s*$""")
    private val appendixPath = Regex("""^\s*%\s+(\S+)\s*$""")
    private val pathToken = Regex("""[A-Za-z0-9_./-]+(?:\.txt|\.md)|[A-Za-z0-9_./-]+/(?:LICENSE|COPYING)""")

    private val trailingParenthetical = Regex("""^(.*?)\s*\(([^()]*)\)\s*$""")

    fun parse(text: String): ThirdPartyNotices {
        val lines = text.replace("\r\n", "\n").split('\n')
        val appendixIndex = lines.indexOfFirst { appendixStart.matches(it) }.takeIf { it >= 0 } ?: lines.size
        return ThirdPartyNotices(
            components = parseComponents(lines.take(appendixIndex)),
            appendix = parseAppendix(lines.drop(appendixIndex)),
        )
    }

    private fun parseComponents(lines: List<String>): List<NoticeComponent> {
        val components = mutableListOf<NoticeComponent>()
        var current: MutableMap<String, StringBuilder>? = null
        var currentNumber = 0
        var currentTitle = ""
        var currentField: String? = null

        fun flush() {
            val fields = current ?: return
            components += componentsOf(currentNumber, currentTitle, fields)
            current = null
        }

        for (line in lines) {
            val trimmed = line.trim()
            val headingMatch = heading.matchEntire(line)
            if (headingMatch != null) {
                flush()
                currentNumber = headingMatch.groupValues[1].toInt()
                currentTitle = headingMatch.groupValues[2].trim()
                current = linkedMapOf()
                currentField = null
                continue
            }
            val fields = current
            if (fields == null) continue
            val copyrightMatch = copyrightBullet.matchEntire(line)
            if (copyrightMatch != null) {
                currentField = "copyright"
                fields[currentField!!] =
                    StringBuilder(
                        copyrightMatch.groupValues[1]
                            .removePrefix("Copyright")
                            .trim()
                            .trimStart(':', ' '),
                    )
                continue
            }
            val bulletMatch = bullet.matchEntire(line)
            if (bulletMatch != null) {
                currentField = bulletMatch.groupValues[1].trim().lowercase()
                fields[currentField!!] = StringBuilder(bulletMatch.groupValues[2].trim())
                continue
            }
            // A non-indented, non-empty line ends the component block (e.g. "Distribution note").
            if (trimmed.isNotEmpty() && line.isNotEmpty() && !line[0].isWhitespace()) {
                flush()
                currentField = null
                continue
            }
            if (trimmed.isNotEmpty() && currentField != null) {
                fields.getValue(currentField!!).append(' ').append(trimmed)
            }
        }
        flush()
        return components
    }

    private fun componentsOf(
        number: Int,
        title: String,
        fields: Map<String, StringBuilder>,
    ): NoticeComponent {
        val licenceField = fields["license"]?.toString()?.trim().orEmpty()
        val licenceParts = licenceField.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        val copyrightFromLicence = licenceParts.firstOrNull { it.startsWith("copyright", ignoreCase = true) }
        val fullTextFromLicence = licenceParts.firstOrNull { it.startsWith("full text", ignoreCase = true) }
        val headingMatch = trailingParenthetical.matchEntire(title)
        val headingNote =
            headingMatch
                ?.groupValues
                ?.get(2)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        val bulletNotes =
            fields
                .filterKeys { it !in knownFields }
                .values
                .map { it.toString() }
                .joinToString(" ")
        val fullTextReferences =
            sequenceOf(
                fields["full text"]?.toString(),
                fullTextFromLicence,
                fields["note"]?.toString(),
                bulletNotes,
            ).filterNotNull()
                .flatMap { pathToken.findAll(it).map { match -> match.value } }
                .distinct()
                .toList()
        val licence = balancedLicence(licenceParts.firstOrNull().orEmpty())
        return NoticeComponent(
            number = number,
            // A trailing "(external, pinned)" style remark is not part of the name; move it to the notes.
            title = headingMatch?.groupValues?.get(1)?.trim() ?: title,
            licence = licence,
            copyright = fields["copyright"]?.toString()?.trim() ?: copyrightFromLicence,
            upstream = fields["upstream"]?.toString()?.trim(),
            role = fields["role"]?.toString()?.trim(),
            pinned = fields["pinned"]?.toString()?.trim(),
            notes =
                listOfNotNull(
                    headingNote?.let { "($it)" },
                    bulletNotes.ifEmpty { null },
                ).joinToString(" ").ifEmpty { null },
            fullTextReferences = fullTextReferences,
        )
    }

    private fun parseAppendix(lines: List<String>): Map<String, String> {
        val sections = LinkedHashMap<String, String>()
        var index = 0
        while (index < lines.size) {
            if (!separator.matches(lines[index])) {
                index++
                continue
            }
            val path =
                (1..3)
                    .asSequence()
                    .mapNotNull { offset -> lines.getOrNull(index + offset)?.let { appendixPath.matchEntire(it) } }
                    .firstOrNull()
                    ?.groupValues
                    ?.get(1)
            if (path == null) {
                index++
                continue
            }
            // Skip the header separator, the path line and the closing separator.
            val pathLine = lines.indexOfFirst(index) { appendixPath.matches(it) }
            var body = pathLine + 1
            if (body < lines.size && separator.matches(lines[body])) body++
            val bodyLines = mutableListOf<String>()
            while (body < lines.size) {
                if (separator.matches(lines[body])) break
                bodyLines += lines[body]
                body++
            }
            sections[path] = bodyLines.joinToString("\n").trim('\n').trimEnd()
            index = body
        }
        return sections
    }

    private fun List<String>.indexOfFirst(
        start: Int,
        predicate: (String) -> Boolean,
    ): Int {
        for (position in start until size) {
            if (predicate(this[position])) return position
        }
        return start
    }

    /** "BSD 2-Clause OR LGPL-2.1-or-later (dual" keeps an unbalanced parenthesis; drop that tail. */
    private fun balancedLicence(value: String): String {
        val text = value.trim()
        if (text.count { it == '(' } > text.count { it == ')' }) return text.substringBeforeLast('(').trim()
        return text
    }

    private val knownFields =
        setOf("upstream", "pinned", "license", "copyright", "role", "full text", "note", "source")
}
