package app.songyan.ime.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The generations the input method process is loading or running, so the deploy process never
 * prunes a generation an engine still has memory-mapped (librime maps the compiled tables; deleting
 * them under a running engine ends in a crash on the next lookup).
 *
 * Only the input method writes the lease, one generation per line. It names the generation it is
 * about to load before loading it and drops the previous one only after releasing that engine, so
 * at every moment each generation in use is listed. A stale lease after a crash only keeps one or
 * two extra generations on disk until the next keyboard start rewrites it.
 */
class GenerationLease(
    private val storage: AppStorage,
) {
    fun leased(): Set<String> =
        try {
            if (storage.leaseFile.isFile) {
                storage.leaseFile
                    .readLines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.contains('/') }
                    .toSet()
            } else {
                emptySet()
            }
        } catch (_: IOException) {
            emptySet()
        }

    fun hold(generations: Collection<String>) {
        storage.rimeRoot.mkdirs()
        val temp = File(storage.rimeRoot, storage.leaseFile.name + ".tmp")
        FileOutputStream(temp).use { out ->
            out.write(generations.distinct().joinToString("\n").toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        Files.move(
            temp.toPath(),
            storage.leaseFile.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}
