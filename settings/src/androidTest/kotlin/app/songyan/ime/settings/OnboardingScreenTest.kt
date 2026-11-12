package app.songyan.ime.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.LocaleManagerCompat
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.settings.state.ImeStatus
import app.songyan.ime.settings.state.RecommendedSchemes
import app.songyan.ime.settings.state.TransferProgress
import app.songyan.ime.settings.state.onboardingProgress
import app.songyan.ime.settings.ui.SchemeImportUiState
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
    private var cancelledDownloads = 0
    private val importState = mutableStateOf<SchemeImportUiState>(SchemeImportUiState.Idle)
    private val catalog =
        RecommendedSchemes.ordered(
            LocaleManagerCompat
                .getSystemLocales(InstrumentationRegistry.getInstrumentation().targetContext)
                .toLanguageTags()
                .split(','),
        )
    private val downloads = mutableListOf<String>()

    private fun show(
        status: ImeStatus,
        typed: Boolean = false,
        schemeReady: Boolean = true,
    ) {
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                OnboardingScreen(
                    progress = onboardingProgress(status, typed, schemeReady),
                    onOpenInputMethodSettings = { openedSystemSettings++ },
                    onShowPicker = {},
                    onRetry = {},
                    onTyped = {},
                    onBack = {},
                    downloadAvailable = true,
                    onDownload = { downloads += it.id },
                    schemeImport = importState.value,
                    onCancelDownload = { cancelledDownloads++ },
                )
            }
        }
    }

    @Test
    fun firstRunDownloadsOnlyAfterConfirmationAndOffersNoSystemShortcut() {
        show(ImeStatus(enabled = false, selected = false), schemeReady = false)
        compose.onNodeWithText(str(R.string.onboarding_step_enable_action)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.tryout_hint)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.schemes_recommended_confirm)).performScrollTo().performClick()
        assertEquals(emptyList<String>(), downloads)
        compose.onNodeWithText(str(R.string.action_cancel)).performClick()
        assertEquals(emptyList<String>(), downloads)
        compose.onNodeWithText(str(R.string.schemes_recommended_confirm)).performScrollTo().performClick()
        confirmDownload()
        assertEquals(listOf(catalog.first().id), downloads)
    }

    private fun confirmDownload() {
        compose.onNode(hasText(str(R.string.schemes_recommended_confirm)) and hasAnyAncestor(isDialog())).performClick()
    }

    @Test
    fun theLocaleRecommendationLeadsAndOtherSchemesStayCollapsedUntilExpanded() {
        show(ImeStatus(enabled = false, selected = false), schemeReady = false)
        compose.onNodeWithText(str(R.string.schemes_recommended_section)).assertIsDisplayed()
        compose.onNodeWithText(str(catalog.first().nameRes)).assertIsDisplayed()
        catalog.drop(1).forEach { compose.onNodeWithText(str(it.nameRes)).assertDoesNotExist() }
        val disclosure = str(R.string.onboarding_more_schemes, catalog.size - 1)
        compose.onNodeWithText(disclosure).performScrollTo().performClick()
        catalog.drop(1).forEach {
            compose.onNodeWithText(str(it.nameRes)).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText(disclosure).performScrollTo().performClick()
        catalog.drop(1).forEach { compose.onNodeWithText(str(it.nameRes)).assertDoesNotExist() }
    }

    @Test
    fun theRecommendedActionBecomesProgressAndCanBeCancelled() {
        show(ImeStatus(enabled = false, selected = false), schemeReady = false)
        compose.onNodeWithText(str(R.string.schemes_recommended_confirm)).performScrollTo().performClick()
        confirmDownload()
        compose.runOnIdle {
            importState.value = SchemeImportUiState.Running(TransferProgress(bytes = 50, total = 100))
        }
        compose.onNodeWithText(str(R.string.schemes_recommended_confirm)).assertDoesNotExist()
        compose
            .onNode(
                hasProgressBarRangeInfo(ProgressBarRangeInfo(0.5f, 0f..1f)),
            ).performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(str(R.string.schemes_scheme_downloading)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.action_cancel)).performScrollTo().performClick()
        assertEquals(1, cancelledDownloads)
        compose.runOnIdle { importState.value = SchemeImportUiState.Running(cancelling = true) }
        compose.onNodeWithText(str(R.string.action_cancel)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.import_cancelling)).assertExists()
    }

    @Test
    fun anAlternativeStillConfirmsAndKeepsItsProgressAvailable() {
        show(ImeStatus(enabled = false, selected = false), schemeReady = false)
        val disclosure = str(R.string.onboarding_more_schemes, catalog.size - 1)
        compose.onNodeWithText(disclosure).performScrollTo().performClick()
        val alternative = catalog[1]
        compose
            .onNodeWithContentDescription(str(R.string.onboarding_download_scheme, str(alternative.nameRes)))
            .performScrollTo()
            .performClick()
        confirmDownload()
        assertEquals(listOf(alternative.id), downloads)
        compose.runOnIdle { importState.value = SchemeImportUiState.Running() }
        compose.onNodeWithText(disclosure).assertIsNotEnabled()
        compose.onNodeWithText(str(R.string.action_cancel)).performScrollTo().performClick()
        assertEquals(1, cancelledDownloads)
        compose.onNodeWithText(str(R.string.schemes_scheme_importing_note)).assertExists()
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
