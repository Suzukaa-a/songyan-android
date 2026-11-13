package app.songyan.ime.settings.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The recommended catalogue follows the device's own language, never the app's: a Taiwanese phone
 * offers 注音 and 倉頡 first, a Hong Kong phone 倉頡, 速成 and 粵拼, a Japanese phone the Japanese
 * entry, and every other phone keeps the order the catalogue is written in. Every entry stays
 * visible in every case.
 */
class RecommendedSchemesOrderTest {
    private val catalog = RecommendedSchemes.catalog

    private fun ordered(vararg systemLanguageTags: String): List<String> =
        RecommendedSchemes.ordered(catalog, systemLanguageTags.toList()).map { it.id }

    @Test
    fun taiwanOffersZhuyinAndCangjieFirst() {
        assertEquals(listOf("rime-bopomofo", "rime-cangjie") + REST, ordered("zh-TW"))
    }

    @Test
    fun hongKongAndMacaoOfferCangjieQuickAndCantoneseFirst() {
        val expected = listOf("rime-cangjie", "rime-quick", "rime-cantonese") + REST_WITHOUT_QUICK
        assertEquals(expected, ordered("zh-HK"))
        assertEquals(expected, ordered("zh-MO"))
    }

    @Test
    fun everyOtherLanguageKeepsTheCatalogOrder() {
        assertEquals(DEFAULT_ORDER, ordered("en-US"))
        assertEquals(DEFAULT_ORDER, ordered("zh-CN"))
        assertEquals(DEFAULT_ORDER, ordered("zh-Hans-HK"))
        assertEquals(DEFAULT_ORDER, ordered("zh-Hans-TW"))
        assertEquals(DEFAULT_ORDER, ordered("fr-FR"))
        assertEquals(DEFAULT_ORDER, ordered())
    }

    @Test
    fun everyEntryStaysVisibleWhicheverLanguageTheDeviceUses() {
        val languages =
            listOf(
                listOf("zh-TW"),
                listOf("zh-HK"),
                listOf("zh-MO"),
                listOf("ja-JP"),
                listOf("en-US"),
            )
        val everyEntry = catalog.map { it.id }.toSet()
        for (tags in languages) {
            val ordered = RecommendedSchemes.ordered(catalog, tags)
            assertEquals("entries under " + tags, everyEntry, ordered.map { it.id }.toSet())
            assertEquals("no entry repeats under " + tags, catalog.size, ordered.size)
        }
    }

    @Test
    fun japaneseIsUpstreamsPrebuiltKagiroiAndLeadsForJapanese() {
        val kagiroi = catalog.first { it.id == "rime-kagiroi" }
        assertTrue(kagiroi.main.url.endsWith("/1c4cd914a92ec433e2c90436414d7d05eeded6c9"))
        assertEquals("4091251313f98b76c49a340f21b94ae0d606993e4134c7c8ba0c5d969c6fa4a2", kagiroi.main.sha256)
        assertEquals(67_182_927L, kagiroi.downloadBytes)
        assertTrue(kagiroi.extras.isEmpty())
        assertEquals(listOf("kagiroi"), kagiroi.advertisedSchemas)
        assertEquals(listOf("GPL-3.0", "Mozc"), kagiroi.licenses)

        assertEquals(listOf("rime-kagiroi") + DEFAULT_ORDER.dropLast(1), ordered("ja-JP"))
    }

    @Test
    fun theDevicesPrimaryLanguageDecides() {
        assertEquals(DEFAULT_ORDER, ordered("en-US", "zh-TW"))
        assertEquals(listOf("rime-bopomofo", "rime-cangjie") + REST, ordered("zh-TW", "en-US"))
    }

    @Test
    fun aRegionalTagThatNamesItsRegionMatches() {
        assertEquals(listOf("rime-bopomofo", "rime-cangjie") + REST, ordered("zh-Hant-TW"))
    }

    @Test
    fun aStoredPackageLinkFindsItsCatalogueEntry() {
        val bopomofo = catalog.first { it.id == "rime-bopomofo" }
        assertEquals("rime-bopomofo", RecommendedSchemes.forLink(bopomofo.main.url)?.id)
        assertNull(RecommendedSchemes.forLink("https://example.com/other.zip"))
        assertNull(RecommendedSchemes.forLink(null))
    }

    @Test
    fun theCatalogPinsEveryArchiveWithItsDigestSizeAndLicence() {
        assertEquals(DEFAULT_ORDER, catalog.map { it.id })

        val hex = Regex("[0-9a-f]{64}")
        for (scheme in catalog) {
            assertTrue(scheme.id + ": main archive over https", scheme.main.url.startsWith("https://"))
            assertTrue(scheme.id + ": a pinned digest", hex.matches(scheme.main.sha256))
            assertTrue(scheme.id + ": a size hint", scheme.main.bytes > 0)
            assertTrue(scheme.id + ": a licence", scheme.licenses.isNotEmpty())
            assertTrue(scheme.id + ": an upstream homepage", scheme.homepage.startsWith("https://"))
            for (extra in scheme.extras) {
                assertTrue(scheme.id + ": extra over https", extra.url.startsWith("https://"))
                assertTrue(scheme.id + ": extra digest", hex.matches(extra.sha256))
                assertTrue(scheme.id + ": extra size", extra.bytes > 0)
            }
            val total = scheme.archives.sumOf { it.bytes }
            assertEquals("the size covers every archive", total, scheme.downloadBytes)
        }
    }

    @Test
    fun theExistingEntriesKeepTheirPinnedPackages() {
        val ice = catalog.first { it.id == "rime-ice" }
        assertTrue(ice.main.url.endsWith("fbb516b2786e4d5444383706d13c31c2e4d10c08"))
        assertEquals("4c6b9c84115e9aa24cfaf55e389363785451943fe70e4e7670b2ec59fc0056d3", ice.main.sha256)
        val wanxiang = catalog.first { it.id == "rime-wanxiang" }
        assertTrue(wanxiang.main.url.contains("/releases/download/v18.1.3/"))
        assertEquals("fc6495ba8489a898737ca29883dedaf61ff3baf593c78900dc59102c919d0e63", wanxiang.main.sha256)
        // Their own default.yaml stays authoritative: nothing narrows what they offer.
        assertTrue(ice.advertisedSchemas.isEmpty())
        assertTrue(wanxiang.advertisedSchemas.isEmpty())
    }

    @Test
    fun theTraditionalEntriesOfferTheirDeclaredChoicesAndPinTheirDependencies() {
        val expected =
            mapOf(
                "rime-bopomofo" to
                    Pair(
                        listOf("bopomofo_tw", "bopomofo"),
                        listOf("rime-terra-pinyin", "rime-essay", "rime-stroke", "rime-luna-pinyin"),
                    ),
                "rime-cangjie" to
                    Pair(
                        listOf("cangjie5"),
                        listOf("rime-luna-pinyin", "rime-essay", "rime-stroke"),
                    ),
                "rime-quick" to
                    Pair(
                        listOf("quick5"),
                        listOf("rime-cangjie", "rime-luna-pinyin", "rime-essay", "rime-stroke"),
                    ),
                "rime-cantonese" to
                    Pair(
                        listOf("jyut6ping3"),
                        listOf(
                            "rime-luna-pinyin",
                            "rime-stroke",
                            "rime-cangjie",
                            "rime-loengfan",
                            "rime-emoji-cantonese",
                            "rime-essay",
                        ),
                    ),
            )
        for ((id, expectation) in expected) {
            val scheme = catalog.first { it.id == id }
            val (choices, extras) = expectation
            assertEquals(id + ": the offered input methods, default first", choices, scheme.advertisedSchemas)
            assertEquals(id + ": the pinned dependency archives", extras, scheme.extras.map { repositoryOf(it.url) })
            assertTrue(id + ": the main archive is the entry", scheme.main.url.contains("/" + id + "/zip/"))
        }
    }

    @Test
    fun aDownloadSizeReadsAsAShortLabel() {
        assertEquals("906 B", downloadSizeLabel(906))
        assertEquals("12 KB", downloadSizeLabel(12_487))
        assertEquals("16.9 MB", downloadSizeLabel(16_944_925))
    }

    /** "https://codeload.github.com/rime/rime-essay/zip/<sha>" names the repository "rime-essay". */
    private fun repositoryOf(url: String): String {
        val parts = url.removePrefix("https://").split("/")
        assertEquals("codeload link: " + url, 5, parts.size)
        return parts[2]
    }

    /** Only here to keep the tag parsing the order relies on honest: a region tag parses to a country. */
    @Test
    fun languageTagsParseAsLocales() {
        assertEquals("TW", Locale.forLanguageTag("zh-TW").country)
        assertEquals("Hant", Locale.forLanguageTag("zh-Hant-TW").script)
    }

    private companion object {
        val DEFAULT_ORDER =
            listOf(
                "rime-ice",
                "rime-wanxiang",
                "rime-bopomofo",
                "rime-cangjie",
                "rime-quick",
                "rime-cantonese",
                "rime-kagiroi",
            )
        val REST = listOf("rime-ice", "rime-wanxiang", "rime-quick", "rime-cantonese", "rime-kagiroi")
        val REST_WITHOUT_QUICK = listOf("rime-ice", "rime-wanxiang", "rime-bopomofo", "rime-kagiroi")
    }
}
