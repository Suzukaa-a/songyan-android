package app.songyan.ime.content

import org.junit.Assume
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** Shared test helpers: temp directories, FIFOs and permission tweaks. */
internal object TestFiles {
    /** A fresh directory under the system temp dir. */
    fun directory(prefix: String = "content-test"): Path = Files.createTempDirectory(prefix)

    /**
     * Creates a FIFO at [parent]/[name] and a daemon writer that unblocks once something opens
     * it for reading, so a regressed blocking read fails the test instead of hanging it.
     */
    fun fifo(
        parent: Path,
        name: String,
        rescueData: ByteArray,
    ): Path {
        val fifo = parent.resolve(name)
        val process =
            try {
                ProcessBuilder("mkfifo", fifo.toString()).start()
            } catch (error: Exception) {
                Assume.assumeNoException(error)
                return fifo
            }
        Assume.assumeTrue("mkfifo", process.waitFor() == 0)
        Thread {
            try {
                Files.write(fifo, rescueData)
            } catch (error: IOException) {
                // The reader rejected the FIFO; nothing to unblock.
            }
        }.apply {
            isDaemon = true
            start()
        }
        return fifo
    }

    fun setPermissions(
        path: Path,
        permissions: String,
    ) {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions))
    }
}
