package app.songyan.ime.ime

import app.songyan.ime.content.CommonPhrase
import app.songyan.ime.content.SymbolCatalog
import app.songyan.ime.content.SymbolCategory
import app.songyan.ime.content.SymbolItem
import org.junit.Assert.assertEquals
import org.junit.Test

class PanelSearchMatchingTest {
    private val catalog =
        SymbolCatalog(
            categories = listOf(SymbolCategory("marks", "标记", 0)),
            items =
                listOf(
                    SymbolItem("star", "★", "五角星", listOf("wujiaoxing", "star"), "marks"),
                    SymbolItem("heart", "♥", "红心", listOf("hongxin", "heart"), "marks"),
                    SymbolItem("burst", "✯", "星芒", listOf("xingmang", "starburst"), "marks"),
                ),
        )

    private val phrases =
        listOf(
            CommonPhrase(text = "辛苦了，谢谢", shortcut = "xk"),
            CommonPhrase(text = "你好呀", shortcut = ""),
            CommonPhrase(text = "Hello", shortcut = "HI"),
            CommonPhrase(text = "稍等", shortcut = "xk2"),
        )

    @Test
    fun symbolsMatchByAliasNameAndTheSymbolItself() {
        assertEquals(listOf("★"), PanelSearchMatching.symbols(catalog, "wujiao"))
        assertEquals(listOf("♥"), PanelSearchMatching.symbols(catalog, "红心"))
        assertEquals(listOf("♥"), PanelSearchMatching.symbols(catalog, "♥"))
    }

    @Test
    fun symbolsKeepTheCatalogsRankingAndAnEmptyQueryFindsNothing() {
        // An alias that starts with the query ranks before one that merely contains it.
        assertEquals(listOf("★", "✯"), PanelSearchMatching.symbols(catalog, "star"))
        assertEquals(emptyList<String>(), PanelSearchMatching.symbols(catalog, "  "))
        assertEquals(emptyList<String>(), PanelSearchMatching.symbols(catalog, "zzz"))
    }

    @Test
    fun phrasesMatchByTextOrByTheirSearchTermInTheUsersOrder() {
        assertEquals(listOf("辛苦了，谢谢", "稍等"), PanelSearchMatching.phrases(phrases, "xk"))
        assertEquals(listOf("辛苦了，谢谢"), PanelSearchMatching.phrases(phrases, "谢谢"))
        assertEquals(listOf("Hello"), PanelSearchMatching.phrases(phrases, "hi"))
        assertEquals(listOf("Hello"), PanelSearchMatching.phrases(phrases, "h"))
        assertEquals(emptyList<String>(), PanelSearchMatching.phrases(phrases, "zzz"))
    }

    @Test
    fun anEmptyPhraseQueryListsEveryPhraseInTheUsersOrder() {
        assertEquals(phrases.map { it.text }, PanelSearchMatching.phrases(phrases, ""))
    }
}
