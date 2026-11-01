package app.songyan.ime.keyboard.display

import app.songyan.ime.keyboard.engine.ReadingCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Ported from KeyboardCoreTests.SentenceSpellingTests. */
class SentenceSpellingTest {
    private fun spell(
        segments: List<String>,
        text: String,
        entries: List<ReadingCandidate> = emptyList(),
        known: Map<Char, List<String>> = emptyMap(),
    ): String? = SentenceSpelling.spell(segments, text, entries, T9, known)

    @Test
    fun sentenceTakesCharacterReadingsThenRankedSyllables() {
        val entries =
            listOf(
                ReadingCandidate(0, "一金贵呀", null),
                ReadingCandidate(1, "犀金龟", listOf(listOf("xi", "jin", "gui"))),
                ReadingCandidate(2, "一经", listOf(listOf("yi", "jing"))),
            )
        // A syllable no candidate proves falls back to the common one.
        assertEquals("yi'jin'gui'ya", spell(listOf("94", "546", "484", "92"), "一金贵呀", entries))
    }

    @Test
    fun unprovenSegmentsNeverBlankOrTruncateTheField() {
        val entries =
            listOf(
                ReadingCandidate(0, "塔影", listOf(listOf("ta", "ying"))),
                ReadingCandidate(1, "他以后", listOf(listOf("ta", "yi", "hou"))),
            )
        assertEquals(
            "ta'ying'dang",
            spell(listOf("82", "9464", "3264"), "他应当", entries, mapOf('当' to listOf("dang"))),
        )
        assertEquals("ta'ying", spell(listOf("82", "9464"), "他应当", entries))
        assertEquals("ta'yi", spell(listOf("82", "94"), "他应", entries))
        assertEquals("only keys no syllable spells show as keys", "11", spell(listOf("11"), "嗯", entries))
        assertNull(SentenceSpelling.spell(listOf("82"), "他", entries, emptyMap()))
    }

    @Test
    fun sentenceTailKeepsEveryPressVisible() {
        val entries =
            listOf(
                ReadingCandidate(0, "你好", listOf(listOf("ni", "hao"))),
                ReadingCandidate(1, "米虫", listOf(listOf("mi", "chong"))),
            )
        assertEquals("ni'hao'mo", spell(listOf("64", "426", "66"), "你好魔", entries))
        assertEquals("o'hai", spell(listOf("6", "442"), "哦还"))
        assertEquals(
            "mi'gai",
            spell(listOf("64", "424"), "米该", known = mapOf('米' to listOf("mi"), '该' to listOf("gai"))),
        )
        assertEquals(
            "ni'hao'z'y'y'y",
            spell(
                listOf("64", "426", "9", "9", "9", "9"),
                "你好中央音乐",
                entries,
                mapOf('中' to listOf("zhong"), '央' to listOf("yang"), '音' to listOf("yin"), '乐' to listOf("yue")),
            ),
        )
        assertEquals("ni'hao'y'y'y", spell(listOf("64", "426", "9", "9", "9"), "你好呷呀呀", entries))
        assertEquals(
            "ni'hao'non'mo",
            spell(listOf("64", "426", "666", "66"), "你好浓哦", entries, mapOf('浓' to listOf("nong"))),
        )
        assertEquals("ni'h", spell(listOf("64", "4"), "你好", entries))
    }

    @Test
    fun coverageTrimsAReadingToTheTypedKeys() {
        val one = requireNotNull(ReadingInputCoverage.of("6", T9))
        assertEquals("one key reads n, not the candidate's na", listOf("n"), one.typedSpelling(listOf("na")))
        val three = requireNotNull(ReadingInputCoverage.of("284", T9))
        assertEquals(listOf("bu", "g"), three.typedSpelling(listOf("bu", "guo")))
        val full = requireNotNull(ReadingInputCoverage.of("64426", T9))
        assertEquals(listOf("ni", "hao"), full.typedSpelling(listOf("ni", "hao")))
        // A candidate covering only part of the input has no typed spelling.
        assertNull(full.typedSpelling(listOf("ni")))
        // Letters typed as themselves match too.
        assertEquals(listOf("zh"), requireNotNull(ReadingInputCoverage.of("zh", T9)).typedSpelling(listOf("zhong")))
    }

    companion object {
        val T9: Map<Char, Char> =
            buildMap {
                val letters = listOf("abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz")
                "23456789".forEachIndexed { index, digit -> letters[index].forEach { put(it, digit) } }
            }
    }
}
