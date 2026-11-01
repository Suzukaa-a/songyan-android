package app.songyan.ime.ime

import android.text.InputType
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
import org.junit.runners.Parameterized
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Letters, 123 and symbols of one field are one height: the window's top (and so the app's ime
 * inset) stays where it is while the user switches pages, and every page's keys reach the bottom
 * of that height instead of leaving a blank band under a shorter page. Over the shipped engine.
 */
@RunWith(Parameterized::class)
class KeyboardHeightStableDeviceTest(
    private val fieldName: String,
    private val inputType: Int,
    private val layout: String,
    private val english: Boolean,
    private val keypad: Boolean,
) {
    /** The window height, and how far the lowest key reaches (null when no row keys are drawn). */
    private fun heightNow(host: FeatureHost): Pair<Int, Int?> =
        host.main2 {
            host.relayout()
            val keys = host.findOrNull(KeyboardView::class.java)?.bounds.orEmpty()
            host.controller.view.measuredHeight to keys.maxOfOrNull { it.bottom.roundToInt() }
        }

    private fun tapFirst(
        host: FeatureHost,
        vararg labels: String,
    ) {
        val shown = host.labelsOf(KeyboardView::class.java)
        host.tap(requireNotNull(labels.firstOrNull { it in shown }) { "none of ${labels.toList()} in $shown" })
        host.awaitIdle()
    }

    @After
    fun restoreTheBundledCatalog() {
        KeyboardLayoutCatalog.install(LayoutRegistry.create())
    }

    private fun pairWithTheKeypad() {
        KeyboardLayoutCatalog.install(
            listOf(
                "qwerty26" to LayoutInput.Builtin.QWERTY26,
                "merged14" to LayoutInput.Builtin.MERGED14,
                "merged18" to LayoutInput.Builtin.MERGED18,
            ).fold(LayoutRegistry.create()) { registry, (id, input) ->
                registry.withComposition(
                    KeyboardComposition(
                        mainLayoutID = id,
                        numericPageID = KeyboardPagePresets.numericKeypad.id,
                    ),
                    KeyboardCompositionKey(BuiltinTemplateSchemas.QUANPIN, input).rawValue,
                )
            },
        )
    }

    @Test
    fun lettersNumbersAndSymbolsShareOneHeight() {
        if (keypad) pairWithTheKeypad()
        val host = FeatureHost(engine.backend(), layout)
        val where = "$fieldName $layout ${if (english) "en" else "zh"}${if (keypad) " keypad" else ""}"
        try {
            host.start(inputType = inputType)
            if (english) {
                host.tap(host.string(R.string.ime_language_to_english))
                host.awaitIdle()
            }
            val letters = heightNow(host)
            val numbersLabel = host.string(R.string.ime_open_numbers_page)
            val symbolsLabel = host.string(R.string.ime_open_symbols_page)
            val lettersLabel = host.string(R.string.ime_return_to_letters)
            tapFirst(host, numbersLabel)
            val numbers = heightNow(host)
            tapFirst(host, symbolsLabel, host.string(R.string.ime_symbols))
            val symbols = heightNow(host)
            // A nine-key symbol page is a panel that has its own way back; the rows pages return here.
            if (host.labelsOf(KeyboardView::class.java).isNotEmpty()) tapFirst(host, numbersLabel, lettersLabel)
            val back = heightNow(host)
            val pages = listOf(numbers, symbols, back)
            assertTrue(
                "$where: letters $letters, 123 $numbers, symbols $symbols, back $back",
                pages.all { it.first == letters.first },
            )
            // A page with fewer rows than the field's height stretches them; one pixel of rounding is fine.
            val lowest = requireNotNull(letters.second)
            assertTrue(
                "$where keys end at: letters $letters, 123 $numbers, symbols $symbols, back $back",
                pages.mapNotNull { it.second }.all { abs(it - lowest) <= 1 },
            )
        } finally {
            host.close()
            kotlinx.coroutines.runBlocking { host.backend.shutdown() }
        }
    }

    companion object {
        private val fields =
            listOf(
                "text" to InputType.TYPE_CLASS_TEXT,
                "password" to (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD),
                "email" to (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS),
                "uri" to (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI),
            )

        @Parameterized.Parameters(name = "{0} {2} english={3} keypad={4}")
        @JvmStatic
        fun cases(): List<Array<Any>> =
            fields.flatMap { (name, type) ->
                listOf("qwerty26", "merged14", "merged18", "phone9").flatMap { layout ->
                    // Only a plain text field has a language key; the others type ASCII.
                    (if (name == "text") listOf(false, true) else listOf(false)).flatMap { english ->
                        // The keypad pairing is for the row layouts; the nine-key one has its own pad.
                        (if (layout == "phone9") listOf(false) else listOf(false, true)).map { keypad ->
                            arrayOf<Any>(name, type, layout, english, keypad)
                        }
                    }
                }
            }

        private lateinit var engine: DeployedEngine

        @BeforeClass
        @JvmStatic
        fun deploy() {
            engine = DeployedEngine("height-stable", english = false)
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            engine.delete()
        }
    }
}
