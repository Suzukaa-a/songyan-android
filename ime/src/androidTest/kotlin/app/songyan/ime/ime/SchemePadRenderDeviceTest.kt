package app.songyan.ime.ime

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.KeyboardComposition
import app.songyan.ime.layout.KeyboardCompositionKey
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutDefinition
import app.songyan.ime.layout.LayoutInput
import app.songyan.ime.layout.LayoutRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The scheme pads in the real key surface, over the shipped engine. The four-row zhuyin pad and the
 * three-row cangjie pad draw through the ordinary rows page: their rows stay inside the key area of
 * the standard page (D24's 256 dp limit), every key is on screen and hittable without scrolling, and
 * the ear hears the pad's own caps rather than the key codes behind them.
 *
 * The pads are paired with the bundled full-pinyin scheme as a user's copy would be; the true
 * bopomofo/cangjie engine fixtures are exercised by the scheme device tests that own them.
 */
@RunWith(AndroidJUnit4::class)
class SchemePadRenderDeviceTest {
    private class Surface(
        val rows: List<Int>,
        val keyTypes: List<LayoutActionKind>,
        val height: Int,
        val width: Int,
        val density: Float,
        val gapDp: Float,
        val bounds: List<KeyBounds>,
    )

    private fun zhuyinPad(): LayoutDefinition = BuiltinLayouts.zhuyin41.copyWithId("my-zhuyin", "我的注音")

    private fun cangjiePad(): LayoutDefinition = BuiltinLayouts.cangjie26.copyWithId("my-cangjie", "我的仓颉")

    @After
    fun restoreTheBundledCatalogAndGeometry() {
        KeyboardLayoutCatalog.install(LayoutRegistry.create())
        KeyboardPages.trim()
        KeyboardGeometry.keyHeight = KeyHeight.DEFAULT
        KeyboardGeometry.bottomGapDp = 0
    }

    /** Installs the pad as the user's copy and pairs it with the bundled scheme the test engine ships. */
    private fun withPad(
        pad: LayoutDefinition,
        block: (FeatureHost) -> Unit,
    ) {
        KeyboardLayoutCatalog.install(
            LayoutRegistry
                .create()
                .install(pad.encoded())
                .withComposition(
                    KeyboardComposition(mainLayoutID = pad.id),
                    KeyboardCompositionKey(BuiltinTemplateSchemas.QUANPIN, LayoutInput.Builtin.QWERTY26).rawValue,
                ),
        )
        KeyboardPages.trim()
        val host = FeatureHost(engine.backend(), "qwerty26")
        try {
            host.start()
            host.awaitIdle()
            block(host)
        } finally {
            host.close()
            runBlocking { host.backend.shutdown() }
        }
    }

    /** The drawn key surface: rows top to bottom, the keys' actions and the area they sit in. */
    private fun surface(host: FeatureHost): Surface =
        host.main2 {
            host.relayout()
            val view = host.find(KeyboardView::class.java)
            val counts = LinkedHashMap<Int, Int>()
            for (box in view.bounds) counts.merge(box.top.roundToInt(), 1, Int::plus)
            Surface(
                rows = counts.values.toList(),
                keyTypes = view.keys.map { it.action.type },
                height = view.height,
                width = view.width,
                density = view.density,
                gapDp = view.metrics.keyAreaBottomGapDp + view.metrics.keyAreaExtraBottomDp,
                bounds = view.bounds.toList(),
            )
        }

    @Test
    fun theFourRowPadDrawsItsFourEncodingRowsAndDeclaredFooter() =
        withPad(zhuyinPad()) { host ->
            val surface = surface(host)
            // Four encoding rows, Delete closing the fourth, then the footer every 26-key board shares.
            assertEquals(5, surface.rows.size)
            assertEquals(listOf(11, 10, 10, 11), surface.rows.dropLast(1))
            // Forty-one encoding keys send engine codes.
            assertTrue(surface.keyTypes.take(41).all { it == LayoutActionKind.CODE })
            assertEquals(LayoutActionKind.BACKSPACE, surface.keyTypes[41])
            assertTrue(surface.keyTypes.none { it == LayoutActionKind.SHIFT })
            assertEquals(1, surface.keyTypes.count { it == LayoutActionKind.BACKSPACE })
        }

    @Test
    fun everyFourRowPadKeyIsInsideTheKeyAreaAndHittable() =
        withPad(zhuyinPad()) { host ->
            val surface = surface(host)
            assertTrue(
                "a key reached outside the surface: " + surface.bounds.map { it.bottom },
                surface.bounds.all { it.bottom <= surface.height + 1f },
            )
            assertTrue(surface.bounds.all { it.top >= -1f && it.left >= -1f })
            assertTrue(surface.bounds.all { it.right <= surface.width + 1f })
            // The five rows share the area: no row is squeezed away and none overflows.
            val rowHeight = surface.bounds.first().bottom - surface.bounds.first().top
            assertTrue("row height " + rowHeight, rowHeight > 0f)
            val hit = host.main2 { host.find(KeyboardView::class.java).hitBounds.toList() }
            assertEquals(surface.bounds.size, hit.size)
            assertTrue(hit.all { it.right > it.left && it.bottom > it.top })
            // Everything is on screen: the surface offers no scrolling to reach a hidden row.
            val node =
                host.main2 {
                    val view = host.find(KeyboardView::class.java)
                    view.accessibilityNodeProvider.createAccessibilityNodeInfo(View.NO_ID)!!
                }
            assertFalse("the key surface must not scroll", node.isScrollable)
            val tapped =
                host.main2 {
                    val view = host.find(KeyboardView::class.java)
                    val index = view.keys.indexOfFirst { it.label == "ㄅ" }
                    assertTrue("no ㄅ key on " + view.keys.map { it.label }, index >= 0)
                    view.accessibilityNodeProvider.performAction(index, AccessibilityNodeInfo.ACTION_CLICK, null)
                }
            assertTrue("a bopomofo cap must take a tap", tapped)
            host.awaitIdle()
        }

    @Test
    fun theThreeChosenHeightsStayDistinctOnTheFiveRowPad() =
        withPad(zhuyinPad()) { host ->
            val screen = host.context.resources.configuration.screenHeightDp
            assumeTrue("a portrait window gets the full preset heights", screen > KeyboardGeometry.SHORT_WINDOW_DP)
            val areas =
                KeyHeight.entries.map { preset ->
                    host.keyHeightFlow.value = preset.wire
                    assertTrue("$preset was applied", host.await { KeyboardGeometry.keyHeight == preset })
                    val surface = surface(host)
                    surface.height / surface.density - surface.gapDp
                }
            assertEquals(areas.toString(), 3, areas.distinct().size)
            assertEquals(areas.toString(), areas.sorted(), areas)
            for ((preset, area) in KeyHeight.entries.zip(areas)) {
                assertEquals(
                    "$preset $area",
                    KeyboardGeometry.portraitAreaDp(preset, screen).toDouble(),
                    area.toDouble(),
                    1.0,
                )
                assertTrue("$preset $area", area <= 256f + 0.5f)
            }
        }

    @Test
    fun switchingPagesKeepsTheFourRowPadHeight() =
        withPad(zhuyinPad()) { host ->
            fun heightDp() = surface(host).let { it.height / it.density }
            val letterRows = surface(host).rows
            val letters = heightDp()
            host.tapNumbersPage()
            host.awaitIdle()
            val numbers = heightDp()
            host.tapSymbolsPage()
            host.awaitIdle()
            val symbols = heightDp()
            if (host.labelsOf(KeyboardView::class.java).isNotEmpty()) host.tapControl(ControlKey.RETURN_TO_LETTERS)
            host.awaitIdle()
            val back = heightDp()
            host.tapControl(ControlKey.LANGUAGE_TOGGLE)
            host.awaitIdle()
            val english = heightDp()
            host.tapControl(ControlKey.LANGUAGE_TOGGLE)
            host.awaitIdle()
            val chinese = heightDp()
            val heights =
                listOf(
                    "123" to numbers,
                    "symbols" to symbols,
                    "back" to back,
                    "english" to english,
                    "chinese" to chinese,
                )
            for ((page, height) in heights) {
                assertTrue("$page: letters $letters, page $height", abs(height - letters) <= 1f)
            }
            assertEquals(letterRows, surface(host).rows)
        }

    @Test
    fun talkBackReadsTheZhuyinCapsNotTheCodes() =
        withPad(zhuyinPad()) { host ->
            val spoken = host.labelsOf(KeyboardView::class.java)
            for (cap in listOf("ㄅ", "ㄧ", "ㄏ", "ㄠ", "ㄥ", "ˊ", "˙")) {
                assertTrue("$cap missing from $spoken", cap in spoken)
            }
            for (code in listOf("1", "2", "3", "q", "p", ";", ",", ".", "/")) {
                assertFalse("$code must not be the spoken face: $spoken", code in spoken)
            }
        }

    @Test
    fun talkBackReadsTheCangjieRadicalsNotTheLetters() =
        withPad(cangjiePad()) { host ->
            val surface = surface(host)
            // Three radical rows, then the footer every 26-key board shares.
            assertEquals(4, surface.rows.size)
            assertEquals(listOf(10, 9, 9), surface.rows.dropLast(1))
            assertEquals(1, surface.keyTypes.count { it == LayoutActionKind.SHIFT })
            assertEquals(1, surface.keyTypes.count { it == LayoutActionKind.BACKSPACE })
            val spoken = host.labelsOf(KeyboardView::class.java)
            for (radical in listOf("人", "弓", "火", "日", "手", "難")) {
                assertTrue("$radical missing from $spoken", radical in spoken)
            }
            for (letter in listOf("a", "n", "f", "q", "o", "x")) {
                assertFalse("$letter must not be the spoken face: $spoken", letter in spoken)
            }
        }

    companion object {
        private lateinit var engine: DeployedEngine

        @BeforeClass
        @JvmStatic
        fun deploy() {
            engine = DeployedEngine("scheme-pad-render", english = false)
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            engine.delete()
        }
    }
}
