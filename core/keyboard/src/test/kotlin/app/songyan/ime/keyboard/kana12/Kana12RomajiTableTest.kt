package app.songyan.ime.keyboard.kana12

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the compiled kana -> romaji table to the native pair-matrix evidence: every listed
 * surface types alone and in every ordered pair through the frozen kagiroi speller, so the table
 * needs no runtime JSON and no correction on the keyboard thread.
 *
 * Ported from KeyboardCoreTests.Kana12RomajiTableTests.
 */
class Kana12RomajiTableTest {
    /** The 82 verified surfaces in artifact order: 47 base (46 kana + ー), 20 dakuten, 5 handakuten, 10 small. */
    private val verified: List<Pair<String, String>> =
        listOf(
            "あ" to "a",
            "い" to "i",
            "う" to "u",
            "え" to "e",
            "お" to "o",
            "か" to "ka",
            "き" to "ki",
            "く" to "ku",
            "け" to "ke",
            "こ" to "ko",
            "さ" to "sa",
            "し" to "si",
            "す" to "su",
            "せ" to "se",
            "そ" to "so",
            "た" to "ta",
            "ち" to "ti",
            "つ" to "tu",
            "て" to "te",
            "と" to "to",
            "な" to "na",
            "に" to "ni",
            "ぬ" to "nu",
            "ね" to "ne",
            "の" to "no",
            "は" to "ha",
            "ひ" to "hi",
            "ふ" to "hu",
            "へ" to "he",
            "ほ" to "ho",
            "ま" to "ma",
            "み" to "mi",
            "む" to "mu",
            "め" to "me",
            "も" to "mo",
            "や" to "ya",
            "ゆ" to "yu",
            "よ" to "yo",
            "ら" to "ra",
            "り" to "ri",
            "る" to "ru",
            "れ" to "re",
            "ろ" to "ro",
            "わ" to "wa",
            "を" to "wo",
            "ん" to "nn",
            "ー" to "-",
            "が" to "ga",
            "ぎ" to "gi",
            "ぐ" to "gu",
            "げ" to "ge",
            "ご" to "go",
            "ざ" to "za",
            "じ" to "zi",
            "ず" to "zu",
            "ぜ" to "ze",
            "ぞ" to "zo",
            "だ" to "da",
            "ぢ" to "di",
            "づ" to "du",
            "で" to "de",
            "ど" to "do",
            "ば" to "ba",
            "び" to "bi",
            "ぶ" to "bu",
            "べ" to "be",
            "ぼ" to "bo",
            "ぱ" to "pa",
            "ぴ" to "pi",
            "ぷ" to "pu",
            "ぺ" to "pe",
            "ぽ" to "po",
            "ぁ" to "la",
            "ぃ" to "li",
            "ぅ" to "lu",
            "ぇ" to "le",
            "ぉ" to "lo",
            "ゃ" to "lya",
            "ゅ" to "lyu",
            "ょ" to "lyo",
            "っ" to "ltu",
            "ゎ" to "lwa",
        )

    @Test
    fun compiledTableMatchesTheVerifiedArtifactExactly() {
        assertEquals(82, Kana12RomajiTable.entries.size)
        assertEquals(verified.map { it.first }, Kana12RomajiTable.entries.map { it.kana })
        assertEquals(verified.map { it.second }, Kana12RomajiTable.entries.map { it.romaji })
    }

    @Test
    fun lookupReturnsEveryVerifiedRomaji() {
        for (entry in verified) {
            assertEquals(entry.first, entry.second, Kana12RomajiTable.romaji(entry.first))
        }
        assertNull(Kana12RomajiTable.romaji(""))
        assertNull(Kana12RomajiTable.romaji("abc"))
    }

    @Test
    fun romajiAreLowerCaseAsciiCodes() {
        val allowed = "abcdefghijklmnopqrstuvwxyz-".toSet()
        for (entry in Kana12RomajiTable.entries) {
            assertFalse(entry.kana, entry.romaji.isEmpty())
            assertTrue(entry.kana, entry.romaji.all { it in allowed })
            assertTrue(entry.kana, entry.romaji.length in 1..3)
        }
    }

    /**
     * Table entries must all be reachable from the 12-key grid, its tap cycles, or the
     * 小゛゜ variant cycle; conversely every reachable glyph must have a code. This is what
     * keeps the frozen data's unreachable archaic surfaces (ゔ, ゐ, ゑ, ゕ, ゖ, いぇ) out.
     */
    @Test
    fun tableMatchesTheGlyphsReachableFromTheGrid() {
        val reachable = mutableSetOf<String>()
        for (rowId in Kana12Layout.rowIds) {
            for (direction in Kana12FlickDirection.entries) {
                Kana12Layout.kana(rowId, direction)?.let { reachable.add(it) }
            }
            reachable.addAll(Kana12Layout.toggleCycle(rowId))
        }
        var changed = true
        while (changed) {
            changed = false
            for (kana in reachable.toList()) {
                val next = Kana12Layout.nextVariant(kana) ?: continue
                if (reachable.add(next)) changed = true
            }
        }
        assertEquals(Kana12RomajiTable.entries.map { it.kana }.toSet(), reachable)
        for (kana in reachable) {
            assertNotNull(kana, Kana12RomajiTable.romaji(kana))
        }
    }

    @Test
    fun unreachableArchaicSurfacesAreNotInTheTable() {
        for (kana in listOf("ゔ", "ゐ", "ゑ", "ゕ", "ゖ", "いぇ", "ヵ", "ヶ")) {
            assertNull(kana, Kana12RomajiTable.romaji(kana))
        }
    }
}
