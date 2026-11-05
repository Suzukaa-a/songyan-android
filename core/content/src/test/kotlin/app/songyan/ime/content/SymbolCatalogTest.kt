package app.songyan.ime.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from QuickContentKitTests: bundled default data and the strict catalog decoder. */
class SymbolCatalogTest {
    @Test
    fun bundledDefaultDecodesAndSearches() {
        val catalog = SymbolCatalog.bundledDefault()
        catalog.validate()
        assertTrue(catalog.items.size >= 80)
        // Searching jiantou / pingfangmi finds the arrow / square-metre symbols.
        assertTrue(catalog.search("jiantou").any { it.text == "→" })
        assertTrue(catalog.search("pingfangmi").any { it.text == "㎡" })
        // Search by Chinese name.
        assertTrue(catalog.search("箭头").any { it.text == "→" })
        assertTrue(catalog.search("平方米").any { it.text == "㎡" })
        // The symbol text itself matches.
        assertTrue(catalog.search("≤").any { it.text == "≤" })
        // Case, whitespace and full-width normalization.
        assertTrue(catalog.search(" JianTou ").any { it.text == "→" })
        assertTrue(catalog.search("ＰＩＮＧＦＡＮＧＭＩ").any { it.text == "㎡" })
        // Name prefix outranks alias contains: deterministic ordering.
        assertEquals("→", catalog.search("箭头").first().text)
        // An empty query returns nothing; the panel then browses by category.
        assertTrue(catalog.search("   ").isEmpty())
        // Every item sits in an ordered category; no data is dropped.
        val categoryIds = catalog.orderedCategories.map { it.id }.toSet()
        assertTrue(catalog.items.all { it.category in categoryIds })
    }

    /** A later prefix match must outrank an earlier contains match. */
    @Test
    fun searchKeepsRankingWhenAliasesMatchDifferentWays() {
        val catalog =
            SymbolCatalog(
                categories = emptyList(),
                items =
                    listOf(
                        SymbolItem("text", "AB", "Other", emptyList(), "c"),
                        SymbolItem("alias-contains", "1", "Other", listOf("xx ab yy"), "c"),
                        SymbolItem("name-contains", "2", "xx ab yy", listOf("zzabzz"), "c"),
                        SymbolItem("alias-prefix", "3", "xxab", listOf("zzabzz", "ＡＢ tail"), "c"),
                        SymbolItem("alias-prefix-next", "4", "Other", listOf("ab next"), "c"),
                        SymbolItem("name-prefix", "5", "AB name", emptyList(), "c"),
                    ),
            )
        assertEquals(
            listOf(
                "name-prefix",
                "alias-prefix",
                "alias-prefix-next",
                "name-contains",
                "alias-contains",
                "text",
            ),
            catalog.search(" ＡＢ ").map { it.id },
        )
    }

    @Test
    fun bundledKaomojiSearchAndRoundTrip() {
        val catalog = SymbolCatalog.bundledDefault()
        assertTrue(catalog.search("tanshou").any { it.category == "kaomoji" })
        assertTrue(catalog.search("你好").any { it.category == "kaomoji" })
        assertEquals(catalog, SymbolCatalog.decode(catalog.encode()))
    }

    @Test
    fun invalidUtf8IsRejectedBeforeDecoding() {
        val valid = "{\"version\":1,\"items\":[]}".toByteArray()
        // A dangling lead byte keeps the document JSON-like but makes the UTF-8 decode fail.
        val damaged = valid.copyOf(valid.size + 1)
        damaged[valid.size - 1] = 0xC3.toByte()
        val error = assertThrows(ContentException::class.java) { SymbolCatalog.decode(damaged) }
        assertEquals(ContentErrorCode.MALFORMED_JSON, error.code)
        val trailing = valid + byteArrayOf(0xFF.toByte())
        assertThrows(ContentException::class.java) { SymbolCatalog.decode(trailing) }
    }

    @Test
    fun strictDecodeRejectsUnknownAndInvalid() {
        val valid =
            "{\"version\":1,\"items\":[{\"id\":\"x-1\",\"text\":\"→\",\"name\":\"右箭头\"," +
                "\"aliases\":[\"jiantou\"],\"category\":\"arrows\"}]}"
        val decoded = SymbolCatalog.decode(valid.toByteArray())
        // Omitting categories is valid: unknown categories sort last.
        assertEquals(listOf("arrows"), decoded.orderedCategories.map { it.id })

        val invalid =
            listOf(
                "{\"version\":2,\"items\":[]}",
                "{\"version\":1,\"extra\":1,\"items\":[]}",
                "{\"version\":1,\"items\":[{\"id\":\"a b\",\"text\":\"→\",\"name\":\"箭头\"," +
                    "\"aliases\":[],\"category\":\"x\"}]}",
                "{\"version\":1,\"items\":[{\"id\":\"a\",\"text\":\"\",\"name\":\"箭头\"," +
                    "\"aliases\":[],\"category\":\"x\"}]}",
                "{\"version\":1,\"items\":[{\"id\":\"a\",\"text\":\"→\",\"name\":\"\"," +
                    "\"aliases\":[],\"category\":\"x\"}]}",
                "{\"version\":1,\"items\":[{\"id\":\"a\",\"text\":\"→\",\"name\":\"箭头\"," +
                    "\"aliases\":[],\"category\":\"\"}]}",
                "{\"version\":1,\"items\":[{\"id\":\"a\",\"text\":\"→\",\"name\":\"箭头\"," +
                    "\"aliases\":[\"\"],\"category\":\"x\"}]}",
                "{\"version\":1,\"items\":[{\"id\":\"a\",\"text\":\"→\",\"name\":\"箭头\"," +
                    "\"aliases\":[],\"category\":\"x\"},{\"id\":\"a\",\"text\":\"←\",\"name\":\"左箭头\"," +
                    "\"aliases\":[],\"category\":\"x\"}]}",
                "{\"version\":1,\"items\":[{\"id\":\"a\",\"text\":\"→\",\"name\":\"箭头\"," +
                    "\"aliases\":[],\"category\":\"x\"},{\"id\":\"b\",\"text\":\"→\",\"name\":\"箭头\"," +
                    "\"aliases\":[],\"category\":\"x\"}]}",
                "{\"version\":1,\"items\":[{\"id\":\"a\",\"text\":\"→\",\"name\":\"箭头\"," +
                    "\"aliases\":[],\"category\":\"x\",\"surprise\":1}]}",
            )
        for (json in invalid) {
            assertThrows(json, ContentException::class.java) { SymbolCatalog.decode(json.toByteArray()) }
        }
        // Re-encoding and re-importing round-trips.
        val round = SymbolCatalog.decode(valid.toByteArray())
        assertEquals(round, SymbolCatalog.decode(round.encode()))
    }

    @Test
    fun unsupportedVersionAndOversizeCarryCodes() {
        val version =
            assertThrows(
                ContentException::class.java,
            ) { SymbolCatalog.decode("{\"version\":2,\"items\":[]}".toByteArray()) }
        assertEquals(ContentErrorCode.UNSUPPORTED_VERSION, version.code)
        assertEquals("$.version", version.path)
        val oversize =
            assertThrows(ContentException::class.java) {
                SymbolCatalog.decode(ByteArray(SymbolCatalog.MAX_JSON_BYTES + 1) { 0x20 })
            }
        assertEquals(ContentErrorCode.OVERSIZE, oversize.code)
    }

    @Test
    fun unknownFieldCarriesPath() {
        val json =
            "{\"version\":1,\"items\":[{\"id\":\"a\",\"text\":\"→\",\"name\":\"箭头\"," +
                "\"aliases\":[],\"category\":\"x\",\"surprise\":1}]}"
        val error = assertThrows(ContentException::class.java) { SymbolCatalog.decode(json.toByteArray()) }
        assertEquals(ContentErrorCode.UNKNOWN_FIELD, error.code)
        assertEquals("$.items[0].surprise", error.path)
    }

    @Test
    fun orderedCategoriesPutUnknownOnesLast() {
        val catalog =
            SymbolCatalog(
                categories =
                    listOf(
                        SymbolCategory("b", "乙", 2),
                        SymbolCategory("a", "甲", 1),
                        SymbolCategory("c", "丙", 2),
                        SymbolCategory("empty", "空", 3),
                    ),
                items = listOf(SymbolItem("x", "→", "箭头", listOf("jiantou"), "zz")),
            )
        assertEquals(listOf("a", "b", "c", "empty", "zz"), catalog.orderedCategories.map { it.id })
        assertFalse(catalog.itemsIn("zz").isEmpty())
        assertTrue(catalog.itemsIn("empty").isEmpty())
    }
}
