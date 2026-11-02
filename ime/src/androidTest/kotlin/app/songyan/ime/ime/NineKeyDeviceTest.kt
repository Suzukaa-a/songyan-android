package app.songyan.ime.ime

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songyan.ime.ime.panels.SymbolPagePanelView
import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.KeyboardComposition
import app.songyan.ime.layout.KeyboardCompositionKey
import app.songyan.ime.layout.KeyboardPageCategory
import app.songyan.ime.layout.KeyboardPagePresets
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutAuxiliaryArea
import app.songyan.ime.layout.LayoutDefinition
import app.songyan.ime.layout.LayoutInput
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutRegistry
import app.songyan.ime.layout.LayoutRow
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The nine-key keyboard over the shipped engine: a layout the user (or the iOS app) authored is drawn
 * from its own data and types through the engine, and the categorized symbol page opens from the
 * keyboard, switches categories and types. Keys are tapped through their accessibility actions.
 */
@RunWith(AndroidJUnit4::class)
class NineKeyDeviceTest {
    @After
    fun restoreTheBundledCatalog() {
        KeyboardLayoutCatalog.install(LayoutRegistry.create())
    }

    private fun literal(text: String) = LayoutKey(text, action = LayoutAction.literal(text))

    private fun nineKeyPairing() =
        KeyboardCompositionKey(BuiltinTemplateSchemas.QUANPIN, LayoutInput.Builtin.PHONE9).rawValue

    /** The bundled nine-key with its middle-row 5 key renamed, its rails declared and a control row of its own. */
    private fun customNine(): LayoutDefinition {
        val preset = BuiltinLayouts.phone9
        val rows =
            preset.rows.mapIndexed { index, row ->
                if (index != 1) {
                    row
                } else {
                    LayoutRow(row.keys.mapIndexed { k, key -> if (k == 1) key.copy(label = "我的") else key })
                }
            }
        return preset.copy(
            id = "custom9",
            name = "我的九键",
            rows = rows,
            auxiliaryAreas =
                listOf(
                    LayoutAuxiliaryArea(
                        LayoutAuxiliaryArea.Position.LEADING,
                        LayoutAuxiliaryArea.Role.PUNCTUATION,
                        listOf(literal("@"), literal("#")),
                    ),
                    LayoutAuxiliaryArea(LayoutAuxiliaryArea.Position.LEADING, LayoutAuxiliaryArea.Role.DISAMBIGUATION),
                    LayoutAuxiliaryArea(
                        LayoutAuxiliaryArea.Position.TRAILING,
                        LayoutAuxiliaryArea.Role.EDITING,
                        listOf(
                            LayoutKey("⌫", action = LayoutAction(LayoutActionKind.BACKSPACE)),
                            LayoutKey("重输", width = 2.0, action = LayoutAction(LayoutActionKind.CLEAR_INPUT)),
                        ),
                    ),
                ),
        )
    }

    private fun install(layout: LayoutDefinition) {
        KeyboardLayoutCatalog.install(
            LayoutRegistry
                .create()
                .install(layout.encoded())
                .withComposition(KeyboardComposition(mainLayoutID = layout.id), nineKeyPairing()),
        )
    }

    @Test
    fun aCustomNineKeyLayoutDrawsItsOwnKeysAndTypesThroughTheEngine() =
        withHost { host ->
            install(customNine())
            host.start()
            val labels = host.labelsOf(KeyboardView::class.java)
            // The renamed key keeps its corner digit for TalkBack, the rails are the declared ones.
            assertTrue(labels.toString(), "我的 5" in labels)
            assertTrue(labels.toString(), "@" in labels && "#" in labels)
            assertEquals("the declared rail leads", listOf("@", "#"), labels.take(2))
            // 5 4 6 spells 林 (lin) on the nine-key; the renamed key still sends its 5.
            host.tap("我的", prefix = true)
            host.tap("GHI", prefix = true)
            host.tap("MNO", prefix = true)
            assertTrue(
                "composing: ${host.text()}",
                host.await {
                    host.text().isNotEmpty() &&
                        host.text().none(Char::isDigit)
                },
            )
            // While composing, readings take the leading rail's slots.
            assertTrue(host.await { host.labelsOf(KeyboardView::class.java).first() != "@" })
            // The author's "重输" is the keyboard's own key name, so a screen reader says it in the system language.
            host.tap(host.string(R.string.ime_clear))
            host.awaitIdle()
            assertEquals("", host.text())
            // Idle again: the declared symbol types itself.
            host.tap("@")
            host.assertText("@")
        }

    @Test
    fun anExplicitCustomFirstKeyTypesItsCommaInsteadOfCycling() =
        withHost { host ->
            install(customNine().copy(t9FirstKeyBehavior = app.songyan.ime.layout.T9FirstKeyBehavior.CUSTOM))
            host.start()
            host.tap("，。？")
            host.tap("，。？")
            host.assertText("，，")
        }

    @Test
    fun onlyTheDynamicFirstKeysPrimaryTapCyclesTheLocalSymbols() =
        withHost { host ->
            val base = customNine()
            val rows = base.rows.toMutableList()
            val firstRow = rows[0].keys.toMutableList()
            firstRow[0] =
                firstRow[0].copy(
                    label = "首键",
                    longPress =
                        listOf(
                            app.songyan.ime.layout
                                .LayoutOption("1", LayoutAction.literal("1")),
                            app.songyan.ime.layout
                                .LayoutOption("同符号", LayoutAction.literal("、")),
                        ),
                )
            rows[0] = LayoutRow(firstRow)
            val areas =
                base.auxiliaryAreas!!.map { area ->
                    if (area.role == LayoutAuxiliaryArea.Role.PUNCTUATION) {
                        area.copy(
                            keys =
                                listOf(
                                    LayoutKey("栏键", action = LayoutAction.literal("、")),
                                ),
                        )
                    } else {
                        area
                    }
                }
            install(
                base.copy(
                    rows = rows,
                    auxiliaryAreas = areas,
                    t9FirstKeyBehavior = app.songyan.ime.layout.T9FirstKeyBehavior.QUICK_SYMBOLS,
                    t9QuickSymbols = "；：！、",
                ),
            )
            host.quickSymbolsFlow.value = "？"
            host.start()
            host.tap("首键")
            host.assertText("、")
            host.tap("首键")
            host.assertText("，")
            host.setText("")
            host.tap("栏键")
            host.tap("栏键")
            host.assertText("、、")
            host.setText("")
            repeat(2) {
                host.main {
                    val keyboard = host.find(KeyboardView::class.java)
                    val index = keyboard.spokenLabels.indexOf("首键")
                    val provider = keyboard.accessibilityNodeProvider
                    assertTrue(provider.performAction(index, AccessibilityNodeInfo.ACTION_LONG_CLICK, null))
                    val option =
                        provider.createAccessibilityNodeInfo(index)!!.actionList.single {
                            it.label?.toString() == "同符号"
                        }
                    assertTrue(provider.performAction(index, option.id, null))
                }
                host.awaitIdle()
            }
            host.assertText("、、")
        }

    @Test
    fun theBottomRowStaysTheKeyboardsOwnWhateverTheLayoutDeclares() =
        withHost { host ->
            install(customNine())
            host.start()
            val labels = host.labelsOf(KeyboardView::class.java)
            // The declared control row (布局, 空格, 🌐) is data only: the generated row has 123, space and Return.
            assertTrue(labels.toString(), host.string(R.string.ime_open_numbers_page) in labels)
            assertTrue(labels.toString(), host.string(R.string.ime_space) in labels)
            assertFalse(labels.toString(), "布局" in labels)
        }

    @Test
    fun aNineKeyLayoutWithoutTheTelephoneStructureFallsBackToRows() =
        withHost { host ->
            val preset = BuiltinLayouts.phone9
            val broken =
                preset.copy(
                    id = "broken9",
                    name = "坏九键",
                    rows =
                        preset.rows.mapIndexed { i, r ->
                            if (i == 1) LayoutRow(r.keys.drop(1)) else r
                        },
                )
            install(broken)
            host.start()
            val labels = host.labelsOf(KeyboardView::class.java)
            assertTrue(labels.toString(), host.string(R.string.ime_open_numbers_page) in labels)
            assertTrue(labels.toString(), host.string(R.string.ime_space) in labels)
        }

    @Test
    fun theNineKeyBuiltInStillDrawsTheQuickSymbolRail() =
        withHost { host ->
            host.start()
            val labels = host.labelsOf(KeyboardView::class.java)
            assertEquals(listOf("，", "。", "？"), labels.take(3))
            assertTrue(labels.toString(), host.string(R.string.ime_clear) in labels)
        }

    @Test
    fun theNineKeySymbolPageIsTheCategorizedPage() =
        withHost { host ->
            host.start()
            host.tap(host.string(R.string.ime_open_numbers_page))
            host.tap(host.string(R.string.ime_open_symbols_page))
            host.awaitIdle()
            val tabs = host.labelsOf(SymbolPagePanelView::class.java)
            for (tab in listOf("常用", "中文", "英文", "括号")) assertTrue("$tab in $tabs", tab in tabs)
            for (key in listOf(R.string.ime_return_to_letters, R.string.ime_open_numbers_page, R.string.ime_space)) {
                assertTrue(host.string(key) in host.labelsOf(SymbolPagePanelView::class.java))
            }
            // Switching tabs shows that tab's symbols.
            host.tap("英文", SymbolPagePanelView::class.java)
            val english = host.labelsOf(SymbolPagePanelView::class.java)
            assertTrue("[ in $english", "[" in english)
            host.tap("中文", SymbolPagePanelView::class.java)
            host.tap("【", SymbolPagePanelView::class.java)
            host.assertText("【")
            // One symbol returns to the letters: the page closed and the nine-key shell is back.
            assertTrue(host.await { host.findOrNull(SymbolPagePanelView::class.java) == null })
            assertTrue(host.string(R.string.ime_clear) in host.labelsOf(KeyboardView::class.java))
        }

    @Test
    fun theCategoryRailTouchTargetsAreAtLeast48dp() =
        withHost { host ->
            host.start()
            host.tap(host.string(R.string.ime_open_numbers_page))
            host.tap(host.string(R.string.ime_open_symbols_page))
            host.awaitIdle()
            val labels = host.labelsOf(SymbolPagePanelView::class.java)
            val first = labels.indexOf("常用")
            val second = labels.indexOf("最近").takeIf { it >= 0 } ?: labels.indexOf("中文")
            assertTrue(labels.toString(), first >= 0 && second >= 0)
            val (density, heights) =
                host.main2 {
                    val panel = host.find(SymbolPagePanelView::class.java)
                    val provider = panel.accessibilityNodeProvider
                    panel.resources.displayMetrics.density to
                        listOf(first, second).map { index ->
                            android.graphics
                                .Rect()
                                .also { provider.createAccessibilityNodeInfo(index)!!.getBoundsInParent(it) }
                                .height()
                        }
                }
            for (height in heights) assertTrue("rail item ${height / density} dp", height >= 48 * density - 1)
        }

    @Test
    fun aLockedSymbolPageStaysOpenAndTheWayBackClosesIt() =
        withHost { host ->
            host.start()
            host.tap(host.string(R.string.ime_open_numbers_page))
            host.tap(host.string(R.string.ime_open_symbols_page))
            host.tap(host.string(R.string.ime_symbol_lock), SymbolPagePanelView::class.java)
            host.tap("，", SymbolPagePanelView::class.java)
            host.tap("。", SymbolPagePanelView::class.java)
            host.assertText("，。")
            assertTrue(
                host.labelsOf(SymbolPagePanelView::class.java).toString(),
                host.findOrNull(SymbolPagePanelView::class.java) != null,
            )
            assertTrue(host.string(R.string.ime_symbol_locked) in host.labelsOf(SymbolPagePanelView::class.java))
            host.tap(host.string(R.string.ime_delete), SymbolPagePanelView::class.java)
            host.assertText("，")
            host.tap(host.string(R.string.ime_return_to_letters), SymbolPagePanelView::class.java)
            assertTrue(host.await { host.findOrNull(SymbolPagePanelView::class.java) == null })
            assertTrue(host.string(R.string.ime_clear) in host.labelsOf(KeyboardView::class.java))
        }

    @Test
    fun anAuthoredCategorizedPageOpensWithItsOwnCategoriesOnAnyLayout() =
        withHost(layout = "qwerty26") { host ->
            val mine =
                KeyboardPagePresets.symbolsCategorized.copy(
                    id = "my-categories",
                    name = "我的分类",
                    categories =
                        listOf(
                            KeyboardPageCategory("first", "甲组", columns = 3, keys = listOf(literal("甲"), literal("乙"))),
                            KeyboardPageCategory("second", "乙组", columns = 2, keys = listOf(literal("丙"))),
                        ),
                )
            KeyboardLayoutCatalog.install(
                LayoutRegistry
                    .create()
                    .withPage(mine)
                    .withComposition(
                        KeyboardComposition(mainLayoutID = "qwerty26", symbolPageID = mine.id),
                        KeyboardCompositionKey(BuiltinTemplateSchemas.QUANPIN, LayoutInput.Builtin.QWERTY26).rawValue,
                    ),
            )
            host.start()
            host.tap(host.string(R.string.ime_open_numbers_page))
            host.tap(host.string(R.string.ime_open_symbols_page))
            host.awaitIdle()
            val labels = host.labelsOf(SymbolPagePanelView::class.java)
            assertTrue(labels.toString(), "甲组" in labels && "乙组" in labels && "甲" in labels)
            host.tap("乙组", SymbolPagePanelView::class.java)
            host.tap("丙", SymbolPagePanelView::class.java)
            host.assertText("丙")
        }

    private fun <T> withHost(
        layout: String = "phone9",
        block: (FeatureHost) -> T,
    ): T {
        val host = FeatureHost(engine.backend(), layout)
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
            engine = DeployedEngine("nine-key", english = false)
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            engine.delete()
        }
    }
}
