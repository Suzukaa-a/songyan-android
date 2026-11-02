package app.songyan.ime.settings

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import app.songyan.ime.settings.state.InMemoryLayoutLibrarySource
import app.songyan.ime.settings.state.InMemorySettingsPreferences
import app.songyan.ime.settings.ui.LayoutEditorViewModel
import app.songyan.ime.settings.ui.screens.LayoutEditorScreen
import app.songyan.ime.settings.ui.theme.SongyanTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Quick symbols use the nine-key editor's layout draft and save, never a second global write. */
class T9QuickSymbolsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val preferences = InMemorySettingsPreferences()
    private val source = InMemoryLayoutLibrarySource()

    private fun show() {
        val model = LayoutEditorViewModel(source, "phone9", preferences = preferences)
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                LayoutEditorScreen(layoutId = "phone9", onClose = {}, viewModel = model)
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun field() = compose.onNode(hasSetTextAction() and hasText(str(R.string.t9_symbols_field)))

    @Test fun theFieldShowsItsEffectiveDefaultPreviewAndNothingIsToSave() {
        show()
        compose.onNodeWithText("， 。 ？").assertExists()
        compose.onNodeWithText(str(R.string.layout_editor_save)).assertIsNotEnabled()
    }

    @Test fun aRepeatedSymbolIsRefusedAndTheSavedLayoutStaysUnchanged() {
        show()
        field().performTextReplacement("；；")
        compose.onNodeWithText(str(R.string.t9_symbols_error_duplicate)).assertExists()
        compose.onNodeWithText(str(R.string.layout_editor_save)).performClick()
        compose.waitForIdle()
        assertEquals(
            null,
            source.registry.value!!
                .layout("phone9")!!
                .t9QuickSymbols,
        )
    }

    @Test fun anEmptyListOverridesTheInheritedOrderWithBuiltinDefaultsOnlyAfterSaving() {
        runBlocking { preferences.setT9QuickSymbols("；") }
        show()
        field().performTextReplacement("")
        compose.onNodeWithText("， 。 ？").assertExists()
        assertEquals(
            null,
            source.registry.value!!
                .layout("phone9")!!
                .t9QuickSymbols,
        )
        compose.onNodeWithText(str(R.string.layout_editor_save)).performClick()
        compose.waitForIdle()
        assertEquals(
            "",
            source.registry.value!!
                .layout("phone9")!!
                .t9QuickSymbols,
        )
        assertEquals("；", runBlocking { preferences.t9QuickSymbols.first() })
    }

    @Test fun theFirstKeyShowsItsLiveSemanticsAndCustomKeepsAnOrdinaryComma() {
        show()
        compose.onNodeWithText(str(R.string.t9_first_quick)).performScrollTo().assertIsSelected()
        compose.onNodeWithText(str(R.string.t9_first_quick_note)).assertExists()
        compose.onNodeWithText(str(R.string.t9_first_custom)).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.t9_first_custom)).assertIsSelected()
        compose.onNodeWithText(str(R.string.layout_editor_save)).performClick()
        compose.waitForIdle()
        val saved = source.registry.value!!.layout("phone9")!!
        assertEquals(app.songyan.ime.layout.T9FirstKeyBehavior.CUSTOM, saved.t9FirstKeyBehavior)
        assertEquals(
            "，",
            saved.rows[0]
                .keys[0]
                .action.value,
        )
    }
}
