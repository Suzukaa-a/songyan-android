package app.songyan.ime.rime

import android.content.res.AssetManager
import java.io.File

/** Copies bundled asset directories into a test's private engine folders. */
object AssetTrees {
    /**
     * Copies the asset directory [assetPath] into [target], recursively. With [keepExisting] a
     * file already in [target] is left alone, so a fixture can lay its files over a base set.
     */
    fun copy(
        assets: AssetManager,
        assetPath: String,
        target: File,
        keepExisting: Boolean = false,
    ) {
        target.mkdirs()
        for (name in assets.list(assetPath).orEmpty()) {
            val childPath = "$assetPath/$name"
            val child = File(target, name)
            if (assets.list(childPath).orEmpty().isNotEmpty()) {
                copy(assets, childPath, child, keepExisting)
            } else if (!keepExisting || !child.exists()) {
                assets.open(childPath).use { input -> child.outputStream().use { input.copyTo(it) } }
            }
        }
    }
}
