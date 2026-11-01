package app.songyan.ime.content

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.stream.Collectors

/**
 * The only file IO of this module.
 *
 * Imports read a user-supplied file, so every read is bounded and refuses anything that is not
 * a regular file: a pipe would block the caller forever and a directory would read as empty.
 * Callers own any URI permission for the duration of the call, and must not run this on the
 * main thread.
 */
internal object BoundedFile {
    /** The file name reported by an error; the full path is deliberately not part of the format. */
    fun displayName(path: Path): String = path.fileName?.toString() ?: "$"

    /**
     * Reads [path] after resolving links, in at most [maximumBytes] bytes.
     *
     * Links are followed, then the resolved target must be a regular file; a symlink to a
     * regular file is therefore accepted, while a dangling link, a pipe or a directory is
     * rejected before it is opened.
     */
    fun read(
        path: Path,
        maximumBytes: Int,
    ): ByteArray {
        val name = displayName(path)
        val resolved =
            try {
                path.toRealPath()
            } catch (error: IOException) {
                throw ContentException(ContentErrorCode.NOT_A_REGULAR_FILE, name, error)
            }
        if (!Files.isRegularFile(resolved)) {
            throw ContentException(ContentErrorCode.NOT_A_REGULAR_FILE, name)
        }
        val size =
            try {
                Files.size(resolved)
            } catch (error: IOException) {
                throw ContentException(ContentErrorCode.STORAGE_UNAVAILABLE, name, error)
            }
        if (size > maximumBytes) throw ContentException(ContentErrorCode.OVERSIZE, name)
        return try {
            Files.newInputStream(resolved).use { stream ->
                // A file that grows after the size check still cannot exceed the budget.
                val bytes = stream.readNBytes(maximumBytes + 1)
                if (bytes.size > maximumBytes) throw ContentException(ContentErrorCode.OVERSIZE, name)
                bytes
            }
        } catch (error: IOException) {
            throw ContentException(ContentErrorCode.STORAGE_UNAVAILABLE, name, error)
        }
    }

    /**
     * Replaces [target] atomically so a reader never observes a torn file, creating parents.
     *
     * The rename orders visibility but is not durable by itself: the data is forced to the device
     * before the move, so a power loss cannot leave the target present-but-empty.
     */
    fun writeAtomically(
        target: Path,
        bytes: ByteArray,
    ) {
        val name = displayName(target)
        val parent = target.parent ?: throw ContentException(ContentErrorCode.STORAGE_UNAVAILABLE, name)
        var temporary: Path? = null
        try {
            Files.createDirectories(parent)
            sweepStaleTemporaries(parent)
            temporary = Files.createTempFile(parent, TEMPORARY_PREFIX, TEMPORARY_SUFFIX)
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(
                temporary,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            temporary = null
        } catch (error: IOException) {
            throw ContentException(ContentErrorCode.STORAGE_UNAVAILABLE, name, error)
        } finally {
            temporary?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    /**
     * Removes temporary files left behind by an interrupted write. Only files older than
     * [STALE_TEMPORARY_MILLIS] are swept, so a concurrent write that is about to be renamed is never
     * deleted from under its owner.
     */
    private fun sweepStaleTemporaries(parent: Path) {
        val deadline = System.currentTimeMillis() - STALE_TEMPORARY_MILLIS
        val stale =
            try {
                Files.list(parent).use { stream ->
                    stream
                        .filter { path ->
                            val file = path.fileName?.toString() ?: return@filter false
                            if (!file.startsWith(TEMPORARY_PREFIX) || !file.endsWith(TEMPORARY_SUFFIX)) {
                                return@filter false
                            }
                            val modified =
                                runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrNull()
                                    ?: return@filter false
                            modified < deadline && Files.isRegularFile(path)
                        }.collect(Collectors.toList())
                }
            } catch (error: IOException) {
                return
            }
        for (path in stale) runCatching { Files.deleteIfExists(path) }
    }

    /** Temporary file naming used by [writeAtomically]; the suffix is part of the sweep filter. */
    private const val TEMPORARY_PREFIX: String = ".content-tmp"

    private const val TEMPORARY_SUFFIX: String = ".tmp"

    /** Age above which a leftover temporary file is considered abandoned. */
    private const val STALE_TEMPORARY_MILLIS: Long = 60 * 60 * 1000
}
