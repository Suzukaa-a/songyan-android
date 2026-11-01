package app.songyan.ime.settings.platform

/** One readable licence text attached to a component or shown on its own. */
data class LicenceText(
    val label: String,
    val text: String,
)

/** A component as the third-party licence screen shows it. */
data class LicenceComponent(
    val title: String,
    val licence: String?,
    val copyright: String?,
    val upstream: String?,
    val role: String?,
    val notes: String?,
    /** Full licence texts, verbatim, resolved from the notice appendix or the bundled files. */
    val texts: List<LicenceText>,
    /** A version the title does not carry, taken from the notice's pinned line. */
    val version: String? = null,
) {
    /** The name with its version, as the list shows it. */
    val displayTitle: String get() = if (version == null) title else "$title $version"
}

/** Turns notice and licence file names into what the screen shows, so no file name reaches the user. */
object LicenceLabels {
    private val versionToken = Regex("""\d+(?:\.\d+)+""")
    private val releaseVersion = Regex("""(?:release|tag(?:\s+ver\.?)?)\s*v?(\d+(?:\.\d+)+)""", RegexOption.IGNORE_CASE)
    private val commit = Regex("""\b([0-9a-f]{40})\b""")
    private val suffix = Regex("""[-_](?:LICENSE|LICENCE|COPYING|NOTICE)(?:_[\d_]+)?$""", RegexOption.IGNORE_CASE)
    private val trailingVersion = Regex("""^(.*?)-(\d[\w.]*)$""")

    /**
     * The version a component should show when its title has none: a release or tag from the
     * pinned line, else the short commit. Null when the title already carries a version or nothing
     * is known.
     */
    fun missingVersion(
        title: String,
        pinned: String?,
    ): String? {
        if (versionToken.containsMatchIn(title) || pinned == null) return null
        releaseVersion.find(pinned)?.let { return it.groupValues[1] }
        commit.find(pinned)?.let { return it.groupValues[1].take(SHORT_COMMIT) }
        return null
    }

    /** "librime-1.17.0-LICENSE.txt" becomes "librime 1.17.0"; "GPL-3.0.txt" stays "GPL-3.0". */
    fun textLabel(fileName: String): String {
        val base = fileName.substringBeforeLast('.', fileName).replace(suffix, "").replace('_', ' ')
        val split = trailingVersion.matchEntire(base)
        return if (split != null && !split.groupValues[1].contains(Regex("""^[A-Z]+$"""))) {
            split.groupValues[1] + " " + split.groupValues[2]
        } else {
            base
        }
    }

    private const val SHORT_COMMIT = 7
}

/** The screen model: components with their readable texts, plus texts no component names. */
data class LicenceIndex(
    val components: List<LicenceComponent>,
    val extraTexts: List<LicenceText>,
) {
    /** Every page the licence screen can open: the components, then the texts no component names. */
    val entries: List<LicenceComponent> by lazy {
        components +
            extraTexts.map { text ->
                LicenceComponent(
                    title = text.label,
                    licence = null,
                    copyright = null,
                    upstream = null,
                    role = null,
                    notes = null,
                    texts = listOf(text),
                )
            }
    }
}

/** A licence file shipped in the app's assets. */
data class LicenceAsset(
    val path: String,
    val text: String,
) {
    val fileName: String get() = path.substringAfterLast('/')
}

/**
 * Turns the synced notice document plus the bundled licence files into a readable index.
 *
 * The notice document is written for the iOS build: it explains Xcode artifact paths and commits and
 * then inlines eight licence texts behind ASCII rules. None of that reads well on a phone, so the
 * screen renders the component list as an index and each component's licence text on its own page.
 * Legal content is preserved verbatim; only the presentation is adapted.
 */
object LicenceIndexBuilder {
    fun build(
        noticesText: String,
        assets: List<LicenceAsset>,
    ): LicenceIndex {
        val notices = ThirdPartyNoticesParser.parse(noticesText)
        val appendixByFile = notices.appendix.entries.associateBy { normalise(it.key.substringAfterLast('/')) }
        val assetByFile = assets.associateBy { normalise(it.fileName) }
        // One licence file can be named by several components; each file is rendered once per
        // component that names it, and only files no component names become "other texts".
        val usedFiles = mutableSetOf<String>()

        val components =
            notices.components.map { component ->
                val texts = LinkedHashMap<String, LicenceText>()

                fun take(
                    file: String,
                    label: String,
                    text: String,
                ) {
                    val key = normalise(file)
                    usedFiles += key
                    texts.putIfAbsent(key, LicenceText(label = LicenceLabels.textLabel(label), text = text))
                }
                component.fullTextReferences.forEach { reference ->
                    val file = reference.substringAfterLast('/')
                    appendixByFile[normalise(file)]?.let { (path, text) ->
                        take(file, path.substringAfterLast('/'), text)
                    }
                    assetByFile[normalise(file)]?.let { asset ->
                        take(file, asset.fileName, asset.text)
                    }
                }
                if (texts.isEmpty()) {
                    // The notice sometimes points at a bundle path (".../RimePrelude/LICENSE") that
                    // does not exist in the Android assets; fall back to the licence the notice names.
                    val licenceToken = component.licence?.let(::normalise)?.takeIf { it.isNotEmpty() }
                    if (licenceToken != null) {
                        assets.firstOrNull { normalise(it.fileName).contains(licenceToken) }?.let { asset ->
                            take(asset.fileName, asset.fileName, asset.text)
                        }
                    }
                }
                if (texts.isEmpty()) {
                    // Components 1-8 carry no "Full text" bullet: their text lives in the appendix
                    // under a file named after the component ("librime-1.17.0-LICENSE.txt").
                    val candidate = bestNameMatch(component.title, notices.appendix, assets)
                    if (candidate != null) {
                        take(candidate.fileName, candidate.fileName, candidate.text)
                    }
                }
                LicenceComponent(
                    title = component.title,
                    licence = component.licence,
                    copyright = component.copyright,
                    upstream = component.upstream,
                    role = component.role,
                    notes = component.notes,
                    texts = texts.values.toList(),
                    version = LicenceLabels.missingVersion(component.title, component.pinned),
                )
            }

        val extraTexts =
            buildList {
                notices.appendix.forEach { (path, text) ->
                    val file = path.substringAfterLast('/')
                    if (usedFiles.add(normalise(file))) {
                        add(LicenceText(LicenceLabels.textLabel(file), text))
                    }
                }
                assets.forEach { asset ->
                    if (usedFiles.add(normalise(asset.fileName))) {
                        add(LicenceText(LicenceLabels.textLabel(asset.fileName), asset.text))
                    }
                }
            }
        return LicenceIndex(components = components, extraTexts = extraTexts)
    }

    private data class TextCandidate(
        val fileName: String,
        val path: String,
        val text: String,
    )

    /** Picks the licence file named after the component, preferring a matching version. */
    private fun bestNameMatch(
        title: String,
        appendix: Map<String, String>,
        assets: List<LicenceAsset>,
    ): TextCandidate? {
        val name = normalise(title.substringBefore(' '))
        if (name.isEmpty()) return null
        val version = title.split(' ').firstOrNull { it.firstOrNull()?.isDigit() == true }?.let(::normalise)
        val candidates =
            buildList {
                appendix.forEach { (path, text) -> add(TextCandidate(path.substringAfterLast('/'), path, text)) }
                assets.forEach { asset -> add(TextCandidate(asset.fileName, asset.path, asset.text)) }
            }
        return candidates
            .map { candidate ->
                val file = normalise(candidate.fileName)
                var score = 0
                if (file.startsWith(name)) {
                    score += 2
                } else if (file.contains(name)) {
                    score += 1
                }
                if (score > 0 && version != null && file.contains(version)) score += 1
                candidate to score
            }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<TextCandidate, Int>> { it.second }.thenBy { it.first.fileName.length })
            .firstOrNull()
            ?.first
    }

    private fun normalise(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }
}
