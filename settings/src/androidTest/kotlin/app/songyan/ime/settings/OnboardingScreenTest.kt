package app.songyan.ime.settings

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.songyan.ime.settings.state.ImeStatus
import app.songyan.ime.settings.state.onboardingProgress
import app.songyan.ime.settings.ui.screens.OnboardingScreen
import app.songyan.ime.settings.ui.theme.SongyanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The guide explains the system warning first, and its try-out step keeps the field. */
class OnboardingScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var openedSystemSettings = 0

    private fun show(
        status: ImeStatus,
        typed: Boolean = false,
    ) {
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                OnboardingScreen(
                    progress = onboardingProgress(status, typed),
                    onOpenInputMethodSettings = { openedSystemSettings++ },
                    onShowPicker = {},
                    onRetry = {},
                    onTyped = {},
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun theSystemWarningIsExplainedBeforeSystemSettingsOpen() {
        show(ImeStatus(enabled = false, selected = false))
        compose.onNodeWithText(str(R.string.onboarding_step_enable_action)).performClick()
        compose.onNodeWithText(str(R.string.onboarding_warning_title)).assertExists()
        assertEquals("settings must not open before the user continues", 0, openedSystemSettings)
        compose.onNodeWithText(str(R.string.onboarding_warning_continue)).performClick()
        assertEquals(1, openedSystemSettings)
    }

    @Test
    fun cancellingTheExplanationOpensNothing() {
        show(ImeStatus(enabled = false, selected = false))
        compose.onNodeWithText(str(R.string.onboarding_step_enable_action)).performClick()
        compose.onNodeWithText(str(R.string.action_cancel)).performClick()
        assertEquals(0, openedSystemSettings)
        assertEquals(0, compose.onAllNodes(hasText(str(R.string.onboarding_warning_title))).fetchSemanticsNodes().size)
    }

    @Test
    fun theTryOutFieldIsShownWhileTheStepIsCurrent() {
        show(ImeStatus(enabled = true, selected = true))
        compose.onNodeWithText(str(R.string.tryout_hint)).assertExists()
    }

    @Test
    fun theTryOutFieldStaysOnceTheStepIsDone() {
        show(ImeStatus(enabled = true, selected = true), typed = true)
        compose.onNodeWithText(str(R.string.tryout_hint)).assertExists()
        compose.onNodeWithText(str(R.string.onboarding_done)).assertExists()
    }
}
