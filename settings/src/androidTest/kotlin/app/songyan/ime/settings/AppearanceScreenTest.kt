package app.songyan.ime.settings

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import app.songyan.ime.settings.state.AppearanceOption
import app.songyan.ime.settings.state.AppearanceSettingsSource
import app.songyan.ime.settings.state.AppearanceSettingsState
import app.songyan.ime.settings.ui.AppearanceViewModel
import app.songyan.ime.settings.ui.screens.AppearanceScreen
import app.songyan.ime.settings.ui.theme.SongyanTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Wallpaper colours are one entry of the colour list, and the chosen entry is the only one marked. */
class AppearanceScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private class Source(
        initial: AppearanceSettingsState,
    ) : AppearanceSettingsSource {
        val mutable = MutableStateFlow(initial)
        override val state: StateFlow<AppearanceSettingsState> = mutable.asStateFlow()
        var selected: String? = null
        var dynamic: Boolean? = null

        override fun refresh() = Unit

        override suspend fun select(id: String): Boolean {
            selected = id
            // The real bridge also turns the wallpaper palette off.
            mutable.value = mutable.value.copy(inUseId = id, dynamicColor = false)
            return true
        }

        override suspend fun setDynamicColor(enabled: Boolean): Boolean {
            dynamic = enabled
            mutable.value = mutable.value.copy(dynamicColor = enabled)
            return true
        }
    }

    private fun state(dynamic: Boolean) =
        AppearanceSettingsState(
            appearances = listOf(AppearanceOption("builtin-songyan"), AppearanceOption("builtin-ink")),
            inUseId = "builtin-songyan",
            dynamicColor = dynamic,
            dynamicColorAvailable = true,
            available = true,
        )

    private var candidateBarOpened = 0

    private fun show(source: Source) {
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                AppearanceScreen(
                    onBack = {},
                    onOpenCandidateBar = { candidateBarOpened++ },
                    viewModel =
                        viewModel(
                            factory =
                                object : ViewModelProvider.Factory {
                                    @Suppress("UNCHECKED_CAST")
                                    override fun <T : ViewModel> create(
                                        modelClass: Class<T>,
                                        extras: CreationExtras,
                                    ): T = AppearanceViewModel(source) as T
                                },
                        ),
                )
            }
        }
    }

    @Test
    fun theCandidateBarStyleOpensFromTheLookAndItsTextSizeLivesThere() {
        show(Source(state(dynamic = false)))
        compose.onNodeWithText(str(R.string.candidate_bar_title)).performScrollTo().performClick()
        assertEquals(1, candidateBarOpened)
        compose.onNodeWithText(str(R.string.options_candidate_size_section)).assertDoesNotExist()
    }

    @Test
    fun theWebEditorIsOfferedNextToTheFileImport() {
        show(Source(state(dynamic = false)))
        compose.onNodeWithText(str(R.string.appearance_web_editor_title)).performScrollTo().assertExists()
    }

    @Test
    fun theWallpaperEntryIsInTheColourListAndThereIsNoSeparateSwitch() {
        show(Source(state(dynamic = false)))
        compose.onNodeWithText(str(R.string.appearance_dynamic_title)).assertExists()
        // The old explanation of how the switch and the list interact is gone.
        assertEquals(
            0,
            compose
                .onAllNodes(
                    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch),
                ).fetchSemanticsNodes()
                .size,
        )
    }

    @Test
    fun exactlyOneColourEntryIsMarkedAndItFollowsTheChoice() {
        val source = Source(state(dynamic = true))
        show(source)
        compose.onNode(hasClickAction() and hasText(str(R.string.appearance_dynamic_title))).assertIsSelected()
        compose.onNodeWithText(str(R.string.appearance_name_ink)).performClick()
        compose.waitForIdle()
        assertEquals("builtin-ink", source.selected)
        compose.onNode(hasClickAction() and hasText(str(R.string.appearance_name_ink))).assertIsSelected()
        compose.onAllNodesWithTextCount(str(R.string.appearance_dynamic_title), expected = 1)
        val markedRows =
            compose
                .onAllNodes(
                    androidx.compose.ui.test.SemanticsMatcher.expectValue(
                        androidx.compose.ui.semantics.SemanticsProperties.Selected,
                        true,
                    ),
                ).fetchSemanticsNodes()
                .size
        // One theme-mode entry and one colour entry.
        assertEquals(2, markedRows)
    }

    @Test
    fun pickingTheWallpaperEntryTurnsTheWallpaperPaletteOn() {
        val source = Source(state(dynamic = false))
        show(source)
        compose.onNodeWithText(str(R.string.appearance_dynamic_title)).performClick()
        compose.waitForIdle()
        assertEquals(true, source.dynamic)
        compose.onNode(hasClickAction() and hasText(str(R.string.appearance_dynamic_title))).assertIsSelected()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(
        text: String,
        expected: Int,
    ) {
        assertEquals(expected, onAllNodes(hasText(text)).fetchSemanticsNodes().size)
    }
}
