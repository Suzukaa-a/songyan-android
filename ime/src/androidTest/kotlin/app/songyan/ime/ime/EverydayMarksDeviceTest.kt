package app.songyan.ime.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import app.songyan.ime.layout.KeyboardComposition
import app.songyan.ime.layout.KeyboardCompositionKey
import app.songyan.ime.layout.KeyboardPagePresets
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutInput
import app.songyan.ime.layout.LayoutPageTarget
import app.songyan.ime.layout.LayoutRegistry
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 26-key letters paired with the keypad 123 page reach the everyday marks the full 123 page carries
 * through the symbol page's extra first page; with the full 123 page the symbol page is unchanged.
 */
@RunWith(AndroidJUnit4::class)
class EverydayMarksDeviceTest {
    @After
    fun restoreTheBundledCatalog() {
        KeyboardLayoutCatalog.install(LayoutRegistry.create())
    }

    private fun withHost(
        numericPageId: String,
        block: (FeatureHost) -> Unit,
    ) {
        KeyboardLayoutCatalog.install(
            LayoutRegistry
                .create()
                .withComposition(
                    KeyboardComposition(mainLayoutID = "qwerty26", numericPageID = numericPageId),
                    KeyboardCompositionKey(BuiltinTemplateSchemas.QUANPIN, LayoutInput.Builtin.QWERTY26).rawValue,
                ),
        )
        val host = FeatureHost(engine.backend(), "qwerty26")
        try {
            host.start()
            block(host)
        } finally {
            host.close()
            kotlinx.coroutines.runBlocking { host.backend.shutdown() }
        }
    }

    private fun FeatureHost.labels() = labelsOf(KeyboardView::class.java)

    private fun FeatureHost.openKeypadSymbols(lacking: String) {
        if ("9" !in labels()) {
            tap(string(R.string.ime_open_numbers_page))
            awaitIdle()
        }
        // The pairing's digit pad, not the row 123 page that carries the mark itself.
        assertTrue(labels().toString(), "9" in labels() && lacking !in labels())
        tap(string(R.string.ime_open_symbols_page))
        awaitIdle()
    }

    /** Delete and the third-row switch, where the page draws them. */
    private fun FeatureHost.controlBounds(): List<KeyBounds> =
        main2 {
            val view = find(KeyboardView::class.java)
            view.keys.indices
                .filter { index ->
                    val action = view.keys[index].action
                    action.type == LayoutActionKind.BACKSPACE ||
                        action.pageSwitchTarget == LayoutPageTarget.SYMBOLS ||
                        action.pageSwitchTarget == LayoutPageTarget.NUMERIC
                }.map { view.bounds[it] }
        }

    @Test
    fun theKeypadPairingTypesYuanFromTheSymbolPagesFirstPage() =
        withHost(KeyboardPagePresets.numericKeypad.id) { host ->
            host.openKeypadSymbols("¥")
            val first = host.labels()
            // The usual page's grid filled with marks: no digit row, and the next page's marks fill what is left.
            assertTrue(first.toString(), "¥" in first && "【" in first && "《" !in first && "1" !in first)
            val firstControls = host.controlBounds()
            host.tap("¥")
            host.assertText("¥")
            // Its page switch turns to the usual symbol rows, whose switch and Delete stand exactly where they did.
            host.tap(host.string(R.string.ime_open_more_symbols))
            host.awaitIdle()
            assertTrue(host.labels().toString(), "《" in host.labels() && "¥" !in host.labels())
            assertEquals(host.controlBounds(), firstControls)
        }

    @Test
    fun theKeypadPairingTypesAmpersandWithEnglishMarks() =
        withHost(KeyboardPagePresets.numericKeypad.id) { host ->
            host.tap(host.string(R.string.ime_open_numbers_page))
            host.awaitIdle()
            // The pad's language key switches the marks to English ones.
            host.tap(host.string(R.string.ime_language_to_english_symbols))
            host.awaitIdle()
            host.openKeypadSymbols("&")
            assertTrue(host.labels().toString(), "&" in host.labels())
            host.tap("&")
            host.assertText("&")
        }

    @Test
    fun theFullKeyboard123PairingKeepsTheSymbolPage() =
        withHost(KeyboardPagePresets.numericAutomatic.id) { host ->
            host.tap(host.string(R.string.ime_open_numbers_page))
            host.awaitIdle()
            host.tap(host.string(R.string.ime_open_symbols_page))
            host.awaitIdle()
            val labels = host.labels()
            assertTrue(labels.toString(), "【" in labels)
            assertFalse(labels.toString(), "¥" in labels)
            assertFalse(labels.toString(), host.string(R.string.ime_open_more_symbols) in labels)
        }

    companion object {
        private lateinit var engine: DeployedEngine

        @BeforeClass
        @JvmStatic
        fun deploy() {
            engine = DeployedEngine("everyday-marks", english = false)
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            engine.delete()
        }
    }
}
