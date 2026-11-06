package app.songyan.ime.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songyan.ime.content.CommonPhrase
import app.songyan.ime.content.SymbolCatalog
import app.songyan.ime.content.SymbolCategory
import app.songyan.ime.content.SymbolItem
import app.songyan.ime.ime.panels.PhraseClipboardPanelView
import app.songyan.ime.ime.panels.SymbolPanelView
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/** The symbol panel's and the phrase panel's search keys: the keyboard types the query, the band lists matches. */
@RunWith(AndroidJUnit4::class)
class PanelSearchDeviceTest {
    private val catalog =
        SymbolCatalog(
            categories = listOf(SymbolCategory("marks", "标记", 0)),
            items =
                listOf(
                    SymbolItem("star", "★", "五角星", listOf("wujiaoxing", "star"), "marks"),
                    SymbolItem("heart", "♥", "红心", listOf("hongxin", "heart"), "marks"),
                ),
        )

    private val phrases =
        listOf(
            CommonPhrase(text = "辛苦了，谢谢", shortcut = "xk"),
            CommonPhrase(text = "你好呀", shortcut = "nh"),
        )

    @Test
    fun symbolSearchFindsASymbolByItsAliasAndInsertsIt() =
        withHost { host ->
            host.symbolCatalog = catalog
            host.start()
            host.main { host.controller.openSymbolPanel() }
            assertTrue(host.await { "标记" in host.symbolLabels() })
            host.tap(host.string(R.string.ime_symbol_search_empty), SymbolPanelView::class.java)
            assertTrue("the panel gave way", host.await { host.symbolLabels().isEmpty() })
            host.tapControl(ControlKey.LANGUAGE_TOGGLE)
            host.type("star")
            assertEquals("what is typed goes into the query, not the editor", "", host.text())
            assertTrue("results: ${host.barLabels()}", host.await { "★" in host.barLabels() })
            assertFalse("♥" in host.barLabels())
            // The chip is UI chrome from the res table; the keys beside it stay the scheme's own labels.
            assertEquals(host.context.getString(R.string.ime_emoji_search_chip, "star"), host.barLabels().first())
            host.tapBar("★")
            host.assertText("★")
            // The search stays open, so a second pick inserts again.
            assertTrue("the search stays open", "★" in host.barLabels())
            host.tapBar("★")
            host.assertText("★★")
            assertEquals("a found symbol is a recent one", listOf("★"), host.recentSymbols)
        }

    @Test
    fun phraseSearchFindsAPhraseByItsSearchTermAndInsertsIt() =
        withHost { host ->
            host.phrases = phrases
            host.start()
            host.main { host.controller.openQuickContentPanel() }
            assertTrue(host.await { "辛苦了，谢谢" in host.phraseLabels() })
            host.tap(host.string(R.string.ime_phrase_search_empty), PhraseClipboardPanelView::class.java)
            assertTrue("the panel gave way", host.await { host.phraseLabels().isEmpty() })
            host.tapControl(ControlKey.LANGUAGE_TOGGLE)
            host.type("xk")
            assertEquals("", host.text())
            assertTrue("results: ${host.barLabels()}", host.await { "辛苦了，谢谢" in host.barLabels() })
            assertFalse("你好呀" in host.barLabels())
            host.tapBar("辛苦了，谢谢")
            host.assertText("辛苦了，谢谢")
            assertTrue("the search stays open", "辛苦了，谢谢" in host.barLabels())
            host.tapBar("辛苦了，谢谢")
            host.assertText("辛苦了，谢谢辛苦了，谢谢")
        }

    @Test
    fun backEndsASymbolSearchAndTypingGoesToTheEditorAgain() =
        withHost { host ->
            host.symbolCatalog = catalog
            host.start()
            host.main { host.controller.openSymbolPanel() }
            assertTrue(host.await { "标记" in host.symbolLabels() })
            host.tap(host.string(R.string.ime_symbol_search_empty), SymbolPanelView::class.java)
            assertTrue(host.await { host.symbolLabels().isEmpty() })
            assertTrue("Back belongs to the search", host.backOwned)
            host.main { assertTrue(host.controller.closePanels()) }
            assertTrue(host.await { host.toolbarLabels().isNotEmpty() })
            assertFalse(host.backOwned)
            host.tapControl(ControlKey.LANGUAGE_TOGGLE)
            host.type("a")
            host.assertText("a")
        }

    private fun FeatureHost.symbolLabels(): List<String> = labelsOf(SymbolPanelView::class.java)

    private fun FeatureHost.phraseLabels(): List<String> = labelsOf(PhraseClipboardPanelView::class.java)

    private fun <T> withHost(block: (FeatureHost) -> T): T {
        val host = FeatureHost(engine.backend())
        try {
            return block(host)
        } finally {
            host.close()
            kotlinx.coroutines.runBlocking { host.backend.shutdown() }
        }
    }

    companion object {
        private lateinit var engine: DeployedEngine

        @BeforeClass
        @JvmStatic
        fun deploy() {
            engine = DeployedEngine("panel-search", english = false)
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            engine.delete()
        }
    }
}
