package app.songyan.ime.settings.state

import androidx.annotation.StringRes
import app.songyan.ime.settings.R

/**
 * An input scheme the settings screen suggests. It is only a pinned link: the package is downloaded
 * from the public upstream repository after the user confirms, never bundled, and goes through the
 * same checks as any other link.
 */
data class RecommendedScheme(
    val id: String,
    @StringRes val nameRes: Int,
    @StringRes val detailRes: Int,
    val url: String,
)

object RecommendedSchemes {
    /** Same pinned versions as the iOS catalog; they change only together with it. */
    val catalog: List<RecommendedScheme> =
        listOf(
            RecommendedScheme(
                id = "rime-ice",
                nameRes = R.string.schemes_recommended_rime_ice_name,
                detailRes = R.string.schemes_recommended_rime_ice_detail,
                url = "https://codeload.github.com/iDvel/rime-ice/zip/fbb516b2786e4d5444383706d13c31c2e4d10c08",
            ),
            // A release asset, not the repository ZIP: the compiled OpenCC dictionaries that the
            // package's opencc/*.json files reference ship only in the release.
            RecommendedScheme(
                id = "rime-wanxiang",
                nameRes = R.string.schemes_recommended_wanxiang_name,
                detailRes = R.string.schemes_recommended_wanxiang_detail,
                url = "https://github.com/amzxyz/rime-wanxiang/releases/download/v18.1.3/rime-wanxiang-base.zip",
            ),
        )
}

/**
 * A scheme package another app handed to the settings activity (share or open). The activity offers
 * it; the screen asks the user before anything is imported, because the sender is not trusted.
 */
object IncomingPackageHolder {
    private val pending = kotlinx.coroutines.flow.MutableStateFlow<android.net.Uri?>(null)

    val uri: kotlinx.coroutines.flow.StateFlow<android.net.Uri?> get() = pending

    fun offer(uri: android.net.Uri) {
        pending.value = uri
    }

    fun clear() {
        pending.value = null
    }
}
