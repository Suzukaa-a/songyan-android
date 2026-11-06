package app.songyan.ime.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songyan.ime.ime.panels.SymbolPagePanelView
import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import app.songyan.ime.layout.KeyboardComposition
import app.songyan.ime.layout.KeyboardCompositionKey
import app.songyan.ime.layout.KeyboardPagePresets
import app.songyan.ime.layout.LayoutInput
import app.songyan.ime.layout.LayoutRegistry
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 26-key letters paired with the keypad 123 page: its symbol key opens whichever symbol page the
 * pairing names and a symbol types there. The row symbol pages leave for letters; the categorized
 * page's 123 tab comes back to the pad.
 */
@RunWith(AndroidJUnit4::class)
class KeypadSymbolSwitchDeviceTest {
    @After
    fun restoreTheBundledCatalog() {
        KeyboardLayoutCatalog.install(LayoutRegistry.create())
    }

    private fun withKeypadPairedTo(
        symbolPageId: String,
        block: (FeatureHost) -> Unit,
    ) {
        KeyboardLayoutCatalog.install(
            LayoutRegistry
                .create()
                .withComposition(
                    KeyboardComposition(
                        mainLayoutID = "qwerty26",
                        numericPageID = KeyboardPagePresets.numericKeypad.id,
                        symbolPageID = symbolPageId,
                    ),
                    KeyboardCompositionKey(BuiltinTemplateSchemas.QUANPIN, LayoutInput.Builtin.QWERTY26).rawValue,
                ),
        )
        val host = FeatureHost(engine.backend(), "qwerty26")
        try {
            host.start()
            host.tap(host.string(R.string.ime_open_numbers_page))
            host.awaitIdle()
            assertTrue(
                host.labelsOf(KeyboardView::class.java).toString(),
                "9" in host.labelsOf(KeyboardView::class.java),
            )
            block(host)
        } finally {
            host.close()
            kotlinx.coroutines.runBlocking { host.backend.shutdown() }
        }
    }

    @Test
    fun theRowShapedSymbolPageOpensTypesAndReturnsToLetters() =
        withKeypadPairedTo(KeyboardPagePresets.symbolsSystem.id) { host ->
            host.tap(host.string(R.string.ime_open_symbols_page))
            host.awaitIdle()
            val marks = host.labelsOf(KeyboardView::class.java)
            assertTrue(marks.toString(), "9" !in marks)
            // The pad lacks the 123 page's everyday marks, so the symbol page opens on them first.
            host.tap(host.string(R.string.ime_open_more_symbols))
            host.awaitIdle()
            host.tap("\$")
            host.assertText("\$")
            // Both row symbol pages share one switch, so it turns back rather than escaping to the pad.
            host.tap(host.string(R.string.ime_open_more_symbols))
            host.awaitIdle()
            assertEquals(marks, host.labelsOf(KeyboardView::class.java))
            host.tap(host.string(R.string.ime_return_to_letters))
            host.awaitIdle()
            val letters = host.labelsOf(KeyboardView::class.java)
            assertTrue(letters.toString(), "a" in letters)
        }

    @Test
    fun theCategorizedSymbolPageOpensTypesAndReturnsToThePad() =
        withKeypadPairedTo(KeyboardPagePresets.symbolsCategorized.id) { host ->
            host.tap(host.string(R.string.ime_open_symbols_page))
            host.awaitIdle()
            assertTrue(host.await { host.findOrNull(SymbolPagePanelView::class.java) != null })
            host.tap(host.string(R.string.ime_open_numbers_page), SymbolPagePanelView::class.java)
            host.awaitIdle()
            assertTrue(
                host.labelsOf(KeyboardView::class.java).toString(),
                "9" in host.labelsOf(KeyboardView::class.java),
            )
            host.tap(host.string(R.string.ime_open_symbols_page))
            host.awaitIdle()
            host.tap("#", SymbolPagePanelView::class.java)
            host.assertText("#")
        }

    companion object {
        private lateinit var engine: DeployedEngine

        @BeforeClass
        @JvmStatic
        fun deploy() {
            engine = DeployedEngine("keypad-symbols", english = false)
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            engine.delete()
        }
    }
}
