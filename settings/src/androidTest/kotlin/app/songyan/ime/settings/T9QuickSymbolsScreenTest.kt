package app.songyan.ime.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import app.songyan.ime.settings.state.InMemorySettingsPreferences
import app.songyan.ime.settings.ui.T9QuickSymbolsViewModel
import app.songyan.ime.settings.ui.screens.T9QuickSymbolsScreen
import app.songyan.ime.settings.ui.theme.SongyanTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The 九键快捷符号 editor: same validation as the store, a live preview, and a save the keyboard reads. */
class T9QuickSymbolsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val preferences = InMemorySettingsPreferences()

    private fun show() {
        val model = T9QuickSymbolsViewModel(preferences)
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                T9QuickSymbolsScreen(onBack = null, viewModel = model)
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun stored() = runBlocking { preferences.t9QuickSymbols.first() }

    private fun field() = compose.onNode(hasSetTextAction())

    @Test
    fun theFieldStartsFromTheDefaultSequenceAndNothingIsToSave() {
        show()
        field().assertTextContains("，。？！、…")
        compose.onNodeWithText(str(R.string.t9_symbols_save)).assertIsNotEnabled()
    }

    @Test
    fun aRepeatedSymbolIsRefusedWithAShortReasonAndCannotBeSaved() {
        show()
        field().performTextClearance()
        field().performTextInput("；；")
        compose.onNodeWithText(str(R.string.t9_symbols_error_duplicate)).assertExists()
        compose.onNodeWithText(str(R.string.t9_symbols_save)).assertIsNotEnabled()
    }

    @Test
    fun anEmptyListIsRefused() {
        show()
        field().performTextClearance()
        compose.onNodeWithText(str(R.string.t9_symbols_error_invalid, 12)).assertExists()
        compose.onNodeWithText(str(R.string.t9_symbols_save)).assertIsNotEnabled()
    }

    @Test
    fun aValidListShowsItsPreviewIsSavedAndCanBeRestored() {
        show()
        field().performTextClearance()
        field().performTextInput("；：")
        compose.onNodeWithText("； ： ，").assertExists()
        compose.onNodeWithText(str(R.string.t9_symbols_save)).assertIsEnabled().performClick()
        compose.waitUntil(5_000) { stored() == "；：" }

        compose.onNodeWithText(str(R.string.t9_symbols_restore)).performClick()
        compose.waitUntil(5_000) { stored().isEmpty() }
        field().assertTextContains("，。？！、…")
        assertEquals("", stored())
    }
}
