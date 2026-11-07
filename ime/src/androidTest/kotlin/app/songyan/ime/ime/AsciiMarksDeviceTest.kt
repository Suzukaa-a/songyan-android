package app.songyan.ime.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import app.songyan.ime.layout.KeyboardComposition
import app.songyan.ime.layout.KeyboardCompositionKey
import app.songyan.ime.layout.KeyboardNumericMarks
import app.songyan.ime.layout.KeyboardPageDefinition
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
 * The 123 and symbol pages are one bilingual page each: English input shows and types the ASCII
 * marks, Chinese input the Chinese ones, on the built-in pages and on a page the user copied.
 */
@RunWith(AndroidJUnit4::class)
class AsciiMarksDeviceTest {
    @After
    fun restoreTheBundledCatalog() {
        KeyboardLayoutCatalog.install(LayoutRegistry.create())
    }

    private fun withHost(
        layout: String = "qwerty26",
        numeric: KeyboardPageDefinition? = null,
        symbols: KeyboardPageDefinition? = null,
        block: (FeatureHost) -> Unit,
    ) {
        var registry = LayoutRegistry.create()
        numeric?.let { registry = registry.withPage(it) }
        symbols?.let { registry = registry.withPage(it) }
        val kind = LayoutInput.Builtin.fromWire(layout) ?: LayoutInput.Builtin.QWERTY26
        registry =
            registry.withComposition(
                KeyboardComposition(
                    mainLayoutID = layout,
                    numericPageID = numeric?.id ?: KeyboardPagePresets.NUMERIC_AUTOMATIC_ID,
                    symbolPageID = symbols?.id ?: KeyboardPagePresets.SYMBOLS_SYSTEM_ID,
                ),
                KeyboardCompositionKey(BuiltinTemplateSchemas.QUANPIN, kind).rawValue,
            )
        KeyboardLayoutCatalog.install(registry)
        val host = FeatureHost(engine.backend(), layout)
        try {
            host.start()
            block(host)
        } finally {
            host.close()
            kotlinx.coroutines.runBlocking { host.backend.shutdown() }
        }
    }

    private fun labels(host: FeatureHost) = host.labelsOf(KeyboardView::class.java)

    private fun english(host: FeatureHost) {
        host.tapControl(ControlKey.LANGUAGE_TOGGLE)
        host.awaitIdle()
    }

    private fun openNumbers(host: FeatureHost) {
        host.tapNumbersPage()
        host.awaitIdle()
    }

    private val bilingual get() = KeyboardPagePresets.numericAutomatic.duplicated("my-digits", "我的数字")

    @Test
    fun chineseInputShowsAndTypesTheChineseCommaOnThe123Page() =
        withHost { host ->
            openNumbers(host)
            assertTrue(labels(host).toString(), "，" in labels(host) && "," !in labels(host))
            host.tap("，")
            host.assertText("，")
        }

    @Test
    fun englishInputShowsAndTypesTheAsciiCommaOnThe123Page() =
        withHost { host ->
            english(host)
            openNumbers(host)
            assertTrue(labels(host).toString(), "," in labels(host) && "，" !in labels(host))
            host.tap(",")
            host.assertText(",")
        }

    @Test
    fun aCopiedBilingualPageFollowsTheInputLanguageToo() {
        withHost(numeric = bilingual) { host ->
            openNumbers(host)
            assertTrue(labels(host).toString(), "，" in labels(host) && "," !in labels(host))
            host.tap("，")
            host.assertText("，")
        }
        withHost(numeric = bilingual) { host ->
            english(host)
            openNumbers(host)
            assertTrue(labels(host).toString(), "," in labels(host) && "，" !in labels(host))
            host.tap(",")
            host.assertText(",")
        }
    }

    @Test
    fun aPageThatOpensInEnglishKeepsItsChoiceInChineseInput() =
        withHost(numeric = bilingual.withDefaultSymbolMarks(KeyboardNumericMarks.ASCII)) { host ->
            openNumbers(host)
            assertTrue(labels(host).toString(), "," in labels(host) && "，" !in labels(host))
            // The page's own 中/英 key still flips it.
            host.tapControl(ControlKey.LANGUAGE_TOGGLE)
            host.awaitIdle()
            assertTrue(labels(host).toString(), "，" in labels(host) && "," !in labels(host))
        }

    @Test
    fun theSymbolPageShowsAndTypesTheMarkOfTheInputLanguage() {
        val mark =
            KeyboardPagePresets
                .symbolRowsPage(false)
                .rows
                .flatMap { it.keys }
                .first { it.action.asciiValue != null && it.action.asciiValue != it.action.value }
        val chinese = requireNotNull(mark.action.value)
        val ascii = requireNotNull(mark.action.asciiValue)
        withHost { host ->
            openNumbers(host)
            host.tapSymbolsPage()
            host.awaitIdle()
            assertTrue(labels(host).toString(), chinese in labels(host))
            host.tap(chinese)
            host.assertText(chinese)
        }
        withHost { host ->
            english(host)
            openNumbers(host)
            host.tapSymbolsPage()
            host.awaitIdle()
            assertTrue(labels(host).toString(), ascii in labels(host) && chinese !in labels(host))
            host.tap(ascii)
            host.assertText(ascii)
        }
    }

    @Test
    fun theNineKeyRailFollowsTheBranchOfThePage() =
        withHost(layout = "phone9") { host ->
            openNumbers(host)
            val chinese = labels(host)
            assertTrue(chinese.toString(), "？" in chinese && "?" !in chinese)
            host.tapControl(ControlKey.LANGUAGE_TOGGLE)
            host.awaitIdle()
            val ascii = labels(host)
            assertTrue(ascii.toString(), "?" in ascii && "？" !in ascii)
            host.tap("?")
            assertEquals("?", host.text())
        }

    companion object {
        private lateinit var engine: DeployedEngine

        @BeforeClass
        @JvmStatic
        fun deploy() {
            engine = DeployedEngine("ascii-marks", english = false)
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            engine.delete()
        }
    }
}
