package app.songyan.ime.rime

import android.content.res.AssetManager
import java.io.File
import java.io.InputStream

/**
 * Minimal read-only view of a bundled asset directory, abstracted so the
 * staging logic can be unit-tested on the JVM without an Android runtime.
 */
interface AssetTree {
    /** Direct child names of [path] (relative, "/"-separated, "" for the root). */
    fun list(path: String): List<String>

    /** Opens a regular file at [path]. */
    fun open(path: String): InputStream

    /** True when [path] names a directory. */
    fun isDirectory(path: String): Boolean
}

class AndroidAssetTree(
    private val assets: AssetManager,
    private val root: String,
) : AssetTree {
    override fun list(path: String): List<String> {
        val prefix = if (path.isEmpty()) root else "$root/$path"
        return assets.list(prefix)?.toList().orEmpty()
    }

    override fun open(path: String): InputStream = assets.open("$root/$path")

    override fun isDirectory(path: String): Boolean {
        val prefix = if (path.isEmpty()) root else "$root/$path"
        return assets.list(prefix)?.isNotEmpty() == true
    }
}

/** Reads every file under [tree]'s root, keyed by "/"-separated relative path. */
internal fun AssetTree.readAllFiles(prefix: String = ""): Map<String, ByteArray> {
    val files = LinkedHashMap<String, ByteArray>()
    for (name in list(prefix)) {
        val childPath = if (prefix.isEmpty()) name else prefix + "/" + name
        if (isDirectory(childPath)) {
            files.putAll(readAllFiles(childPath))
        } else {
            files[childPath] = open(childPath).use { it.readBytes() }
        }
    }
    return files
}

internal fun copyAssetFile(
    tree: AssetTree,
    assetPath: String,
    target: File,
) {
    target.parentFile?.mkdirs()
    val temp = File(target.parentFile, target.name + ".tmp")
    tree.open(assetPath).use { input ->
        temp.outputStream().use { output -> input.copyTo(output) }
    }
    if (!temp.renameTo(target)) {
        temp.copyTo(target, overwrite = true)
        temp.delete()
    }
}
