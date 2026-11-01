package app.songyan.ime.rime

import android.content.res.AssetManager
import java.io.File

/** Helpers shared by the real-engine instrumented tests of this module. */
internal object EngineTestSupport {
    const val BUILTIN_ASSET_ROOT = "rime/builtin"
    const val FIXTURE_ASSET_ROOT = "engine-fixtures"

    fun copyAssetTree(
        assets: AssetManager,
        assetPath: String,
        target: File,
    ) {
        target.mkdirs()
        for (name in assets.list(assetPath).orEmpty()) {
            val childPath = "$assetPath/$name"
            val child = File(target, name)
            if (assets.list(childPath).orEmpty().isNotEmpty()) {
                copyAssetTree(assets, childPath, child)
            } else {
                assets.open(childPath).use { input -> child.outputStream().use { input.copyTo(it) } }
            }
        }
    }

    /** Feeds each character of [keys] as one key press. */
    suspend fun type(
        engine: RimeEngine,
        keys: String,
    ) {
        for (char in keys) {
            engine.processKey(
                RimeKey(requireNotNull(RimeKey.baseKeycode(char)) { "no keycode for '$char'" }),
            )
        }
    }

    /** Candidate texts of the current page. */
    suspend fun candidateTexts(engine: RimeEngine): List<String> = engine.readSnapshot().candidates.map { it.text }
}
