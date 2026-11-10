package app.songyan.ime.settings

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.songyan.ime.settings.state.CurrentMainLayout
import app.songyan.ime.settings.state.InMemorySettingsPreferences
import app.songyan.ime.settings.state.InstalledScheme
import app.songyan.ime.settings.state.LayoutDisplayName
import app.songyan.ime.settings.state.StoredSourceInfo
import app.songyan.ime.settings.state.StoredSourceKind
import app.songyan.ime.settings.ui.screens.KeyboardMenuScreen
import app.songyan.ime.settings.ui.theme.SongyanTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The keyboard menu page: every package's input methods under its name, the running one first, then
 * the layouts of the input method in use, worded as the menu words them.
 */
class KeyboardMenuScreenTest {
    @get:Rule val compose = createComposeRule()
    private val preferences = InMemorySettingsPreferences()

    private val other =
        StoredSourceInfo(
            id = "scheme-kana",
            name = "Kana Pack",
            kind = StoredSourceKind.SCHEME,
            inUse = false,
            schemes = listOf(InstalledScheme("kana", "Kana"), InstalledScheme("romaji", "Romaji")),
        )

    private fun show() {
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                KeyboardMenuScreen(
                    schemes = listOf(InstalledScheme("quanpin", "Quanpin")),
                    inUseSchemeId = "quanpin",
                    layoutId = "qwerty26",
                    onBack = {},
                    preferences = preferences,
                    otherSources = listOf(other),
                )
            }
        }
    }

    private fun hidden() = runBlocking { preferences.menuHiddenSchemas.first() }

    private fun hiddenLayouts() = runBlocking { preferences.menuHiddenLayouts.first() }

    /** カギロイ in use on its romaji board: the layouts are its two boards, not the pinyin shapes. */
    private fun showJapanese() {
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                KeyboardMenuScreen(
                    schemes = listOf(InstalledScheme("kagiroi", "カギロイ")),
                    inUseSchemeId = "kagiroi",
                    layoutId = "qwerty26",
                    onBack = {},
                    preferences = preferences,
                    sourceName = "カギロイ Pack",
                    mainLayout = CurrentMainLayout("romaji26", LayoutDisplayName.Worded(R.string.layout_name_romaji26)),
                    supportedLayouts = setOf("qwerty26"),
                    boards = listOf("romaji26", "kana12"),
                )
            }
        }
    }

    @Test fun theLayoutsAreTheBoardsOfTheInputMethodInUse() {
        showJapanese()
        compose.onNodeWithText("カギロイ Pack").assertExists()
        compose.onNodeWithText("Keyboard layouts for カギロイ").performScrollTo()
        compose.onNodeWithText("Romaji 26 keys").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Kana 12 keys").performScrollTo().assertIsOn()
        for (shape in listOf("26-key", "14-key", "18-key", "9-key")) {
            assertTrue(shape, compose.onAllNodesWithText(shape).fetchSemanticsNodes().isEmpty())
        }
    }

    @Test fun hidingABoardStoresItsOwnId() {
        showJapanese()
        compose.onNodeWithText("Kana 12 keys").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(setOf("kana12"), hiddenLayouts())
    }

    @Test fun anotherPackageIsListedUnderItsNameWithSwitchesOn() {
        show()
        compose.onNodeWithText("Kana Pack").performScrollTo()
        compose.onNodeWithText("Kana").performScrollTo().assertIsOn()
        compose.onNodeWithText("Romaji").performScrollTo().assertIsOn()
    }

    @Test fun hidingAnInputMethodOfAnotherPackageStoresItsPlainId() {
        show()
        compose.onNodeWithText("Romaji").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(setOf("romaji"), hidden())
    }
}
