package app.songyan.ime.settings.state

import androidx.annotation.StringRes
import app.songyan.ime.settings.R
import java.util.Locale

/**
 * One pinned upstream archive of a recommended entry: where to download it and the SHA-256 its
 * bytes must have. The digest is what makes a catalogue entry stable; a download that does not
 * match it is refused instead of installed.
 */
data class RecommendedArchive(
    val url: String,
    val sha256: String,
    val bytes: Long,
)

/**
 * An input scheme the settings screen suggests. It is only a set of pinned links: the package is
 * downloaded from the public upstream repositories after the user confirms, never bundled, and goes
 * through the same checks as any other link.
 *
 * A single entry can need several upstream archives: the entry package plus the dictionaries and
 * schemas it references. [advertisedSchemas] names the input methods the entry offers; the packages
 * it depends on stay loadable without being offered themselves.
 */
data class RecommendedScheme(
    val id: String,
    @StringRes val nameRes: Int,
    @StringRes val detailRes: Int,
    /** The entry package: its files keep their package-relative paths and its configuration wins. */
    val main: RecommendedArchive,
    /** The archives the entry's files depend on. */
    val extras: List<RecommendedArchive> = emptyList(),
    /** SPDX names of the licences the upstream archives ship. */
    val licenses: List<String>,
    val homepage: String,
    /**
     * The device languages this entry is made for. The device's own language puts matching entries
     * first; an empty list keeps the entry where the catalogue writes it.
     */
    val languageTags: List<String> = emptyList(),
    /**
     * The input methods the entry offers, primary first; empty keeps whatever the package's own
     * default.yaml lists. A package such as rime-bopomofo ships several root schemas and no
     * default.yaml, so without this every schema it ships would be offered as an input method.
     */
    val advertisedSchemas: List<String> = emptyList(),
) {
    /** Every archive of the entry, the entry package first. */
    val archives: List<RecommendedArchive> get() = listOf(main) + extras

    val downloadBytes: Long get() = archives.sumOf { it.bytes }
}

private val RIME_ICE_ARCHIVE =
    RecommendedArchive(
        url = "https://codeload.github.com/iDvel/rime-ice/zip/fbb516b2786e4d5444383706d13c31c2e4d10c08",
        sha256 = "4c6b9c84115e9aa24cfaf55e389363785451943fe70e4e7670b2ec59fc0056d3",
        bytes = 16_944_925,
    )

// A release asset, not the repository ZIP: the compiled OpenCC dictionaries that the package's
// opencc/*.json files reference ship only in the release.
private val RIME_WANXIANG_ARCHIVE =
    RecommendedArchive(
        url = "https://github.com/amzxyz/rime-wanxiang/releases/download/v18.1.3/rime-wanxiang-base.zip",
        sha256 = "fc6495ba8489a898737ca29883dedaf61ff3baf593c78900dc59102c919d0e63",
        bytes = 26_011_020,
    )

private val BOPOMOFO_ARCHIVE =
    RecommendedArchive(
        url = "https://codeload.github.com/rime/rime-bopomofo/zip/6085c9a38a4a728047862b33d67eee18aa86f3b9",
        sha256 = "97247bcd63774686e9f86f1920ab58bb272032cbb3a622f4ffd669ad8f40df00",
        bytes = 12_487,
    )

private val TERRA_PINYIN_ARCHIVE =
    RecommendedArchive(
        url = "https://codeload.github.com/rime/rime-terra-pinyin/zip/723e51bc266cf9464530c1ddedb856aa18e3da34",
        sha256 = "eed62b2a8bf56ac51c0931bb1c22a1e6b404a77be4984e7d3da787ef20d58fb5",
        bytes = 732_915,
    )

private val ESSAY_ARCHIVE =
    RecommendedArchive(
        url = "https://codeload.github.com/rime/rime-essay/zip/054920de4f54c9e5994276a96a4fc2a35cb51aa3",
        sha256 = "f1ed067efee9d13d53ac6b1bdc3a57873d48fcd375cdbda0e9c2ef0d79158bac",
        bytes = 2_577_894,
    )

private val STROKE_ARCHIVE =
    RecommendedArchive(
        url = "https://codeload.github.com/rime/rime-stroke/zip/1e8fff9b9494ddec23b0cbc526bcfd8171a6fd48",
        sha256 = "058ffc2a3ce4de7137a170cf38feeeef06dcfac040ed3d173589d43d7dd7fc95",
        bytes = 1_068_491,
    )

private val CANGJIE_ARCHIVE =
    RecommendedArchive(
        url = "https://codeload.github.com/rime/rime-cangjie/zip/fcb3d7dd94ac1304663d575727d24d8107f6b082",
        sha256 = "6e32880ad439d9f9966cf6e1d2c3524bbd1d24219800cda0d9122607b08ee295",
        bytes = 403_456,
    )

private val LUNA_PINYIN_ARCHIVE =
    RecommendedArchive(
        url = "https://codeload.github.com/rime/rime-luna-pinyin/zip/56b934b099dfbeab842320f13aa8b461a6ab3e42",
        sha256 = "96f96206361a9f5e9ec5af7f102cb1c6b04e3f394746150e5fa62ae87290f9c2",
        bytes = 398_121,
    )

private val QUICK_ARCHIVE =
    RecommendedArchive(
        url = "https://codeload.github.com/rime/rime-quick/zip/5dcdb9e353d314239e9c8cddc0f42d52da4837bb",
        sha256 = "ed0053fbc824398e94735bc040f3b10e7af32588f4a8ecc48c663cfb0ed475c0",
        bytes = 40_231,
    )

private val CANTONESE_ARCHIVE =
    RecommendedArchive(
        url = "https://codeload.github.com/rime/rime-cantonese/zip/259f0e48bba840c3a2e0d117539e96937f3d89bc",
        sha256 = "9ef08e9529e2afbd9b0f84917e5194d8e7e00808cddcb4769fd91e5df13ef96f",
        bytes = 4_819_857,
    )

private val LOENGFAN_ARCHIVE =
    RecommendedArchive(
        url = "https://codeload.github.com/CanCLID/rime-loengfan/zip/987ac95b02f957e8764a2f45222a4006c188ed50",
        sha256 = "81dbfca498be152bdb9f2a102f5700b1d9f8ac74062bb1fb0dd59abeeb3adca7",
        bytes = 564_392,
    )

private val EMOJI_CANTONESE_ARCHIVE =
    RecommendedArchive(
        url = "https://codeload.github.com/rime/rime-emoji-cantonese/zip/e89d9bdb1c454cf8dbc15db21cce0f6b1a54ed99",
        sha256 = "6436f0193048d6351f726f4a714ca779492f9abfed6d1bbe9abc01e38e9d0fb4",
        bytes = 48_321,
    )

private val RIME_ICE =
    RecommendedScheme(
        id = "rime-ice",
        nameRes = R.string.schemes_recommended_rime_ice_name,
        detailRes = R.string.schemes_recommended_rime_ice_detail,
        main = RIME_ICE_ARCHIVE,
        licenses = listOf("GPL-3.0"),
        homepage = "https://github.com/iDvel/rime-ice",
    )

private val RIME_WANXIANG =
    RecommendedScheme(
        id = "rime-wanxiang",
        nameRes = R.string.schemes_recommended_wanxiang_name,
        detailRes = R.string.schemes_recommended_wanxiang_detail,
        main = RIME_WANXIANG_ARCHIVE,
        licenses = listOf("CC-BY-4.0"),
        homepage = "https://github.com/amzxyz/rime-wanxiang",
    )

private val RIME_BOPOMOFO =
    RecommendedScheme(
        id = "rime-bopomofo",
        nameRes = R.string.schemes_recommended_bopomofo_name,
        detailRes = R.string.schemes_recommended_bopomofo_detail,
        main = BOPOMOFO_ARCHIVE,
        extras = listOf(TERRA_PINYIN_ARCHIVE, ESSAY_ARCHIVE, STROKE_ARCHIVE, LUNA_PINYIN_ARCHIVE),
        licenses = listOf("LGPL-3.0"),
        homepage = "https://github.com/rime/rime-bopomofo",
        languageTags = listOf("zh-TW"),
        advertisedSchemas = listOf("bopomofo_tw", "bopomofo"),
    )

private val RIME_CANGJIE =
    RecommendedScheme(
        id = "rime-cangjie",
        nameRes = R.string.schemes_recommended_cangjie_name,
        detailRes = R.string.schemes_recommended_cangjie_detail,
        main = CANGJIE_ARCHIVE,
        extras = listOf(LUNA_PINYIN_ARCHIVE, ESSAY_ARCHIVE, STROKE_ARCHIVE),
        licenses = listOf("LGPL-3.0"),
        homepage = "https://github.com/rime/rime-cangjie",
        languageTags = listOf("zh-TW", "zh-HK", "zh-MO"),
        advertisedSchemas = listOf("cangjie5"),
    )

private val RIME_QUICK =
    RecommendedScheme(
        id = "rime-quick",
        nameRes = R.string.schemes_recommended_quick_name,
        detailRes = R.string.schemes_recommended_quick_detail,
        main = QUICK_ARCHIVE,
        extras = listOf(CANGJIE_ARCHIVE, LUNA_PINYIN_ARCHIVE, ESSAY_ARCHIVE, STROKE_ARCHIVE),
        licenses = listOf("LGPL-3.0"),
        homepage = "https://github.com/rime/rime-quick",
        languageTags = listOf("zh-HK", "zh-MO"),
        advertisedSchemas = listOf("quick5"),
    )

private val RIME_CANTONESE =
    RecommendedScheme(
        id = "rime-cantonese",
        nameRes = R.string.schemes_recommended_cantonese_name,
        detailRes = R.string.schemes_recommended_cantonese_detail,
        main = CANTONESE_ARCHIVE,
        extras =
            listOf(
                LUNA_PINYIN_ARCHIVE,
                STROKE_ARCHIVE,
                CANGJIE_ARCHIVE,
                LOENGFAN_ARCHIVE,
                EMOJI_CANTONESE_ARCHIVE,
                ESSAY_ARCHIVE,
            ),
        licenses = listOf("CC-BY-4.0", "ODbL-1.0", "CC0-1.0", "LGPL-3.0"),
        homepage = "https://github.com/rime/rime-cantonese",
        languageTags = listOf("zh-HK", "zh-MO"),
        advertisedSchemas = listOf("jyut6ping3"),
    )

/**
 * Upstream's own prebuilt kagiroi: the `build` branch commit generated from source 808a1954. Compiling
 * the source dictionaries on a phone runs out of memory, so the entry takes the compiled tables as
 * upstream ships them, its dictionary set included.
 */
private val RIME_KAGIROI =
    RecommendedScheme(
        id = "rime-kagiroi",
        nameRes = R.string.schemes_recommended_kagiroi_name,
        detailRes = R.string.schemes_recommended_kagiroi_detail,
        main =
            RecommendedArchive(
                url = "https://codeload.github.com/rimeinn/rime-kagiroi/zip/1c4cd914a92ec433e2c90436414d7d05eeded6c9",
                sha256 = "4091251313f98b76c49a340f21b94ae0d606993e4134c7c8ba0c5d969c6fa4a2",
                bytes = 67_182_927,
            ),
        licenses = listOf("GPL-3.0", "Mozc"),
        homepage = "https://github.com/rimeinn/rime-kagiroi",
        languageTags = listOf("ja"),
        advertisedSchemas = listOf("kagiroi"),
    )

object RecommendedSchemes {
    /** Same pinned versions as the iOS catalog; they change only together with it. */
    val catalog: List<RecommendedScheme> =
        listOf(
            RIME_ICE,
            RIME_WANXIANG,
            RIME_BOPOMOFO,
            RIME_CANGJIE,
            RIME_QUICK,
            RIME_CANTONESE,
            RIME_KAGIROI,
        )

    /**
     * The entry a stored package was installed from, when [url] is a recommended entry's own link.
     * An update then downloads the entry's pinned archives again, digests included, instead of the
     * single link that would miss the dependencies the entry needs.
     */
    fun forLink(url: String?): RecommendedScheme? = catalog.firstOrNull { it.main.url == url }

    /**
     * The catalogue as the device's own language orders it: entries made for that language come
     * first, in catalogue order, and the rest keep theirs. [systemLanguageTags] is the device's
     * locale list, primary language first; the app's own language never takes part in this.
     */
    fun ordered(systemLanguageTags: List<String>): List<RecommendedScheme> = ordered(catalog, systemLanguageTags)

    internal fun ordered(
        catalog: List<RecommendedScheme>,
        systemLanguageTags: List<String>,
    ): List<RecommendedScheme> {
        val primary = systemLanguageTags.firstOrNull { it.isNotBlank() } ?: return catalog
        val system = runCatching { Locale.forLanguageTag(primary.trim()) }.getOrNull() ?: return catalog
        if (system.language.isBlank()) return catalog
        val promoted = catalog.filter { scheme -> scheme.languageTags.any { matches(it, system) } }
        if (promoted.isEmpty()) return catalog
        return promoted + catalog.filter { it !in promoted }
    }

    /** Whether [tag] names [system]'s language; a tag without a region matches every region. */
    private fun matches(
        tag: String,
        system: Locale,
    ): Boolean {
        val wanted = runCatching { Locale.forLanguageTag(tag) }.getOrNull() ?: return false
        if (!wanted.language.equals(system.language, ignoreCase = true)) return false
        val region = wanted.country
        if (region.isBlank()) return true
        return region.equals(system.country, ignoreCase = true)
    }
}

/** A short download size ("906 B", "12 KB", "16.9 MB"); the unit reads the same in every language. */
fun downloadSizeLabel(bytes: Long): String =
    when {
        bytes >= 1_000_000 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format(Locale.ROOT, "%.0f KB", bytes / 1_000.0)
        else -> "$bytes B"
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
