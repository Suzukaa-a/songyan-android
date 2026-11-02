package app.songyan.ime.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import app.songyan.ime.layout.KeyboardPageCategory
import app.songyan.ime.layout.KeyboardPagePresets
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutRegistry
import app.songyan.ime.settings.state.InMemoryLayoutLibrarySource
import app.songyan.ime.settings.ui.PageEditorViewModel
import app.songyan.ime.settings.ui.screens.PageEditorScreen
import app.songyan.ime.settings.ui.theme.SongyanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The page editor edits the categories of a categorized symbol page: tabs, their content and their keys. */
class PageEditorScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun literal(text: String) = LayoutKey(text, action = LayoutAction.literal(text))

    private val page =
        KeyboardPagePresets.symbolsCategorized.copy(
            id = "my-symbols",
            name = "我的符号",
            categories =
                listOf(
                    KeyboardPageCategory("one", "甲组", columns = 2, keys = listOf(literal("甲"), literal("乙"))),
                    KeyboardPageCategory("two", "乙组", keys = listOf(literal("丙"))),
                ),
        )

    private val source = InMemoryLayoutLibrarySource(LayoutRegistry.create().withPage(page))
    private var closed = false

    private fun show() {
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                PageEditorScreen(
                    pageId = page.id,
                    onClose = { closed = true },
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

    private fun tab(
        index: Int,
        name: String,
    ) = compose.onNodeWithContentDescription(str(R.string.page_editor_category_description, index, name))

    private fun stored() = source.registry.value!!.page(page.id)!!

    @Test
    fun theCategoriesAreTabsAndTheOpenOneShowsItsKeys() {
        show()
        tab(1, "甲组").performScrollTo().assertIsDisplayed()
        tab(2, "乙组").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(str(R.string.layout_editor_key_description, 1, 1, "甲")).assertIsDisplayed()
        tab(2, "乙组").performClick()
        compose.onNodeWithContentDescription(str(R.string.layout_editor_key_description, 1, 1, "丙")).assertIsDisplayed()
    }

    @Test
    fun aCategoryIsAddedRenamedAndSaved() {
        show()
        compose.onNodeWithContentDescription(str(R.string.page_editor_category_add)).performScrollTo().performClick()
        compose
            .onAllNodes(hasSetTextAction() and hasText(str(R.string.page_editor_category_new)))
            .onFirst()
            .performTextClearance()
        compose.onAllNodes(hasSetTextAction() and hasText("")).onFirst().performTextInput("新分类")
        compose.onNodeWithText(str(R.string.layout_editor_save)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("甲组", "乙组", "新分类"), stored().categories!!.map { it.name })
        assertEquals("new-id", stored().categories!!.last().id)
        assertEquals(true, closed)
    }

    @Test
    fun aCategoryKeyTakesNewTextAndColumnsChange() {
        show()
        compose
            .onNodeWithContentDescription(
                str(R.string.page_editor_category_more_columns),
            ).performScrollTo()
            .performClick()
        compose.onNodeWithContentDescription(str(R.string.layout_editor_key_description, 1, 1, "甲")).performClick()
        compose
            .onAllNodes(hasSetTextAction() and hasText("甲"))
            .onFirst()
            .performTextClearance()
        compose.onAllNodes(hasSetTextAction() and hasText("")).onFirst().performTextInput("壹")
        compose.onNodeWithText(str(R.string.layout_editor_save)).performScrollTo().performClick()
        compose.waitForIdle()
        val first = stored().categories!!.first()
        assertEquals(3, first.columns)
        assertEquals("壹", first.keys.first().label)
    }

    @Test
    fun theRecentContentKeepsTheKeysTheCategoryHad() {
        show()
        compose.onNodeWithText(str(R.string.page_editor_category_recent)).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.page_editor_category_recent_body)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(str(R.string.layout_editor_save)).performScrollTo().performClick()
        compose.waitForIdle()
        val first = stored().categories!!.first()
        assertEquals(KeyboardPageCategory.Source.RECENT, first.source)
        assertEquals(listOf("甲", "乙"), first.keys.map { it.label })
    }

    @Test
    fun theLastCategoryCannotBeRemoved() {
        show()
        compose.onNodeWithContentDescription(str(R.string.page_editor_category_remove)).performScrollTo().performClick()
        compose.onNodeWithContentDescription(str(R.string.page_editor_category_remove)).performScrollTo().performClick()
        compose.waitForIdle()
        tab(1, "乙组").assertIsDisplayed()
        compose.onNodeWithContentDescription(str(R.string.page_editor_category_remove)).assertIsNotEnabled()
    }
}
