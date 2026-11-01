package app.songyan.ime.userconfig

import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Document ids of [RimeConfigProvider] and the checks that keep every id inside its folder.
 *
 * Ids are paths: `root`; `edit` and `edit/<path>` (the user's files, in folders that mirror the
 * source's layout); `active/...` (the source in use); `sources/<sourceId>/...` (each stored source). A caller can send any string, so
 * nothing here trusts one: empty, `.` and `..` segments, backslashes and NUL are refused, and a
 * resolved file must still be inside its folder once symbolic links are followed.
 */
object RimeConfigPaths {
    const val ROOT = "root"
    const val EDIT = "edit"
    const val ACTIVE = "active"
    const val SOURCES = "sources"

    private const val MAX_ID_LENGTH = 1024
    private const val MAX_NAME_ATTEMPTS = 99

    /** The source manifest the app writes into each source; not the user's to read as a Rime file. */
    private const val MANIFEST = "songyan-source.json"

    enum class Area { ROOT, EDIT, ACTIVE, SOURCES }

    data class Path(
        val area: Area,
        val segments: List<String>,
    )

    /** The parsed [documentId], or null when it names nothing this provider has. */
    fun parse(documentId: String): Path? {
        if (documentId.isEmpty() || documentId.length > MAX_ID_LENGTH) return null
        if (documentId == ROOT) return Path(Area.ROOT, emptyList())
        val parts = documentId.split('/')
        val area =
            when (parts.first()) {
                EDIT -> Area.EDIT
                ACTIVE -> Area.ACTIVE
                SOURCES -> Area.SOURCES
                else -> return null
            }
        val segments = parts.drop(1)
        if (segments.any { !isSafeSegment(it) }) return null
        if (area == Area.EDIT && segments.isNotEmpty()) {
            val path = segments.joinToString("/")
            if (!UserConfigFiles.isAllowedPath(path) && !UserConfigFiles.isAllowedFolderPath(path)) return null
        }
        return Path(area, segments)
    }

    private fun isSafeSegment(segment: String): Boolean =
        segment.isNotEmpty() &&
            segment != "." &&
            segment != ".." &&
            '\\' !in segment &&
            '\u0000' !in segment

    /** Files this provider never lists: hidden files (backups, work markers) and the app's own manifest. */
    fun hidden(name: String): Boolean = name.startsWith(".") || name == MANIFEST

    /** [segments] under [base], or null when the result would leave [base]. */
    fun inside(
        base: File,
        segments: List<String>,
    ): File? {
        if (segments.isEmpty() || segments.any { !isSafeSegment(it) || hidden(it) }) return null
        val file = segments.fold(base) { dir, segment -> File(dir, segment) }
        // A link is never followed, whatever it points at: the trees are the app's own and hold none.
        var step = base
        for (segment in segments) {
            step = File(step, segment)
            if (Files.isSymbolicLink(step.toPath())) return null
        }
        return try {
            val root = base.canonicalFile
            val resolved = file.canonicalFile
            resolved.takeIf { it.path.startsWith(root.path + File.separator) }
        } catch (_: IOException) {
            null
        }
    }

    /** Whether [documentId] is [parentId] itself or anywhere below it. */
    fun isChild(
        parentId: String,
        documentId: String,
    ): Boolean {
        if (parse(parentId) == null || parse(documentId) == null) return false
        return parentId == ROOT || documentId == parentId || documentId.startsWith("$parentId/")
    }

    /**
     * The file name for a new document called [displayName]: as given when it is a configuration
     * name, with `.yaml` or `.txt` added when only the MIME type says what it is, else null.
     */
    fun newFileName(
        displayName: String,
        mimeType: String,
    ): String? {
        val name = displayName.trim()
        if (UserConfigFiles.isCreatableName(name)) return name
        // Already a configuration file name, just not one this folder takes.
        if (name.endsWith(".yaml") || name.endsWith(".txt") || name.endsWith(".lua")) return null
        val extension =
            when (mimeType.lowercase()) {
                "text/plain" -> ".txt"
                "application/yaml", "application/x-yaml", "text/yaml", "text/x-yaml" -> ".yaml"
                else -> return null
            }
        return (name + extension).takeIf(UserConfigFiles::isCreatableName)
    }

    /** [name], or `stem-2.ext`, `stem-3.ext`… when it is [taken]; null when all are. */
    fun unique(
        name: String,
        allowed: (String) -> Boolean = UserConfigFiles::isCreatableName,
        taken: (String) -> Boolean,
    ): String? {
        if (!taken(name)) return name
        val extension = listOf(".yaml", ".lua").firstOrNull { name.endsWith(it) } ?: ".txt"
        val stem = name.removeSuffix(extension)
        // `rime_ice.custom.yaml` becomes `rime_ice.custom-2.yaml`: still a configuration name.
        return (2..MAX_NAME_ATTEMPTS)
            .asSequence()
            .map { "$stem-$it$extension" }
            .firstOrNull { allowed(it) && !taken(it) }
    }
}
