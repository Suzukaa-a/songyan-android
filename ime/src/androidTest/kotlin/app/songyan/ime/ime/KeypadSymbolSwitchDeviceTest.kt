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
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 26-key letters paired with the keypad 123 page: its symbol key opens whichever symbol page the
 * pairing names, a symbol types there, and the page's 123 key comes back to the pad.
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
            host.tapNumbersPage()
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
    fun theRowShapedSymbolPageOpensTypesAndReturnsToThePad() =
        withKeypadPairedTo(KeyboardPagePresets.symbolsSystem.id) { host ->
            host.tapSymbolsPage()
            host.awaitIdle()
            assertTrue(
                host.labelsOf(KeyboardView::class.java).toString(),
                "9" !in host.labelsOf(KeyboardView::class.java),
            )
            // The pad lacks the 123 page's everyday marks, so the symbol page opens on them first.
            host.tapSymbolsPage()
            host.awaitIdle()
            host.tap("\$")
            host.assertText("\$")
            host.tapNumbersPage()
            host.awaitIdle()
            assertTrue(
                host.labelsOf(KeyboardView::class.java).toString(),
                "9" in host.labelsOf(KeyboardView::class.java),
            )
        }

    @Test
    fun theCategorizedSymbolPageOpensTypesAndReturnsToThePad() =
        withKeypadPairedTo(KeyboardPagePresets.symbolsCategorized.id) { host ->
            host.tapSymbolsPage()
            host.awaitIdle()
            assertTrue(host.await { host.findOrNull(SymbolPagePanelView::class.java) != null })
            host.tap(host.string(R.string.ime_open_numbers_page), SymbolPagePanelView::class.java)
            host.awaitIdle()
            assertTrue(
                host.labelsOf(KeyboardView::class.java).toString(),
                "9" in host.labelsOf(KeyboardView::class.java),
            )
            host.tapSymbolsPage()
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
