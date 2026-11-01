package app.songyan.ime.rime

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class FakeAssetTree(
    files: Map<String, ByteArray> = emptyMap(),
) : AssetTree {
    private val files = files.toMutableMap()

    fun put(
        path: String,
        text: String,
    ) {
        files[path] = text.toByteArray(Charsets.UTF_8)
    }

    override fun list(path: String): List<String> {
        val prefix = if (path.isEmpty()) "" else "$path/"
        val children = sortedSetOf<String>()
        for (candidate in files.keys) {
            if (path.isNotEmpty() && !candidate.startsWith(prefix)) continue
            val rest = candidate.removePrefix(prefix)
            children += rest.substringBefore("/")
        }
        return children.toList()
    }

    override fun open(path: String): InputStream =
        files[path]?.let { ByteArrayInputStream(it) } ?: throw IOException("missing asset $path")

    override fun isDirectory(path: String): Boolean = files.keys.any { it.startsWith("$path/") }
}
