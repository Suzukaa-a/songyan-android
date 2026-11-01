package app.songyan.ime.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.layout.KeyboardComposition
import app.songyan.ime.layout.KeyboardCompositionKey
import app.songyan.ime.layout.KeyboardPageKind
import app.songyan.ime.layout.KeyboardPagePresets
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutInput
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutPageJump
import app.songyan.ime.layout.LayoutPageTarget
import app.songyan.ime.layout.LayoutRegistry
import app.songyan.ime.layout.LayoutRow
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A key the user put on the 26-key symbol page that jumps to the 9-key 123 page, over the shipped
 * engine: the 9-key runs for the visit, and 返回 brings back the user's own 26-key letters.
 */
@RunWith(AndroidJUnit4::class)
class PageJumpDeviceTest {
    @After
    fun restoreTheBundledCatalog() {
        KeyboardLayoutCatalog.install(LayoutRegistry.create())
    }

    private val toNineKeyDigits = LayoutPageJump(LayoutInput.Builtin.PHONE9, LayoutPageTarget.NUMERIC)

    private fun installSymbolPageWithJump() {
        val base = KeyboardPagePresets.symbolsSystem.duplicated("my-symbols", "我的符号")
        val jump = LayoutKey("123", action = LayoutAction.layoutPageSwitch(toNineKeyDigits))
        val page =
            base.copy(
                rows =
                    base.rows + LayoutRow(listOf(LayoutKey("@", action = LayoutAction.literal("@")), jump)),
            )
        KeyboardLayoutCatalog.install(
            LayoutRegistry
                .create()
                .withPage(page)
                .withComposition(
                    KeyboardComposition(mainLayoutID = "qwerty26", symbolPageID = page.id),
                    KeyboardCompositionKey(BuiltinTemplateSchemas.QUANPIN, LayoutInput.Builtin.QWERTY26).rawValue,
                ),
        )
    }

    private fun jumpName(host: FeatureHost): String =
        host.context.getString(R.string.ime_jump_to_numbers, host.string(R.string.ime_layout_phone9))

    private fun openSymbolPage(host: FeatureHost) {
        host.tap(host.string(R.string.ime_open_numbers_page))
        host.tap(host.string(R.string.ime_open_symbols_page))
        host.awaitIdle()
    }

    @Test
    fun aJumpKeyOpensThe9Key123PageAndBackReturnsToTheUsersOwnLayout() =
        withHost { host ->
            installSymbolPageWithJump()
            host.start()
            openSymbolPage(host)
            val labels = host.labelsOf(KeyboardView::class.java)
            assertTrue(labels.toString(), jumpName(host) in labels)
            host.tap(jumpName(host))
            assertTrue(host.await { host.main2 { host.controller.layoutKind } == KeyboardLayoutKind.PHONE9 })
            host.awaitIdle()
            assertEquals(KeyboardPageKind.NUMERIC, host.main2 { host.controller.page })
            // The session runs the 9-key for the visit, but the user's layout is not changed.
            assertEquals(KeyboardLayoutKind.PHONE9, host.main2 { host.controller.session?.layout })
            assertEquals(KeyboardLayoutKind.QWERTY26, host.main2 { host.controller.preferredLayout })
            // The 9-key digit pad is what shows: its digits type themselves.
            host.tap("5")
            host.assertText("5")
            // 返回 lands on the 26-key letters the jump came from, not on the 9-key ones.
            host.tap(host.string(R.string.ime_return_to_letters))
            assertTrue(host.await { host.main2 { host.controller.layoutKind } == KeyboardLayoutKind.QWERTY26 })
            host.awaitIdle()
            assertNull(host.main2 { host.controller.page })
            assertEquals(KeyboardLayoutKind.QWERTY26, host.main2 { host.controller.session?.layout })
            val letters = host.labelsOf(KeyboardView::class.java)
            assertTrue(letters.toString(), host.string(R.string.ime_clear) !in letters)
            host.setText("")
            host.type("ni")
            assertTrue(host.barLabels().toString(), host.await { host.barLabels().any { it.contains("你") } })
        }

    @Test
    fun theSymbolKeyOnTheJumpedTo123PageReturnsToTheUsersSymbolPageSoTheJumpLoops() =
        withHost { host ->
            installSymbolPageWithJump()
            host.start()
            openSymbolPage(host)
            repeat(2) { round ->
                host.tap(jumpName(host))
                assertTrue(
                    "round $round",
                    host.await { host.main2 { host.controller.layoutKind } == KeyboardLayoutKind.PHONE9 },
                )
                host.awaitIdle()
                assertEquals(KeyboardPageKind.NUMERIC, host.main2 { host.controller.page })
                // 符 on the visited 9-key 123 page opens the user's own symbol page, which has the jump key again.
                host.tap(host.string(R.string.ime_open_symbols_page))
                assertTrue(
                    "round $round",
                    host.await { host.main2 { host.controller.layoutKind } == KeyboardLayoutKind.QWERTY26 },
                )
                host.awaitIdle()
                assertEquals(KeyboardPageKind.SYMBOLS, host.main2 { host.controller.page })
                assertTrue(jumpName(host) in host.labelsOf(KeyboardView::class.java))
            }
        }

    @Test
    fun aNewFieldAfterAJumpOpensOnTheUsersOwnLayout() =
        withHost { host ->
            installSymbolPageWithJump()
            host.start()
            openSymbolPage(host)
            host.tap(jumpName(host))
            assertTrue(host.await { host.main2 { host.controller.layoutKind } == KeyboardLayoutKind.PHONE9 })
            host.main {
                val field = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
                host.controller.startInput(EditorPolicy.from(field), false)
            }
            assertTrue(host.await { host.main2 { host.controller.layoutKind } == KeyboardLayoutKind.QWERTY26 })
            host.awaitIdle()
            assertNull(host.main2 { host.controller.page })
        }

    @Test
    fun aJumpWithAnUnknownTargetLeavesTheKeyboardAsItWas() =
        withHost { host ->
            installSymbolPageWithJump()
            host.start()
            openSymbolPage(host)
            // A target this version does not know (a file from a newer app would be refused on import;
            // this is the dispatcher's own guard) changes neither the layout nor the page.
            host.main {
                host.controller.enqueueKey(
                    LayoutAction(LayoutActionKind.LAYOUT_PAGE_SWITCH, "phone10:numeric"),
                )
            }
            host.awaitIdle()
            assertEquals(KeyboardLayoutKind.QWERTY26, host.main2 { host.controller.layoutKind })
            assertEquals(KeyboardPageKind.SYMBOLS, host.main2 { host.controller.page })
            assertEquals(KeyboardLayoutKind.QWERTY26, host.main2 { host.controller.preferredLayout })
        }

    private fun <T> withHost(block: (FeatureHost) -> T): T {
        val host = FeatureHost(engine.backend(), "qwerty26")
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
            engine = DeployedEngine("page-jump", english = false)
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            engine.delete()
        }
    }
}
