package app.songyan.ime.settings.platform

import android.content.Context
import android.content.res.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** The readable licence index built from the bundled notice document and licence files. */
data class LicenseBundle(
    val index: LicenceIndex,
)

/**
 * Loads the licence material the APK ships: the notice document shared with the iOS build and its
 * licence files (`assets/legal`), the Android-only notices (`assets/legal-android`), the engine
 * licence files (`assets/rime/licenses`), and the starter dictionary and emoji data notices. Each
 * notice points at licence files in any of those places.
 */
object LicenseCatalog {
    private const val LEGAL = "legal"
    private const val NOTICES = "legal/ThirdPartyNotices.txt"
    private const val LEGAL_ANDROID = "legal-android"
    private const val ANDROID_NOTICES = "legal-android/AndroidNotices.txt"
    private const val ENGINE_LICENCES = "rime/licenses"
    private const val ANDROID_STARTER = "rime/starter"
    private const val ANDROID_NOTICE = "rime/starter/NOTICE.md"
    private const val EMOJI_DATA = "emoji"
    private const val EMOJI_NOTICE = "emoji/NOTICE.md"
    private const val MAX_DEPTH = 3

    suspend fun load(context: Context): LicenseBundle =
        withContext(Dispatchers.IO) {
            val assets =
                buildList {
                    collectLicenceFiles(context.assets, LEGAL, 0, this)
                    collectLicenceFiles(context.assets, LEGAL_ANDROID, 0, this)
                    collectLicenceFiles(context.assets, ENGINE_LICENCES, 0, this)
                    collectLicenceFiles(context.assets, ANDROID_STARTER, 0, this)
                    collectLicenceFiles(context.assets, EMOJI_DATA, 0, this)
                }.filter { it.path != ANDROID_NOTICE && it.path != EMOJI_NOTICE }.distinctBy { it.path }
            // Android-only material (starter dictionary, emoji data, libraries) has its own notice
            // files in the same format, listed ahead of the engine notices shared with iOS.
            val noticesText =
                listOfNotNull(
                    readAsset(context.assets, ANDROID_NOTICE),
                    readAsset(context.assets, EMOJI_NOTICE),
                    readAsset(context.assets, ANDROID_NOTICES),
                    readAsset(context.assets, NOTICES),
                ).joinToString("\n\n")
            LicenseBundle(
                index =
                    LicenceIndexBuilder.build(
                        noticesText = noticesText,
                        assets = assets,
                    ),
            )
        }

    private fun collectLicenceFiles(
        assets: AssetManager,
        path: String,
        depth: Int,
        out: MutableList<LicenceAsset>,
    ) {
        if (depth > MAX_DEPTH) return
        val children =
            try {
                assets.list(path)
            } catch (e: IOException) {
                null
            } ?: return
        if (children.isEmpty()) {
            if (path == NOTICES || path == ANDROID_NOTICES || !isLicenceFile(path)) return
            val text = readAsset(assets, path) ?: return
            out += LicenceAsset(path = path, text = text)
            return
        }
        children.forEach { child -> collectLicenceFiles(assets, "$path/$child", depth + 1, out) }
    }

    private fun isLicenceFile(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return name.endsWith(".txt") || name.endsWith(".md") || name.startsWith("COPYING") || name.contains("LICENSE")
    }

    private fun readAsset(
        assets: AssetManager,
        path: String,
    ): String? =
        try {
            assets.open(path).bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            null
        }

    /** "librime-1.17.0-LICENSE" for "legal/licenses/librime-1.17.0-LICENSE.txt". */
    internal fun titleOf(assetPath: String): String {
        val name = assetPath.substringAfterLast('/').substringBeforeLast('.')
        return name
    }
}
