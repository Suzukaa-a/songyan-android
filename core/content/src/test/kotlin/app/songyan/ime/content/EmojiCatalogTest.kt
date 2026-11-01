package app.songyan.ime.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiCatalogTest {
    private val data =
        """
        #emoji-data 1 test
        @Smileys & Emotion
        😀	笑脸|高兴	grinning face|happy|smile	
        😭	大哭|哭	loudly crying face|cry|sob	
        @People & Body
        👋	挥手|你好	waving hand|hello|hi	👋🏻 👋🏼 👋🏽 👋🏾 👋🏿
        broken line without tabs
        	only-tabs
        @Food & Drink
        🍌	香蕉	banana|fruit	
        """.trimIndent()

    private val catalog = EmojiCatalog.parse(data.lineSequence())

    @Test
    fun groupsKeepTheirOrderAndEntries() {
        assertEquals(listOf("Smileys & Emotion", "People & Body", "Food & Drink"), catalog.categories.map { it.id })
        assertEquals(listOf("😀", "😭"), catalog.categories[0].emojis.map { it.emoji })
        val wave = catalog.categories[1].emojis.first { it.emoji == "👋" }
        assertEquals(5, wave.variants.size)
        assertEquals(listOf("挥手", "你好"), wave.zhKeywords)
    }

    @Test
    fun aDamagedLineCostsOneEmojiNotTheCatalog() {
        // Neither the line without tabs nor the one with no emoji becomes an entry, and parsing goes on.
        val all = catalog.categories.flatMap { it.emojis }.map { it.emoji }
        assertEquals(listOf("😀", "😭", "👋", "🍌"), all)
    }

    @Test
    fun chineseKeywordsFindEmoji() {
        assertEquals(listOf("😭"), catalog.search("大哭").map { it.emoji })
        assertEquals(listOf("🍌"), catalog.search("香蕉").map { it.emoji })
        assertEquals(
            "one character of a Chinese keyword is a valid query",
            listOf("😭"),
            catalog.search("哭").map { it.emoji },
        )
    }

    @Test
    fun exactBeatsPrefixBeatsContains() {
        val ranked =
            EmojiCatalog(
                listOf(
                    EmojiCategory(
                        "g",
                        listOf(
                            EmojiEntry("a", zhKeywords = listOf("大笑脸")),
                            EmojiEntry("b", zhKeywords = listOf("笑脸大")),
                            EmojiEntry("c", zhKeywords = listOf("笑脸")),
                        ),
                    ),
                ),
            )
        assertEquals(listOf("c", "b", "a"), ranked.search("笑脸").map { it.emoji })
    }

    @Test
    fun englishMatchesAreCaseInsensitiveAndNeverASingleLetter() {
        assertEquals(listOf("😭"), catalog.search("CRY").map { it.emoji })
        assertTrue(catalog.search("s").isEmpty())
        assertTrue("a two-letter fragment inside a word is noise", catalog.search("an").isEmpty())
        assertEquals(listOf("🍌"), catalog.search("nan").map { it.emoji })
    }

    @Test
    fun blankQueryAndZeroLimitFindNothing() {
        assertTrue(catalog.search("  ").isEmpty())
        assertTrue(catalog.search("笑脸", limit = 0).isEmpty())
    }

    @Test
    fun aRecentVariantFindsItsBaseEntry() {
        assertEquals("👋", catalog.find("👋🏽")?.emoji)
        assertEquals("😀", catalog.find("😀")?.emoji)
        assertNull(catalog.find("🦄"))
        assertNotNull(catalog.find("🍌"))
    }

    @Test
    fun filteringDropsRefusedEmojiVariantsAndEmptyCategories() {
        val kept = catalog.filtered { it != "😀" && it != "👋🏿" && it != "🍌" }
        assertEquals(listOf("Smileys & Emotion", "People & Body"), kept.categories.map { it.id })
        assertEquals(listOf("😭"), kept.categories[0].emojis.map { it.emoji })
        assertEquals(
            4,
            kept.categories[1]
                .emojis
                .single()
                .variants.size,
        )
        assertTrue("a filtered catalog still searches", kept.search("挥手").isNotEmpty())
        assertTrue(kept.search("香蕉").isEmpty())
    }
}
