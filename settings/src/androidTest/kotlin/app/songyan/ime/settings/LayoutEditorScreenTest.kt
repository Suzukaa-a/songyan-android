package app.songyan.ime.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
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
import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.LayoutAuxiliaryArea
import app.songyan.ime.layout.LayoutRegistry
import app.songyan.ime.layout.PhoneKeypadRailPolicy
import app.songyan.ime.settings.state.InMemoryLayoutLibrarySource
import app.songyan.ime.settings.ui.LayoutEditorViewModel
import app.songyan.ime.settings.ui.screens.LayoutEditorScreen
import app.songyan.ime.settings.ui.theme.SongyanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The layout editor changes a layout key by key and refuses a layout the keyboard could not draw. */
class LayoutEditorScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val source = InMemoryLayoutLibrarySource(LayoutRegistry.create())
    private var closed = false

    private fun show(layoutId: String = "qwerty26") {
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                LayoutEditorScreen(
                    layoutId = layoutId,
                    onClose = { closed = true },
                    viewModel =
                        viewModel(
                            factory =
                                object : ViewModelProvider.Factory {
                                    @Suppress("UNCHECKED_CAST")
                                    override fun <T : ViewModel> create(
                                        modelClass: Class<T>,
                                        extras: CreationExtras,
                                    ): T = LayoutEditorViewModel(source, layoutId, newId = { "copy-id" }) as T
                                },
                        ),
                )
            }
        }
    }

    private fun keyNode(
        row: Int,
        key: Int,
        label: String,
    ) = compose.onNodeWithContentDescription(str(R.string.layout_editor_key_description, row, key, label))

    @Test
    fun theGridShowsTheLetterRowsAndTheControlRowStaysOut() {
        show()
        keyNode(1, 1, "q").assertIsDisplayed()
        keyNode(1, 2, "w").assertIsDisplayed()
        compose.onNodeWithText(str(R.string.layout_editor_control_row_note)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun anEditedTitleIsSavedIntoTheCatalogAndTheEditorCloses() {
        show()
        keyNode(1, 1, "q").performClick()
        compose
            .onAllNodes(
                hasSetTextAction() and
                    androidx.compose.ui.test
                        .hasText("q"),
            ).onFirst()
            .performTextClearance()
        compose
            .onAllNodes(
                hasSetTextAction() and
                    androidx.compose.ui.test
                        .hasText(""),
            ).onFirst()
            .performTextInput("qq")
        compose.onNodeWithText(str(R.string.layout_editor_save)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(
            "qq",
            source.registry.value!!
                .layout("qwerty26")!!
                .rows[0]
                .keys[0]
                .label,
        )
        assertEquals(true, closed)
    }

    @Test
    fun aKeyWithoutATitleIsRefusedWithTheReasonAndNothingIsStored() {
        show()
        keyNode(1, 1, "q").performClick()
        compose
            .onAllNodes(
                hasSetTextAction() and
                    androidx.compose.ui.test
                        .hasText("q"),
            ).onFirst()
            .performTextClearance()
        compose.onNodeWithText(str(R.string.layout_editor_save)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.layout_error_key_label_width)).assertIsDisplayed()
        assertEquals(BuiltinLayouts.qwerty26, source.registry.value!!.layout("qwerty26"))
        assertEquals(false, closed)
    }

    @Test
    fun saveAsCopyLeavesTheOriginalAndAddsALayout() {
        show()
        compose.onNodeWithText(str(R.string.layout_editor_save_copy)).performScrollTo().performClick()
        compose.waitForIdle()
        val registry = source.registry.value!!
        assertEquals(BuiltinLayouts.qwerty26, registry.layout("qwerty26"))
        assertEquals(BuiltinLayouts.qwerty26.name + str(R.string.layout_copy_suffix), registry.layout("copy-id")!!.name)
    }

    @Test
    fun addingAKeyPutsItAfterTheSelectedOneInTheGrid() {
        show()
        keyNode(1, 1, "q").performClick()
        compose.onNodeWithContentDescription(str(R.string.layout_editor_add_key)).performClick()
        compose.waitForIdle()
        // The new key sits second in the first row, and the old second key moved to third.
        keyNode(1, 2, "·").assertIsDisplayed()
        keyNode(1, 3, "w").assertIsDisplayed()
    }

    @Test
    fun theSplitSettingsOfALayoutAppearOnlyWhenSplittingIsOn() {
        show()
        compose.onAllNodesWithText(str(R.string.layout_editor_split_gap_automatic)).assertCountEquals(0)
        compose.onNodeWithText(str(R.string.layout_editor_split_always)).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.layout_editor_split_gap_automatic)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(str(R.string.layout_editor_split_row, 1, 5)).performScrollTo().assertIsDisplayed()
    }

    // The nine-key layout.

    private fun railNode(
        column: Int,
        index: Int,
        label: String,
    ) = compose.onNodeWithContentDescription(
        str(R.string.layout_editor_rail_key_description, str(column), index, label),
    )

    @Test
    fun theNineKeyGridIsFixedAndTheBottomRowIsExplainedNotEdited() {
        show("phone9")
        keyNode(1, 2, "ABC").assertIsDisplayed()
        keyNode(3, 3, "WXYZ").assertIsDisplayed()
        // Only the 3x3 grid is shown: the declared control row (布局, 空格, 🌐) is not a row of it.
        compose
            .onAllNodesWithContentDescription(
                str(R.string.layout_editor_key_description, 4, 1, "布局"),
            ).assertCountEquals(0)
        compose.onNodeWithText(str(R.string.layout_editor_control_row_note)).performScrollTo().assertIsDisplayed()
        // The structure is not editable: nothing to add, move or split.
        compose.onAllNodesWithContentDescription(str(R.string.layout_editor_add_key)).assertCountEquals(0)
        compose.onAllNodesWithText(str(R.string.layout_editor_add_row)).assertCountEquals(0)
        compose.onAllNodesWithText(str(R.string.layout_editor_split_section)).assertCountEquals(0)
    }

    @Test
    fun aNineKeyKeyIsEditedAndTheControlRowComesBackUnchanged() {
        show("phone9")
        keyNode(2, 2, "JKL").performClick()
        compose
            .onAllNodes(
                hasSetTextAction() and
                    androidx.compose.ui.test
                        .hasText("JKL"),
            ).onFirst()
            .performTextClearance()
        compose
            .onAllNodes(
                hasSetTextAction() and
                    androidx.compose.ui.test
                        .hasText(""),
            ).onFirst()
            .performTextInput("我的")
        compose.onNodeWithText(str(R.string.layout_editor_save)).performScrollTo().performClick()
        compose.waitForIdle()
        val stored = source.registry.value!!.layout("phone9")!!
        assertEquals("我的", stored.rows[1].keys[1].label)
        assertEquals(BuiltinLayouts.phone9.rows.last(), stored.rows.last())
        assertEquals(BuiltinLayouts.phone9.auxiliaryAreas, stored.auxiliaryAreas)
    }

    @Test
    fun theSideColumnsAreTheKeyboardsOwnUntilTheUserDeclaresThemAndThenEveryKeyEdits() {
        show("phone9")
        compose.onNodeWithText(str(R.string.layout_editor_rails_bundled_body)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(str(R.string.layout_editor_rails_customize)).performScrollTo().performClick()
        railNode(R.string.layout_editor_rail_leading, 1, "，").performScrollTo().assertIsDisplayed()
        railNode(R.string.layout_editor_rail_trailing, 2, "重输").performScrollTo().assertIsDisplayed()
        // Add a key to the left column and save: the layout stores four declared keys there.
        compose
            .onNodeWithContentDescription(str(R.string.layout_editor_rail_add_leading))
            .performScrollTo()
            .performClick()
        compose.onNodeWithText(str(R.string.layout_editor_save)).performScrollTo().performClick()
        compose.waitForIdle()
        val stored = source.registry.value!!.layout("phone9")!!
        val leading =
            stored.auxiliaryAreas!!
                .filter { it.position == LayoutAuxiliaryArea.Position.LEADING && it.keys.isNotEmpty() }
                .flatMap { it.keys }
        assertEquals(listOf("，", "。", "？", "·"), leading.map { it.label })
        assertEquals(true, closed)
    }

    @Test
    fun aNineKeyLayoutSavesAsACopyWithItsDeclaredSideColumns() {
        show("phone9")
        compose.onNodeWithText(str(R.string.layout_editor_rails_customize)).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.layout_editor_save_copy)).performScrollTo().performClick()
        compose.waitForIdle()
        val registry = source.registry.value!!
        assertEquals(BuiltinLayouts.phone9, registry.layout("phone9") ?: BuiltinLayouts.phone9)
        val copy = registry.layout("copy-id")!!
        assertEquals(false, PhoneKeypadRailPolicy.usesBundledRails(copy))
        assertEquals(BuiltinLayouts.phone9.rows, copy.rows)
    }
}
