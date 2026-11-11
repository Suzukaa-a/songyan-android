package app.songyan.ime.settings

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
import app.songyan.ime.candidate.CandidateBarDefinition
import app.songyan.ime.candidate.CandidateBarDefinition.Expanded.Mode
import app.songyan.ime.keyboard.support.CandidateTextSize
import app.songyan.ime.settings.state.AppearanceSettingsSource
import app.songyan.ime.settings.state.AppearanceSettingsState
import app.songyan.ime.settings.ui.AppearanceViewModel
import app.songyan.ime.settings.ui.screens.CandidateBarScreen
import app.songyan.ime.settings.ui.theme.SongyanTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The candidate bar page edits the candidate area of the appearance in use, one choice at a time. */
class CandidateBarScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private class Source : AppearanceSettingsSource {
        val mutable = MutableStateFlow(AppearanceSettingsState(available = true))
        override val state: StateFlow<AppearanceSettingsState> = mutable.asStateFlow()
        val edits = mutableListOf<CandidateBarDefinition>()

        override fun refresh() = Unit

        override suspend fun select(id: String) = true

        override suspend fun setDynamicColor(enabled: Boolean) = true

        override suspend fun updateCandidateBar(bar: CandidateBarDefinition): Boolean {
            edits += bar
            mutable.value = mutable.value.copy(candidateBar = bar)
            return true
        }
    }

    private var size = CandidateTextSize.STANDARD

    private fun show(source: Source) {
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                CandidateBarScreen(
                    hideIdleToolbar = false,
                    onHideIdleToolbarChanged = {},
                    candidateTextSize = size,
                    onCandidateTextSizeChanged = { size = it },
                    onBack = {},
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
    fun pickingTheGridModeStoresItAndRevealsTheColumnCount() {
        val source = Source()
        show(source)
        compose
            .onNodeWithText(
                str(R.string.candidate_bar_value, str(R.string.candidate_bar_columns), 2),
            ).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.candidate_bar_mode_grid)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(
            Mode.GRID,
            source.edits
                .last()
                .expanded.mode,
        )
        compose.onNodeWithText(str(R.string.candidate_bar_value, str(R.string.candidate_bar_columns), 2)).assertExists()
        compose.onNode(hasClickAction() and hasText(str(R.string.candidate_bar_mode_grid))).assertIsSelected()
    }

    @Test
    fun theRowHeightIsShownTheWayTheKeyboardDrawsIt() {
        show(Source())
        // The iOS default row (40) is a 48 dp touch target on the keyboard.
        compose
            .onNodeWithText(
                str(R.string.candidate_bar_value, str(R.string.candidate_bar_row_height), 48),
            ).assertExists()
    }

    @Test
    fun cellPaddingAndSpacingAreShownTheWayTheKeyboardDrawsThem() {
        show(Source())
        // The iOS default padding (7) is the keyboard's 10 dp token; the default spacing is none.
        compose
            .onNodeWithText(
                str(R.string.candidate_bar_value, str(R.string.candidate_bar_cell_padding), 10),
            ).assertExists()
        compose
            .onNodeWithText(
                str(R.string.candidate_bar_value, str(R.string.candidate_bar_cell_spacing), 0),
            ).assertExists()
    }

    @Test
    fun theCandidateTextSizeIsChosenWithTheRestOfTheBarStyle() {
        show(Source())
        compose.onNodeWithText(str(R.string.options_candidate_size_large)).performScrollTo().performClick()
        assertEquals(CandidateTextSize.LARGE, size)
    }
}
