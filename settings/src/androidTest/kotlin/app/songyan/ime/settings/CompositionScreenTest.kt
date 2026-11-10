package app.songyan.ime.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.songyan.ime.keyboard.engine.InputSchemeFamily
import app.songyan.ime.keyboard.engine.LayoutFamilyContext
import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.KeyboardComposition
import app.songyan.ime.layout.KeyboardCompositionKey
import app.songyan.ime.layout.KeyboardPagePresets
import app.songyan.ime.layout.LayoutInput
import app.songyan.ime.layout.LayoutRegistry
import app.songyan.ime.settings.state.InMemoryLayoutLibrarySource
import app.songyan.ime.settings.state.InstalledScheme
import app.songyan.ime.settings.ui.LayoutLibraryViewModel
import app.songyan.ime.settings.ui.screens.CompositionScreen
import app.songyan.ime.settings.ui.theme.SongyanTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** 页面搭配 names the everyday marks a pairing cannot type when the symbol page is the user's own. */
class CompositionScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val key = KeyboardCompositionKey("test_ime", LayoutInput.Builtin.QWERTY26)
    private val mine = KeyboardPagePresets.symbolRowsPage(ascii = false).duplicated("my-symbols", "我的符号")

    private fun show(symbolPageId: String) {
        val registry =
            LayoutRegistry
                .create()
                .withPage(mine)
                .withComposition(
                    KeyboardComposition(
                        mainLayoutID = "qwerty26",
                        numericPageID = KeyboardPagePresets.numericKeypad.id,
                        symbolPageID = symbolPageId,
                    ),
                    key.rawValue,
                )
        val viewModel = LayoutLibraryViewModel(InMemoryLayoutLibrarySource(registry))
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                CompositionScreen(key = key, keyboardName = "全拼", onBack = null, viewModel = viewModel)
            }
        }
    }

    @Test
    fun aSavedPageIsOnlyAppliedAfterChoosingANamedKeyboardAndCanBeTried() {
        val source = InMemoryLayoutLibrarySource(LayoutRegistry.create().withPage(mine))
        val model = LayoutLibraryViewModel(source)
        var activated: KeyboardCompositionKey? = null
        var tried = false
        val activation = kotlinx.coroutines.CompletableDeferred<Unit>()
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                app.songyan.ime.settings.ui.screens.UseContentScreen(
                    contentId = mine.id,
                    isPage = true,
                    schemes =
                        listOf(
                            app.songyan.ime.settings.state
                                .InstalledScheme("test_ime", "全拼"),
                        ),
                    sourcesLoaded = true,
                    onActivate = {
                        activated = it
                        activation.await()
                    },
                    onTryOut = { tried = true },
                    onOpenSchemes = {},
                    onBack = {},
                    viewModel = model,
                )
            }
        }
        val target = str(R.string.keyboard_title, "全拼", str(R.string.layout_name_26))
        listOf(R.string.layout_name_14, R.string.layout_name_18, R.string.layout_name_9).forEach { layoutName ->
            compose.onNodeWithText(str(R.string.keyboard_title, "全拼", str(layoutName))).assertDoesNotExist()
        }
        assertNull(activated)
        assertEquals(
            false,
            source.registry.value!!
                .composition(key)
                .symbolPageID == mine.id,
        )
        compose.onNodeWithText(target).performClick()
        assertNull(activated)
        compose.onNodeWithText(str(R.string.content_use_on, target)).performClick()
        compose.waitForIdle()
        assertEquals(key, activated)
        assertEquals(
            mine.id,
            source.registry.value!!
                .composition(key)
                .symbolPageID,
        )
        compose.onNodeWithText(str(R.string.row_tryout_title)).assertDoesNotExist()
        activation.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.row_tryout_title)).performScrollTo().performClick()
        assertEquals(true, tried)
    }

    @Test
    fun thePairingNamesAPhysicalBoardInsteadOfItsShape() {
        val zhuyin = KeyboardCompositionKey("bopomofo_tw", LayoutInput.Builtin.QWERTY26)
        val registry =
            LayoutRegistry
                .create()
                .withComposition(KeyboardComposition(mainLayoutID = "zhuyin41"), zhuyin.rawValue)
        val model = LayoutLibraryViewModel(InMemoryLayoutLibrarySource(registry))
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                CompositionScreen(
                    key = zhuyin,
                    keyboardName = str(R.string.keyboard_title, "注音", str(R.string.layout_name_zhuyin41)),
                    families = LayoutFamilyContext.of("bopomofo_tw" to InputSchemeFamily.ZHUYIN),
                    onBack = null,
                    viewModel = model,
                )
            }
        }
        compose.onNodeWithText(str(R.string.layout_name_zhuyin41)).performScrollTo().assertExists()
        // The stored definition's own Simplified literal is not what the UI shows.
        compose.onNodeWithText(BuiltinLayouts.zhuyin41.name).assertDoesNotExist()
    }

    @Test
    fun aUseTargetNamesThePhysicalBoardItWouldDraw() {
        val zhuyin = KeyboardCompositionKey("bopomofo_tw", LayoutInput.Builtin.QWERTY26)
        val registry =
            LayoutRegistry
                .create()
                .withComposition(KeyboardComposition(mainLayoutID = "zhuyin41"), zhuyin.rawValue)
        val model = LayoutLibraryViewModel(InMemoryLayoutLibrarySource(registry))
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                app.songyan.ime.settings.ui.screens.UseContentScreen(
                    contentId = BuiltinLayouts.zhuyin41.id,
                    isPage = false,
                    schemes = listOf(InstalledScheme("bopomofo_tw", "注音", family = InputSchemeFamily.ZHUYIN)),
                    sourcesLoaded = true,
                    onActivate = {},
                    onTryOut = {},
                    onOpenSchemes = {},
                    onBack = {},
                    viewModel = model,
                )
            }
        }
        val target = str(R.string.keyboard_title, "注音", str(R.string.layout_name_zhuyin41))
        compose.onNodeWithText(target).performScrollTo().assertExists()
        compose.onNodeWithText(str(R.string.keyboard_title, "注音", str(R.string.layout_name_26))).assertDoesNotExist()
    }

    private val keypadMisses =
        listOf(
            "-",
            "/",
            "：",
            "；",
            "（",
            "）",
            "¥",
            "@",
            "“",
            "”",
            "。",
            "、",
            "…",
            "%",
            ":",
            ";",
            "(",
            ")",
            "$",
            "&",
            "\"",
            "'",
            "#",
            "+",
        )

    @Test
    fun aUsersSymbolPagePairedWithTheKeypadNamesTheMarksItLacks() {
        show(mine.id)
        compose
            .onNodeWithText(str(R.string.composition_missing_marks, keypadMisses.joinToString(" ")))
            .performScrollTo()
            .assertExists()
    }

    @Test
    fun aBuiltInSymbolPageNamesNothing() {
        show(KeyboardPagePresets.symbolsSystem.id)
        compose.waitForIdle()
        compose
            .onNodeWithText(str(R.string.composition_missing_marks, keypadMisses.joinToString(" ")))
            .assertDoesNotExist()
    }
}
