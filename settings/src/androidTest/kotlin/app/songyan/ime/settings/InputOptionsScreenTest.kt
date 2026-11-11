package app.songyan.ime.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.songyan.ime.keyboard.engine.BuiltinScriptState
import app.songyan.ime.keyboard.engine.EngineOptionKind
import app.songyan.ime.keyboard.support.KeyFeedbackSettings
import app.songyan.ime.settings.state.EngineOptionSetting
import app.songyan.ime.settings.ui.EngineOptionsUiState
import app.songyan.ime.settings.ui.screens.InputOptionsScreen
import app.songyan.ime.settings.ui.screens.KeyboardFeelState
import app.songyan.ime.settings.ui.screens.SystemFeedbackState
import app.songyan.ime.settings.ui.theme.SongyanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Candidate text size and own key sound / vibration controls that follow the system by default. */
class InputOptionsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var feedback = KeyFeedbackSettings()
    private var placement = "row"
    private var longPress: Int? = null
    private var bottomGap = -1
    private var splitWidth = -1
    private var sideMargin = -1
    private var feel = KeyboardFeelState()
    private var engineOptions = EngineOptionsUiState()
    private val engineChanges = mutableListOf<Pair<String, Boolean>>()
    private var doubleSpace: Boolean? = null

    private fun show(
        stored: KeyFeedbackSettings = KeyFeedbackSettings(),
        system: SystemFeedbackState = SystemFeedbackState(soundEnabled = true, hapticsEnabled = true),
    ) {
        feedback = stored
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                InputOptionsScreen(
                    keyFeedback = feedback,
                    onKeyFeedbackChanged = { feedback = it },
                    keyboardFeel = feel.copy(readingPlacement = placement),
                    onLongPressDelayChanged = { longPress = it },
                    onBottomGapChanged = { bottomGap = it },
                    onSplitWidthChanged = { splitWidth = it },
                    onSideMarginChanged = { sideMargin = it },
                    onReadingPlacementChanged = { placement = it },
                    onKeyHeightChanged = {},
                    onOneHandChanged = {},
                    onKeyPreviewChanged = {},
                    onReleaseHapticChanged = {},
                    onBack = {},
                    engineOptions = engineOptions,
                    onEngineOptionChanged = { name, on -> engineChanges += name to on },
                    onDoubleSpacePeriodChanged = { doubleSpace = it },
                    system = system,
                )
            }
        }
    }

    @Test
    fun theInputMethodSwitchesAreRowsThatReportTheirChange() {
        engineOptions =
            EngineOptionsUiState(
                options =
                    listOf(
                        EngineOptionSetting("traditionalize", EngineOptionKind.TRADITIONAL, on = false),
                        EngineOptionSetting("emoji", EngineOptionKind.EMOJI, on = true),
                    ),
            )
        show()
        compose.onNodeWithText(str(R.string.options_engine_section)).performScrollTo().assertExists()
        compose.onNodeWithText(str(R.string.options_engine_emoji)).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.options_engine_traditional)).performScrollTo().performClick()
        assertEquals(listOf("emoji" to false, "traditionalize" to true), engineChanges)
    }

    @Test
    fun characterFormsOpenAsOneSelectionAndChoosingOneClosesThePicker() {
        val names = listOf("s2s", "s2t", "s2hk", "s2tw")
        val labels = listOf("简体", "繁体", "香港", "臺灣")
        engineOptions =
            EngineOptionsUiState(
                options =
                    names.mapIndexed { index, name ->
                        EngineOptionSetting(name, EngineOptionKind.TRADITIONAL, index == 0, names, labels[index])
                    },
            )
        show()
        compose.onNodeWithText(str(R.string.options_engine_character_form)).performScrollTo().performClick()
        compose.onNode(hasText("简体") and hasAnyAncestor(isDialog())).assertIsSelected()
        compose.onNodeWithText("臺灣").performClick()
        assertEquals(listOf("s2tw" to true), engineChanges)
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    @Test
    fun theBundledFourStatesAreWordedByResources() {
        val names = listOf("simplification", "zht", "zh_tw", "zh_hk")
        val states =
            listOf(
                BuiltinScriptState.SIMPLIFIED,
                BuiltinScriptState.TRADITIONAL,
                BuiltinScriptState.TAIWAN,
                BuiltinScriptState.HONG_KONG,
            )
        engineOptions =
            EngineOptionsUiState(
                options =
                    names.mapIndexed { index, name ->
                        EngineOptionSetting(name, EngineOptionKind.TRADITIONAL, index == 0, names, null, states[index])
                    },
            )
        show()
        // The selected state is worded by its resource, not by the schema's own (absent) label.
        compose.onNodeWithText(str(R.string.options_engine_script_simplified)).performScrollTo().assertExists()
        compose.onNodeWithText(str(R.string.options_engine_character_form)).performScrollTo().performClick()
        compose
            .onNode(hasText(str(R.string.options_engine_script_taiwan)) and hasAnyAncestor(isDialog()))
            .assertExists()
        compose.onNodeWithText(str(R.string.options_engine_script_taiwan)).performClick()
        assertEquals(listOf("zh_tw" to true), engineChanges)
    }

    @Test
    fun anInputMethodWithoutSwitchesShowsNoSection() {
        show()
        compose.onNodeWithText(str(R.string.options_engine_section)).assertDoesNotExist()
    }

    @Test
    fun theDoubleSpacePeriodIsASwitchThatIsOnByDefault() {
        show()
        compose.onNodeWithText(str(R.string.options_double_space_title)).performScrollTo().performClick()
        assertEquals(false, doubleSpace)
    }

    @Test
    fun theReadingPlacementIsChosenFromTwoOptionsAndDefaultsToTheRow() {
        show()
        compose.onNodeWithText(str(R.string.options_reading_row_title)).performScrollTo().assertIsSelected()
        compose.onNodeWithText(str(R.string.options_reading_inline_title)).performScrollTo().performClick()
        assertEquals("inline", placement)
    }

    @Test
    fun soundAndVibrationFollowTheSystemUntilTheUserTouchesThem() {
        show()
        compose.onNodeWithText(str(R.string.options_sound_volume_title)).performScrollTo()
        assertEquals(null, feedback.soundEnabled)
        compose.onNodeWithText(str(R.string.options_sound_title)).performScrollTo().performClick()
        assertEquals(false, feedback.soundEnabled)
        assertEquals(null, feedback.hapticsEnabled)
    }

    @Test
    fun theSoundControlsAreDisabledAndExplainedWhenTheSystemTurnedSoundOff() {
        show(system = SystemFeedbackState(soundEnabled = false, hapticsEnabled = true))
        compose.onNodeWithText(str(R.string.options_sound_system_off)).performScrollTo().assertExists()
        compose.onNode(hasClickAction() and hasText(str(R.string.options_sound_title))).assertIsNotEnabled()
        compose.onNode(hasClickAction() and hasText(str(R.string.options_haptics_title))).assertIsEnabled()
    }

    @Test
    fun aChangedChoiceOffersAWayBackToFollowingTheSystem() {
        show(stored = KeyFeedbackSettings(soundEnabled = false, hapticStrength = 0.4f))
        compose.onNodeWithText(str(R.string.options_feedback_reset)).performScrollTo().performClick()
        assertEquals(KeyFeedbackSettings(), feedback)
    }

    @Test
    fun withoutAChoiceThereIsNothingToResetAndSlidersSayTheyFollowTheSystem() {
        show()
        assertEquals(0, compose.onAllNodes(hasText(str(R.string.options_feedback_reset))).fetchSemanticsNodes().size)
        compose.onNodeWithText(str(R.string.options_sound_volume_title)).performScrollTo().assertExists()
        // Both sliders state it, and so do both switches and the long-press delay row.
        assertEquals(5, compose.onAllNodes(hasText(str(R.string.options_follow_system))).fetchSemanticsNodes().size)
    }

    @Test
    fun theLongPressRowFollowsTheSystemUntilTheUserPicksADelayAndCanGoBack() {
        feel = KeyboardFeelState(longPressDelayMs = 300)
        show()
        compose.onNodeWithText(str(R.string.options_long_press_value, 300)).performScrollTo().assertExists()
        compose.onNodeWithText(str(R.string.options_long_press_title)).performScrollTo().performClick()
        compose.onNode(hasText(str(R.string.action_ok)) and hasAnyAncestor(isDialog())).performClick()
        assertEquals(300, longPress)
        compose.onNodeWithText(str(R.string.options_long_press_title)).performScrollTo().performClick()
        // The dialog's own button, not the row's subtitle, brings back the system value.
        compose.onNode(hasText(str(R.string.options_follow_system)) and hasAnyAncestor(isDialog())).performClick()
        assertEquals(null, longPress)
    }

    @Test
    fun theBottomGapRowSaysNoneByDefaultAndTheDialogStoresTheChoice() {
        show()
        // The side margin row says the same word; only the bottom gap row is meant here.
        compose
            .onNode(hasText(str(R.string.options_bottom_gap_title)) and hasText(str(R.string.options_bottom_gap_none)))
            .performScrollTo()
            .assertExists()
        compose.onNodeWithText(str(R.string.options_bottom_gap_title)).performScrollTo().performClick()
        compose.onNode(hasText(str(R.string.action_ok)) and hasAnyAncestor(isDialog())).performClick()
        assertEquals(0, bottomGap)
    }

    @Test
    fun theSplitWidthRowShowsItsValueAndHidesWhenSplittingIsOff() {
        feel = KeyboardFeelState(splitMode = "layout", splitWidthDp = 840)
        show()
        compose
            .onNodeWithText(str(R.string.options_split_width_subtitle, 840))
            .performScrollTo()
            .assertExists()
        compose.onNodeWithText(str(R.string.options_split_width_title)).performScrollTo().performClick()
        compose.onNode(hasText(str(R.string.action_ok)) and hasAnyAncestor(isDialog())).performClick()
        assertEquals(840, splitWidth)
        compose.onNode(hasText(str(R.string.options_reset_default)) and hasAnyAncestor(isDialog())).assertDoesNotExist()
    }

    @Test
    fun theLargeScreenWidthStaysWhileSplittingIsOffBecauseTheMarginFollowsIt() {
        feel = KeyboardFeelState(splitMode = "off", splitWidthDp = 720)
        show()
        compose.onNodeWithText(str(R.string.options_split_width_subtitle, 720)).performScrollTo().assertExists()
    }

    @Test
    fun theSideMarginRowReadsTwoPointFivePercentByDefault() {
        show()
        compose.onNodeWithText(str(R.string.options_side_margin_value, "2.5")).performScrollTo().assertExists()
    }

    @Test
    fun theSideMarginRowSaysNoneAtZero() {
        feel = KeyboardFeelState(sideMarginPerMille = 0)
        show()
        compose
            .onNode(
                hasText(str(R.string.options_side_margin_title)) and hasText(str(R.string.options_side_margin_none)),
            ).performScrollTo()
            .assertExists()
    }

    @Test
    fun theSideMarginResetReturnsTheDefault() {
        feel = KeyboardFeelState(sideMarginPerMille = 40)
        show()
        compose.onNodeWithText(str(R.string.options_side_margin_title)).performScrollTo().performClick()
        compose.onNode(hasText(str(R.string.options_reset_default)) and hasAnyAncestor(isDialog())).performClick()
        assertEquals(25, sideMargin)
    }
}
