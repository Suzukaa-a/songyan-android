package app.songyan.ime.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.songyan.ime.settings.state.CurrentMainLayout
import app.songyan.ime.settings.state.LayoutDisplayName
import app.songyan.ime.settings.ui.screens.KeyboardsScreen
import app.songyan.ime.settings.ui.theme.SongyanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 我的键盘 names the keyboard it actually draws, not the shape it shares with the 26-key board. */
class KeyboardsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(mainLayout: CurrentMainLayout?) {
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                KeyboardsScreen(
                    inputMethodName = "注音",
                    layoutId = "qwerty26",
                    mainLayout = mainLayout,
                    supportedLayouts = setOf("qwerty26"),
                    onSelectLayout = {},
                    onBack = null,
                )
            }
        }
    }

    @Test
    fun aPhysicalBoardNamesTheSelectedRowInsteadOfTheShapeName() {
        show(CurrentMainLayout("zhuyin41", LayoutDisplayName.Worded(R.string.layout_name_zhuyin41)))
        compose.onNodeWithText(str(R.string.layout_name_zhuyin41)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.layout_name_26)).assertDoesNotExist()
    }

    @Test
    fun theShippedShapesKeepTheirOwnNames() {
        show(CurrentMainLayout("qwerty26", LayoutDisplayName.Worded(R.string.layout_name_26)))
        compose.onNodeWithText(str(R.string.layout_name_26)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.layout_name_zhuyin41)).assertDoesNotExist()
    }

    @Test
    fun aJapaneseKeyboardOffersRomajiAndKanaAndNoShapeItCannotUse() {
        var picked: String? = null
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                KeyboardsScreen(
                    inputMethodName = "カギロイ",
                    layoutId = "qwerty26",
                    mainLayout = CurrentMainLayout("romaji26", LayoutDisplayName.Worded(R.string.layout_name_romaji26)),
                    supportedLayouts = setOf("qwerty26"),
                    onSelectLayout = {},
                    boards = listOf("romaji26", "kana12"),
                    onSelectBoard = { picked = it },
                    onBack = null,
                )
            }
        }
        compose.onNodeWithText(str(R.string.layout_name_romaji26)).assertIsSelected()
        // The kana board is one tap away instead of inside the page pairing.
        compose.onNodeWithText(str(R.string.layout_name_kana12)).performClick()
        assertEquals("kana12", picked)
        // Shapes Japanese cannot use are left out, not offered greyed as if they were coming.
        for (shape in listOf(R.string.layout_name_14, R.string.layout_name_18, R.string.layout_name_9)) {
            compose.onNodeWithText(str(shape)).assertDoesNotExist()
        }
        compose.onNodeWithText(str(R.string.layout_unavailable_note)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.layout_name_26)).assertDoesNotExist()
    }
}
