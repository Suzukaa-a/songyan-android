package app.songyan.ime.settings

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.songyan.ime.candidate.ToolbarItem
import app.songyan.ime.candidate.ToolbarLayout
import app.songyan.ime.settings.state.InMemorySettingsPreferences
import app.songyan.ime.settings.ui.screens.TOOLBAR_RESET_TAG
import app.songyan.ime.settings.ui.screens.ToolbarScreen
import app.songyan.ime.settings.ui.screens.toolbarMenuTag
import app.songyan.ime.settings.ui.screens.toolbarRowTag
import app.songyan.ime.settings.ui.screens.toolbarSwitchTag
import app.songyan.ime.settings.ui.theme.SongyanTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The toolbar page: switches, order, the restore action and the width limit shown for this window. */
class ToolbarScreenTest {
    @get:Rule val compose = createComposeRule()
    private val preferences = InMemorySettingsPreferences()

    private fun show(widthDp: Float = 360f) {
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                ToolbarScreen(onBack = {}, preferences = preferences, widthDp = widthDp)
            }
        }
    }

    private fun stored() = ToolbarLayout.parse(runBlocking { preferences.toolbarLayout.first() })

    @Test fun theDefaultShowsEverythingOnAndTheLimitOfThisWidth() {
        show(360f)
        compose.onNodeWithTag("toolbar-limit").assertTextEquals(str(R.string.toolbar_limit, 7))
        for (item in ToolbarItem.entries) {
            compose.onNodeWithTag(toolbarSwitchTag(item)).performScrollTo().assertIsOn()
        }
        compose.onNodeWithTag(TOOLBAR_RESET_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test fun aSwitchTurnsAnItemOffAndOnAndTheChoiceIsStored() {
        show()
        compose.onNodeWithTag(toolbarSwitchTag(ToolbarItem.EMOJI)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(toolbarSwitchTag(ToolbarItem.EMOJI)).assertIsOff()
        assertEquals(false, ToolbarItem.EMOJI in stored().shown)
        compose.onNodeWithTag(toolbarSwitchTag(ToolbarItem.EMOJI)).performClick()
        compose.waitForIdle()
        assertEquals("back to the default stores nothing", "", runBlocking { preferences.toolbarLayout.first() })
    }

    @Test fun everyItemCanBeTurnedOff() {
        show()
        for (item in ToolbarItem.entries) {
            compose.onNodeWithTag(toolbarSwitchTag(item)).performScrollTo().performClick()
            compose.waitForIdle()
        }
        assertEquals(emptyList<ToolbarItem>(), stored().shown)
    }

    @Test fun moveUpAndMoveDownReorderTheItems() {
        show()
        compose.onNodeWithTag(toolbarMenuTag(ToolbarItem.EDIT)).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.toolbar_move_up)).performClick()
        compose.waitForIdle()
        assertEquals(ToolbarItem.EDIT, stored().entries[2].item)
        compose.onNodeWithTag(toolbarMenuTag(ToolbarItem.EDIT)).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.toolbar_move_down)).performClick()
        compose.waitForIdle()
        assertEquals(ToolbarLayout.DEFAULT, stored())
    }

    @Test fun theFirstItemCannotMoveUp() {
        show()
        compose.onNodeWithTag(toolbarMenuTag(ToolbarItem.CLIPBOARD)).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.toolbar_move_up)).assertIsNotEnabled()
    }

    @Test fun restoreDefaultsPutsTheOrderAndSwitchesBack() {
        show()
        compose.onNodeWithTag(toolbarSwitchTag(ToolbarItem.CLIPBOARD)).performScrollTo().performClick()
        compose.onNodeWithTag(toolbarMenuTag(ToolbarItem.SETTINGS)).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.toolbar_move_up)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(TOOLBAR_RESET_TAG).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(ToolbarLayout.DEFAULT, stored())
        compose.onNodeWithTag(toolbarSwitchTag(ToolbarItem.CLIPBOARD)).assertIsOn()
    }

    @Test fun itemsPastTheLimitAreMarkedNotShown() {
        // 132 dp holds three slots, one of them the hide button: two items fit.
        show(132f)
        compose.onNodeWithTag("toolbar-limit").assertTextEquals(str(R.string.toolbar_limit, 2))
        val marked = hasText(str(R.string.toolbar_over_width))
        compose.onNodeWithTag(toolbarRowTag(ToolbarItem.CLIPBOARD)).assert(!marked)
        compose.onNodeWithTag(toolbarRowTag(ToolbarItem.INPUT_METHODS)).assert(!marked)
        compose.onNodeWithTag(toolbarRowTag(ToolbarItem.EMOJI)).performScrollTo().assert(marked)
        // An item that is off takes no room and is not marked.
        compose.onNodeWithTag(toolbarSwitchTag(ToolbarItem.CLIPBOARD)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(toolbarRowTag(ToolbarItem.EMOJI)).assert(!marked)
        compose.onNodeWithTag(toolbarRowTag(ToolbarItem.CLIPBOARD)).assert(!marked)
    }
}
