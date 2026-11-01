package app.songyan.ime.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import app.songyan.ime.layout.KeyboardComposition
import app.songyan.ime.layout.KeyboardCompositionKey
import app.songyan.ime.layout.KeyboardPagePresets
import app.songyan.ime.layout.LayoutInput
import app.songyan.ime.layout.LayoutRegistry
import app.songyan.ime.settings.state.InMemoryLayoutLibrarySource
import app.songyan.ime.settings.ui.LayoutLibraryViewModel
import app.songyan.ime.settings.ui.screens.CompositionScreen
import app.songyan.ime.settings.ui.theme.SongyanTheme
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
