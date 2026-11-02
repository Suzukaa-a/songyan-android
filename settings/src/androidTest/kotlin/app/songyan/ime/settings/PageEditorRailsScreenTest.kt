package app.songyan.ime.settings

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import app.songyan.ime.layout.KeyboardPagePresets
import app.songyan.ime.layout.LayoutInput
import app.songyan.ime.layout.LayoutPageJump
import app.songyan.ime.layout.LayoutPageTarget
import app.songyan.ime.layout.LayoutRegistry
import app.songyan.ime.settings.state.InMemoryLayoutLibrarySource
import app.songyan.ime.settings.ui.PageEditorViewModel
import app.songyan.ime.settings.ui.screens.PageEditorScreen
import app.songyan.ime.settings.ui.theme.SongyanTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The page editor adds keys to the side columns of a row page, sets their width and saves them. */
class PageEditorRailsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val page = KeyboardPagePresets.numericAutomatic.duplicated("my-digits", "我的数字")
    private val source = InMemoryLayoutLibrarySource(LayoutRegistry.create().withPage(page))

    private fun show() {
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                PageEditorScreen(
                    pageId = page.id,
                    onClose = {},
                    viewModel =
                        viewModel(
                            factory =
                                object : ViewModelProvider.Factory {
                                    @Suppress("UNCHECKED_CAST")
                                    override fun <T : ViewModel> create(
                                        modelClass: Class<T>,
                                        extras: CreationExtras,
                                    ): T = PageEditorViewModel(source, page.id, newId = { "new-id" }) as T
                                },
                        ),
                )
            }
        }
    }

    @Test
    fun aBottomKeyCanBeEditedAndSavedWithThePage() {
        show()
        val index = page.chrome!!.bottom.indexOfFirst { it.label == "空格" }
        compose
            .onNodeWithContentDescription(str(R.string.layout_editor_key_description, 1, index + 1, "空格"))
            .performScrollTo()
            .performClick()
        compose.onAllNodes(hasSetTextAction() and hasText("空格")).onFirst().performTextReplacement("空格键")
        compose.onNodeWithText(str(R.string.layout_editor_save)).performClick()
        compose.waitForIdle()
        assertEquals(
            "空格键",
            source.registry.value!!
                .page(page.id)!!
                .chrome!!
                .bottom[index]
                .label,
        )
    }

    @Test
    fun aKeyAddedToTheRightColumnIsSavedWithItsWidthAndTheFooterIsKept() {
        show()
        compose
            .onNodeWithContentDescription(str(R.string.layout_editor_rail_add_trailing))
            .performScrollTo()
            .performClick()
        compose
            .onNodeWithText(str(R.string.page_editor_rail_width_trailing))
            .performScrollTo()
        compose
            .onAllNodes(hasSetTextAction() and hasText(str(R.string.page_editor_rail_width_trailing)))
            .onFirst()
            .performTextInput("56")
        compose.onNodeWithText(str(R.string.layout_editor_save)).performClick()
        compose.waitForIdle()
        val chrome =
            source.registry.value!!
                .page(page.id)!!
                .chrome!!
        assertEquals(1, chrome.trailing.size)
        assertEquals(56.0, chrome.trailingWidth!!, 0.0)
        assertTrue(chrome.leading.isEmpty())
        assertEquals(page.chrome!!.bottom, chrome.bottom)
    }

    @Test
    fun aKeyMadeToJumpTo9Key123IsNamedAfterThePageAndSaved() {
        show()
        compose
            .onNodeWithContentDescription(str(R.string.layout_editor_key_description, 1, 1, "1"))
            .performScrollTo()
            .performClick()
        compose
            .onAllNodes(hasText(str(R.string.layout_action_literal)))
            .onFirst()
            .performScrollTo()
            .performClick()
        compose.onNodeWithText(str(R.string.layout_action_jump)).performClick()
        val target = str(R.string.layout_jump_target, str(R.string.layout_name_9), str(R.string.layout_page_numeric))
        val first = str(R.string.layout_jump_target, str(R.string.layout_name_26), str(R.string.layout_page_numeric))
        compose.onNodeWithText(first).performScrollTo().performClick()
        compose.onNodeWithText(target).performClick()
        compose.onNodeWithText(target).performScrollTo()
        compose.onNodeWithText(str(R.string.layout_editor_save)).performClick()
        compose.waitForIdle()
        val key =
            source.registry.value!!
                .page(page.id)!!
                .rows[0]
                .keys[0]
        assertEquals(LayoutPageJump(LayoutInput.Builtin.PHONE9, LayoutPageTarget.NUMERIC), key.action.layoutPageJump)
        assertEquals(str(R.string.layout_page_numeric), key.label)
    }
}
