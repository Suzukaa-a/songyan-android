package app.songyan.ime.ime

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import app.songyan.ime.layout.KeyboardCompositionKey
import app.songyan.ime.layout.KeyboardPageDefinition
import app.songyan.ime.layout.KeyboardPageKind
import app.songyan.ime.layout.KeyboardPagePresets
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutInput
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutPageTarget
import app.songyan.ime.layout.LayoutRegistry
import app.songyan.ime.layout.LayoutRow
import app.songyan.ime.layout.LayoutStore
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 26-key letters paired in 页面搭配 with the unchanged keypad 123 page: the 123 key opens the digit
 * pad, not the ten-column row page, in Chinese and in English; a nine-key keyboard paired with a
 * 123 page the user authored draws that page instead of its own digit pad. Over the shipped engine.
 */
@RunWith(AndroidJUnit4::class)
class PairedKeypadDeviceTest {
    private val directory =
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "paired-keypad-layouts")

    @After
    fun restoreTheBundledCatalog() {
        KeyboardLayoutCatalog.install(LayoutRegistry.create())
        directory.deleteRecursively()
    }

    /**
     * Saves the pairing the way 页面搭配 does (the current pairing with only its 123 page changed,
     * written to the layout store) and hands the keyboard what the store reads back.
     */
    private fun pair123Page(
        layout: LayoutInput.Builtin,
        pageId: String,
        page: KeyboardPageDefinition? = null,
    ) {
        directory.mkdirs()
        val store = LayoutStore(directory.absolutePath)
        val key = KeyboardCompositionKey(BuiltinTemplateSchemas.QUANPIN, layout)
        val current = store.load().let { if (page != null) it.withPage(page) else it }
        store.save(
            current.withComposition(
                current.composition(key).withPageId(pageId, KeyboardPageKind.NUMERIC),
                key.rawValue,
            ),
        )
        KeyboardLayoutCatalog.install(store.load())
    }

    private fun withPairedKeypad(block: (FeatureHost) -> Unit) {
        pair123Page(LayoutInput.Builtin.QWERTY26, KeyboardPagePresets.NUMERIC_KEYPAD_ID)
        val host = FeatureHost(engine.backend(), "qwerty26")
        try {
            host.start()
            block(host)
        } finally {
            host.close()
            kotlinx.coroutines.runBlocking { host.backend.shutdown() }
        }
    }

    /** The digits stand in a 3x3 grid with 0 right of the 6 and 符号 under the 9: the keypad, not the row page. */
    private fun assertThePadIsDrawn(host: FeatureHost) {
        host.main2 {
            val view = host.find(KeyboardView::class.java)
            val labels = view.keys.map { it.label }

            fun at(label: String): KeyBounds {
                val index = labels.indexOf(label)
                assertTrue("no key '$label' on $labels", index >= 0)
                return view.bounds[index]
            }
            val grid = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))
            for (row in grid) {
                assertTrue("$row share a row on $labels", row.map { at(it).top }.distinct().size == 1)
            }
            for (column in 0..2) {
                val tops = grid.map { at(it[column]).top }
                assertEquals("column $column on $labels", tops.sorted(), tops)
                assertTrue("column $column stacks on $labels", tops.distinct().size == 3)
            }
            val symbols = view.keys.indexOfFirst { it.action == LayoutAction.pageSwitch(LayoutPageTarget.SYMBOLS) }
            assertTrue("no 符号 key on $labels", symbols >= 0)
            assertEquals("符号 is under the 9", at("9").left, view.bounds[symbols].left, 1f)
            assertEquals("0 is beside the 6", at("6").top, at("0").top, 1f)
            assertTrue("0 is right of the 6", at("0").left >= at("6").right)
        }
    }

    @Test
    fun the123KeyOpensThePairedKeypadInChinese() =
        withPairedKeypad { host ->
            host.tap(host.string(R.string.ime_open_numbers_page))
            host.awaitIdle()
            assertThePadIsDrawn(host)
            host.tap("5")
            host.assertText("5")
        }

    @Test
    fun theRowSymbolPagesCycleWhenPairedWithTheNumericKeypad() =
        withPairedKeypad { host ->
            host.tap(host.string(R.string.ime_open_numbers_page))
            host.tap(host.string(R.string.ime_open_symbols_page))
            val firstPage = host.labelsOf(KeyboardView::class.java)
            repeat(4) { turn ->
                host.main {
                    val view = host.find(KeyboardView::class.java)
                    val thirdRow =
                        view.bounds
                            .map { it.top }
                            .distinct()
                            .sorted()[2]
                    val switch = view.bounds.indexOfFirst { it.top == thirdRow }
                    assertTrue(
                        view.accessibilityNodeProvider.performAction(switch, AccessibilityNodeInfo.ACTION_CLICK, null),
                    )
                }
                host.awaitIdle()
                val current = host.labelsOf(KeyboardView::class.java)
                if (turn % 2 == 0) {
                    assertTrue("the other symbol page is shown", firstPage != current)
                } else {
                    assertEquals("the switch returns to the first symbol page", firstPage, current)
                }
            }
            host.tap(host.string(R.string.ime_return_to_letters))
            assertTrue("typing resumes on letters", "a" in host.labelsOf(KeyboardView::class.java))
        }

    @Test
    fun the123KeyOpensThePairedKeypadInEnglish() =
        withPairedKeypad { host ->
            host.tap(host.string(R.string.ime_language_to_english))
            host.tap(host.string(R.string.ime_open_numbers_page))
            host.awaitIdle()
            assertThePadIsDrawn(host)
            host.tap("5")
            host.assertText("5")
        }

    /** Every key of the 123 page that is showing, by what it does, with where it stands. */
    private fun FeatureHost.padKeys(): Map<String, KeyBounds> =
        main2 {
            val view = find(KeyboardView::class.java)
            view.keys.indices.associate { index ->
                val action = view.keys[index].action
                "${action.type}:${action.value}:${action.pageSwitchTarget}" to view.bounds[index]
            }
        }

    @Test
    fun the26KeyPadIsTheNineKeyPad() {
        lateinit var paired: Map<String, KeyBounds>
        withPairedKeypad { host ->
            host.tap(host.string(R.string.ime_open_numbers_page))
            host.awaitIdle()
            paired = host.padKeys()
            // The space bar stands in the right column and types a space.
            host.tap("5")
            host.tap(host.string(R.string.ime_space))
            host.assertText("5 ")
        }
        KeyboardLayoutCatalog.install(LayoutRegistry.create())
        val host = FeatureHost(engine.backend(), "phone9")
        try {
            host.start()
            host.tap(host.string(R.string.ime_open_numbers_page))
            host.awaitIdle()
            // Same keys in the same places, so the pad feels the same whichever letters it is paired with.
            assertEquals(host.padKeys(), paired)
        } finally {
            host.close()
            kotlinx.coroutines.runBlocking { host.backend.shutdown() }
        }
    }

    @Test
    fun aCopiedPadLinesItsBottomRowUpWithTheRailAndTheColumns() {
        val copy = KeyboardPagePresets.numericKeypad.duplicated("my-pad", "我的数字盘")
        pair123Page(LayoutInput.Builtin.QWERTY26, copy.id, copy)
        val host = FeatureHost(engine.backend(), "qwerty26")
        try {
            host.start()
            host.tap(host.string(R.string.ime_open_numbers_page))
            host.awaitIdle()
            host.main2 {
                val view = host.find(KeyboardView::class.java)

                fun at(match: (LayoutKey) -> Boolean): KeyBounds = view.bounds[view.keys.indexOfFirst(match)]

                fun digit(text: String) = at { it.action == LayoutAction.literal(text) }
                val rail = at { it.action.type == LayoutActionKind.LITERAL && it.action.value == "." }
                val back = at { it.action.pageSwitchTarget == LayoutPageTarget.LETTERS }
                val symbols = at { it.action.pageSwitchTarget == LayoutPageTarget.SYMBOLS }
                val language = at { it.action.type == LayoutActionKind.LANGUAGE_SWITCH }
                val space = at { it.action.type == LayoutActionKind.SPACE }
                val enter = at { it.action.type == LayoutActionKind.ENTER }
                // One key under each rail and each digit column, as on the pad it was copied from.
                for ((below, above) in listOf(
                    back to rail,
                    language to digit("7"),
                    space to digit("8"),
                    symbols to digit("9"),
                    enter to digit("0"),
                )) {
                    assertEquals("$below under $above", above.left, below.left, 1f)
                    assertEquals("$below under $above", above.right, below.right, 1f)
                }
            }
        } finally {
            host.close()
            kotlinx.coroutines.runBlocking { host.backend.shutdown() }
        }
    }

    @Test
    fun aNineKeyKeyboardDrawsThe123PageTheUserAuthored() {
        val mine =
            KeyboardPagePresets.numericRowsPage(ascii = false).duplicated("my-digits", "我的数字").let { copy ->
                copy.withRows(listOf(LayoutRow(listOf(literal("甲"), literal("乙"), literal("丙")))))
            }
        pair123Page(LayoutInput.Builtin.PHONE9, mine.id, mine)
        val host = FeatureHost(engine.backend(), "phone9")
        try {
            host.start()
            host.tap(host.string(R.string.ime_open_numbers_page))
            host.awaitIdle()
            val labels = host.labelsOf(KeyboardView::class.java)
            assertTrue(labels.toString(), listOf("甲", "乙", "丙").all { it in labels })
            host.tap("乙")
            host.assertText("乙")
        } finally {
            host.close()
            kotlinx.coroutines.runBlocking { host.backend.shutdown() }
        }
    }

    private fun literal(text: String) = LayoutKey(text, action = LayoutAction.literal(text))

    companion object {
        private lateinit var engine: DeployedEngine

        @BeforeClass
        @JvmStatic
        fun deploy() {
            engine = DeployedEngine("paired-keypad", english = false)
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            engine.delete()
        }
    }
}
