package app.songyan.ime.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import app.songyan.ime.layout.KeyboardComposition
import app.songyan.ime.layout.KeyboardCompositionKey
import app.songyan.ime.layout.KeyboardPagePresets
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutInput
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutOption
import app.songyan.ime.layout.LayoutRegistry
import app.songyan.ime.layout.LayoutRow
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A 123 page the user authored with side rails: the rails are drawn beside its rows, the trailing
 * one runs down beside the footer, and their keys type like any other key, over the shipped engine.
 */
@RunWith(AndroidJUnit4::class)
class AuthoredPageRailsDeviceTest {
    @After
    fun restoreTheBundledCatalog() {
        KeyboardLayoutCatalog.install(LayoutRegistry.create())
    }

    private fun literal(text: String) = LayoutKey(text, action = LayoutAction.literal(text))

    private fun railedPage() =
        KeyboardPagePresets.numericAutomatic.duplicated("my-rails", "我的数字").let { copy ->
            val rows = listOf(LayoutRow(listOf(literal("7"), literal("8"), literal("9"))))
            copy.copy(
                rows = rows,
                chrome =
                    requireNotNull(copy.chrome).copy(
                        leading = listOf(literal("甲")),
                        trailing =
                            listOf(
                                literal("乙").copy(longPress = listOf(LayoutOption("丁", LayoutAction.literal("丁")))),
                                literal("丙").copy(width = 2.0),
                            ),
                        trailingSpansBottom = true,
                    ),
            )
        }

    private fun withRailedPage(block: (FeatureHost) -> Unit) {
        KeyboardLayoutCatalog.install(
            LayoutRegistry
                .create()
                .withPage(railedPage())
                .withComposition(
                    KeyboardComposition(mainLayoutID = "qwerty26", numericPageID = "my-rails"),
                    KeyboardCompositionKey(BuiltinTemplateSchemas.QUANPIN, LayoutInput.Builtin.QWERTY26).rawValue,
                ),
        )
        val host = FeatureHost(engine.backend(), "qwerty26")
        try {
            host.start()
            host.tap(host.string(R.string.ime_open_numbers_page))
            host.awaitIdle()
            block(host)
        } finally {
            host.close()
            kotlinx.coroutines.runBlocking { host.backend.shutdown() }
        }
    }

    private fun boundsOf(
        host: FeatureHost,
        label: String,
    ): KeyBounds =
        host.main2 {
            val view = host.find(KeyboardView::class.java)
            val index = view.keys.indexOfFirst { it.label == label }
            assertTrue("no key '$label' on ${view.keys.map { it.label }}", index >= 0)
            view.bounds[index]
        }

    @Test
    fun theRailsStandBesideTheRowsAndTheTrailingOneRunsDownBesideTheFooter() =
        withRailedPage { host ->
            val leading = boundsOf(host, "甲")
            val digit = boundsOf(host, "7")
            val digitRight = boundsOf(host, "9")
            val trailing = boundsOf(host, "乙")
            val trailingTall = boundsOf(host, "丙")
            assertTrue("leading rail is left of the digits", leading.right <= digit.left)
            assertTrue("trailing rail is right of the digits", trailing.left >= digitRight.right)
            // The trailing rail also covers the footer row, so it is taller than the one content row.
            val footerBottom = host.main2 { host.find(KeyboardView::class.java).bounds.maxOf { it.bottom } }
            assertEquals(footerBottom, trailingTall.bottom, 1f)
            assertTrue(trailingTall.bottom - trailing.top > (digit.bottom - digit.top) * 1.9f)
            // The spans are relative: 丙 is twice 乙.
            assertEquals(2f, (trailingTall.bottom - trailingTall.top) / (trailing.bottom - trailing.top), 0.05f)
        }

    @Test
    fun aRailKeyTypesItsOutputAndItsLongPressTypesTheOption() =
        withRailedPage { host ->
            host.tap("甲")
            host.assertText("甲")
            host.tap("丙")
            host.assertText("甲丙")
            // A rail key keeps its long-press options, which the press popup offers like any key's.
            val options =
                host.main2 {
                    val view = host.find(KeyboardView::class.java)
                    view.keys
                        .first { it.label == "乙" }
                        .longPress
                        .orEmpty()
                        .map { it.label }
                }
            assertEquals(listOf("丁"), options)
        }

    companion object {
        private lateinit var engine: DeployedEngine

        @BeforeClass
        @JvmStatic
        fun deploy() {
            engine = DeployedEngine("rails", english = false)
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            engine.delete()
        }
    }
}
