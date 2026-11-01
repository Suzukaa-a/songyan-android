import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import java.io.File
import java.security.MessageDigest

/**
 * A short content hash of directory trees, for build stamps that must change exactly when the
 * bundled data or the engine sources change. Paths are hashed relative to their root and in a fixed
 * order, so the value is the same on every checkout; dot files (`.DS_Store`, editor state) are
 * skipped. As a ValueSource it is re-evaluated on every build, also with the configuration cache.
 */
abstract class ContentHashValueSource : ValueSource<String, ContentHashValueSource.Parameters> {
    interface Parameters : ValueSourceParameters {
        /** Absolute paths of the directory trees to hash. */
        val roots: ListProperty<String>
    }

    override fun obtain(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (rootPath in parameters.roots.get()) {
            val root = File(rootPath)
            check(root.isDirectory) { "content hash root is missing: $root" }
            root
                .walkTopDown()
                .onEnter { it == root || !it.name.startsWith(".") }
                .filter { it.isFile && !it.name.startsWith(".") }
                .map { it.relativeTo(root).invariantSeparatorsPath to it }
                .sortedBy { it.first }
                .forEach { (relative, file) ->
                    digest.update(root.name.toByteArray())
                    digest.update(0)
                    digest.update(relative.toByteArray())
                    digest.update(0)
                    digest.update(file.readBytes())
                    digest.update(0)
                }
        }
        return digest.digest().take(HASH_BYTES).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val HASH_BYTES = 8
    }
}
