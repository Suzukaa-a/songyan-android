package app.songyan.ime.content

import java.io.InputStream
import java.io.OutputStream

/**
 * The dictionary files themselves: what a usable Rime dictionary file name looks like, and a
 * bounded copy so a picked or downloaded file never grows without a limit.
 */
internal object DictionaryImportFiles {
    const val SUFFIX = ".dict.yaml"

    /** Used when a storage provider only offers a display name that is not a safe path. */
    const val FALLBACK_NAME = "imported.dict.yaml"

    private const val MAX_NAME_BYTES = 240
    private const val COPY_BUFFER_BYTES = 64 * 1024
    private val SAFE_COMPONENT = Regex("^[A-Za-z0-9_.-]+$")

    fun isDictionaryName(name: String): Boolean = name.endsWith(SUFFIX)

    /**
     * The name handed to the parser. The imported dictionary's display name comes from its own
     * header, so a provider handing out a non-ASCII display name must not fail the import: such a
     * name is replaced by a fixed safe one and only the bytes matter.
     */
    fun stagingName(candidate: String?): String {
        val base =
            candidate
                .orEmpty()
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .trim()
        return if (isSafeDictionaryName(base)) base else FALLBACK_NAME
    }

    /**
     * [stagingName], made unique among [used] (several picked files may fall back to the same
     * safe name); records the result in [used].
     */
    fun uniqueStagingName(
        candidate: String?,
        used: MutableSet<String>,
    ): String {
        val base = stagingName(candidate)
        var name = base
        var index = 2
        while (!used.add(name.lowercase())) {
            name = base.removeSuffix(SUFFIX) + "-" + index + SUFFIX
            index++
        }
        return name
    }

    /** Mirrors the parser's path rule: a relative ASCII path made of [A-Za-z0-9_.-] components. */
    fun isSafeDictionaryName(name: String): Boolean {
        if (!isDictionaryName(name)) return false
        if (name.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) return false
        if (name.startsWith("/")) return false
        return name.split('/').all { it.isNotEmpty() && it != "." && it != ".." && SAFE_COMPONENT.matches(it) }
    }

    /** Copies [input] into [output], refusing anything above [limit]; returns the bytes copied. */
    fun copyCapped(
        input: InputStream,
        output: OutputStream,
        limit: Long,
    ): Long {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw DictionaryImportProblemException(DictionaryImportProblemKind.FILE_TOO_LARGE)
            output.write(buffer, 0, read)
        }
        return total
    }
}
