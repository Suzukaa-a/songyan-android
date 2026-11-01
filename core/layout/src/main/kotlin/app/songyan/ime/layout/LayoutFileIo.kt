package app.songyan.ime.layout

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * The only file IO in this module, kept behind an interface so the model stays free of platform
 * assumptions and tests can substitute an in-memory implementation.
 */
interface LayoutFileIo {
    /** The file text, or null when the file does not exist. Oversized files are rejected. */
    fun readTextOrNull(
        path: String,
        maximumBytes: Int,
    ): String?

    /** Replaces the file contents atomically so a reader never observes a torn write. */
    fun writeAtomically(
        path: String,
        text: String,
    )
}

/** The default [LayoutFileIo] over java.nio. Callers must not run this on the main thread. */
object LayoutFileIoImpl : LayoutFileIo {
    override fun readTextOrNull(
        path: String,
        maximumBytes: Int,
    ): String? {
        val file = Paths.get(path)
        return try {
            if (!Files.exists(file)) return null
            if (!Files.isRegularFile(file)) {
                throw LayoutException.Invalid(LayoutInvalidCode.UNREADABLE_SOURCE, "not a regular file")
            }
            if (Files.size(file) > maximumBytes) {
                throw LayoutException.Invalid(LayoutInvalidCode.RESOURCE_BUDGET_EXCEEDED, "file")
            }
            // Files.readString is a Java 11 API that Android's java.nio.file does not ship.
            Files.readAllBytes(file).decodeToString()
        } catch (error: LayoutException) {
            throw error
        } catch (error: IOException) {
            // An IO failure must stay a domain failure: callers map LayoutException onto UI copy.
            throw LayoutException.Invalid(LayoutInvalidCode.UNREADABLE_SOURCE, file.fileName?.toString() ?: path)
        }
    }

    override fun writeAtomically(
        path: String,
        text: String,
    ) {
        val target: Path = Paths.get(path).toAbsolutePath()
        val parent: Path = target.parent ?: Paths.get(".").toAbsolutePath()
        var temporary: Path? = null
        try {
            Files.createDirectories(parent)
            temporary = Files.createTempFile(parent, "layout", ".tmp")
            // Files.writeString is a Java 11 API that Android's java.nio.file does not ship.
            Files.write(temporary, text.encodeToByteArray())
            Files.move(
                temporary,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (error: IOException) {
            throw LayoutException.Invalid(LayoutInvalidCode.UNREADABLE_SOURCE, target.fileName?.toString() ?: path)
        } finally {
            temporary?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }
}
