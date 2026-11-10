package app.songyan.ime.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Base64
import java.util.UUID

/** Source bytes are immutable and included in device transfer; generations hold references. */
internal class PrebuiltResourceStore(
    private val storage: AppStorage,
) {
    private val root = File(storage.sourcesDir, ".compiled")
    private val previous by lazy { GenerationPublisher(storage).publishedRecords() }

    fun publish(
        directory: File,
        original: File?,
        work: File,
        destination: File,
        generation: String,
        sourceId: String,
        revision: String,
    ) {
        val input = original?.takeIf { it.isFile }?.let { retainInput(directory, it, sourceId, revision) }
        val retained =
            if (input != null && sameBytes(input, work)) {
                input
            } else {
                previousOutput(sourceId, work)
                    ?: File(root, "generation/" + generation + "/" + work.name).also { copyImmutable(work, it) }
            }
        replaceWithLink(destination, retained)
    }

    private fun previousOutput(
        sourceId: String,
        work: File,
    ): File? {
        for (record in previous.filter { it.sourceId == sourceId }) {
            val prior = File(storage.generationDir(record.generation), "build/" + work.name).canonicalFile
            if (prior.path.startsWith(root.canonicalPath + File.separator) && prior.isFile && sameBytes(prior, work)) {
                return prior
            }
        }
        return null
    }

    private fun retainInput(
        directory: File,
        input: File,
        sourceId: String,
        revision: String,
    ): File {
        if (Files.isSymbolicLink(input.toPath())) {
            val resolved = input.canonicalFile
            if (resolved.path.startsWith(root.canonicalPath + File.separator)) return resolved
        }
        val descriptor = manifest(directory)
        val retained =
            File(sourceVersion(descriptor?.sourceId ?: sourceId, descriptor?.revision ?: revision), input.name)
        copyImmutable(input, retained)
        replaceWithLink(input, retained)
        return retained
    }

    /** Commit the copy before changing its source reference, so interruption cannot lose source data. */
    private fun copyImmutable(
        source: File,
        target: File,
    ) {
        target.parentFile.mkdirs()
        if (target.isFile) {
            check(sameBytes(source, target)) { "compiled input changed without a source revision: " + target }
            return
        }
        val temporary = File(target.parentFile, ".copy-" + UUID.randomUUID())
        try {
            source.inputStream().use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output, BUFFER_SIZE)
                    output.fd.sync()
                }
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            var parent = target.parentFile
            while (parent.path.startsWith(storage.sourcesDir.path)) {
                syncDirectory(parent)
                if (parent == storage.sourcesDir) break
                parent = parent.parentFile
            }
        } finally {
            temporary.delete()
        }
    }

    private fun replaceWithLink(
        reference: File,
        target: File,
    ) {
        reference.parentFile.mkdirs()
        val temporary = File(reference.parentFile, ".compiled-link-" + UUID.randomUUID())
        try {
            Files.createSymbolicLink(temporary.toPath(), target.absoluteFile.toPath())
            Files.move(
                temporary.toPath(),
                reference.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            syncDirectory(reference.parentFile)
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    /** Transfer restores regular pool files, not symlinks; the source manifest locates its input again. */
    fun restoreSource(directory: File) {
        val descriptor = manifest(directory) ?: return
        for (file in sourceVersion(descriptor.sourceId, descriptor.revision).listFiles().orEmpty()) {
            if (!file.isFile || !isBin(file.name)) continue
            val reference = File(directory, "build/" + file.name)
            if (!reference.isFile) replaceWithLink(reference, file)
        }
    }

    /** Current sources, undoable sources and retained generations keep their referenced files. */
    fun prune() {
        if (!root.isDirectory) return
        val retained = HashSet<String>()
        storage.rimeRoot
            .walkTopDown()
            .onEnter { directory ->
                directory != root && !Files.isSymbolicLink(directory.toPath())
            }.forEach { file ->
                if (Files.isSymbolicLink(file.toPath())) {
                    val target = file.canonicalFile
                    if (target.path.startsWith(root.canonicalPath + File.separator)) retained += target.path
                } else if (file.isFile && file.name == SourceCatalog.MANIFEST) {
                    manifest(file.parentFile)?.let { descriptor ->
                        sourceVersion(descriptor.sourceId, descriptor.revision)
                            .listFiles()
                            .orEmpty()
                            .filter { it.isFile && isBin(it.name) }
                            .forEach { retained += it.canonicalPath }
                    }
                }
            }
        root.walkBottomUp().forEach { file ->
            if (file.isFile && file.canonicalPath !in retained) {
                file.delete()
            } else if (file.isDirectory && file.list().isNullOrEmpty()) {
                file.delete()
            }
        }
    }

    private fun syncDirectory(directory: File) {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    private fun sourceVersion(
        sourceId: String,
        revision: String,
    ): File = File(root, "source/" + encoded(sourceId) + "/" + encoded(revision))

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private val SUFFIXES = listOf(".table.bin", ".reverse.bin", ".prism.bin")

        fun isBin(name: String): Boolean = SUFFIXES.any { name.endsWith(it) && name.length > it.length }

        private fun encoded(value: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

        private fun manifest(directory: File): SourceManifest? =
            try {
                val file = File(directory, SourceCatalog.MANIFEST)
                if (file.isFile && file.length() <= 256 * 1024) SourceManifest.parse(file.readText()) else null
            } catch (_: IOException) {
                null
            }

        fun restore(directory: File) {
            if (directory.parentFile?.name != "sources") return
            val files = directory.parentFile.parentFile.parentFile
            PrebuiltResourceStore(AppStorage(files)).restoreSource(directory)
        }

        private fun sameBytes(
            a: File,
            b: File,
        ): Boolean {
            if (a.length() != b.length()) return false
            a.inputStream().use { left ->
                b.inputStream().use { right ->
                    val x = ByteArray(BUFFER_SIZE)
                    val y = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val size = readChunk(left, x)
                        if (readChunk(right, y) != size) return false
                        for (index in 0 until size) if (x[index] != y[index]) return false
                        if (size == 0) return true
                    }
                }
            }
        }

        private fun readChunk(
            input: InputStream,
            buffer: ByteArray,
        ): Int {
            var total = 0
            while (total < buffer.size) {
                val count = input.read(buffer, total, buffer.size - total)
                if (count < 0) break
                total += count
            }
            return total
        }
    }
}
