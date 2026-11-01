package app.songyan.ime.settings

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.songyan.ime.settings.state.EngineStatus
import app.songyan.ime.settings.state.ImeStatus
import app.songyan.ime.settings.ui.DictionaryImportAction
import app.songyan.ime.settings.ui.DictionaryImportFailure
import app.songyan.ime.settings.ui.DictionaryImportUiState
import app.songyan.ime.settings.ui.SchemeImportUiState
import app.songyan.ime.settings.ui.SettingsRoutes
import app.songyan.ime.settings.ui.SettingsUiState
import app.songyan.ime.settings.ui.components.ImportKind
import app.songyan.ime.settings.ui.screens.HomeScreen
import app.songyan.ime.settings.ui.theme.SongyanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Home screen rules, and the import card that stays visible on the home page. */
class HomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var cancelled: ImportKind? = null
    private var retried: ImportKind? = null
    private var dismissedDictionary = false

    private fun show(state: SettingsUiState) {
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                HomeScreen(
                    state = state,
                    onOpen = {},
                    onRetryImeStatus = {},
                    onCancelImport = { cancelled = it },
                    onRetryImport = { retried = it },
                    onDismissDictionaryImport = { dismissedDictionary = true },
                    onDismissSchemeImport = {},
                )
            }
        }
    }

    private val opened = mutableListOf<String>()

    private fun showSearchable() {
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                HomeScreen(
                    state = SettingsUiState(engine = EngineStatus.Ready, ime = ImeStatus(true, true)),
                    onOpen = { opened += it },
                    onRetryImeStatus = {},
                    onCancelImport = {},
                    onRetryImport = {},
                    onDismissDictionaryImport = {},
                    onDismissSchemeImport = {},
                )
            }
        }
    }

    private fun count(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size

    @Test
    fun notEnabledAndReadyAreNeverShownTogether() {
        show(SettingsUiState(engine = EngineStatus.Ready, ime = ImeStatus(enabled = false, selected = false)))
        compose.onNodeWithText(str(R.string.home_enable_title)).assertExists()
        assertEquals(0, count(str(R.string.home_engine_ready)))
    }

    @Test
    fun readyIsShownOnlyWhenTheKeyboardIsAlsoTheCurrentOne() {
        show(SettingsUiState(engine = EngineStatus.Ready, ime = ImeStatus(enabled = true, selected = true)))
        compose.onNodeWithText(str(R.string.home_engine_ready)).assertExists()
        assertEquals(0, count(str(R.string.home_enable_title)))
        assertEquals(0, count(str(R.string.home_select_title)))
    }

    @Test
    fun theDefaultKeyboardIsNeverCalledNotTheDefault() {
        show(SettingsUiState(engine = EngineStatus.Ready, ime = ImeStatus(enabled = true, selected = true)))
        assertEquals(0, count(str(R.string.home_select_title)))
    }

    @Test
    fun aRunningImportShowsItsProgressAndCanBeCancelledFromHome() {
        show(
            SettingsUiState(
                engine = EngineStatus.Ready,
                ime = ImeStatus(enabled = true, selected = true),
                dictionaryImport = DictionaryImportUiState.Running(DictionaryImportAction.IMPORT),
            ),
        )
        compose.onNodeWithText(str(R.string.schemes_dictionary_importing)).assertExists()
        compose.onNodeWithText(str(R.string.action_cancel)).performClick()
        assertEquals(ImportKind.DICTIONARY, cancelled)
    }

    @Test
    fun aFailedDownloadStaysWithARetryAndTheReason() {
        show(
            SettingsUiState(
                engine = EngineStatus.Ready,
                ime = ImeStatus(enabled = true, selected = true),
                dictionaryImport =
                    DictionaryImportUiState.Failed(
                        action = DictionaryImportAction.IMPORT,
                        reason = DictionaryImportFailure.GATEWAY,
                        message = "没有网络连接，请检查网络后重试。",
                        retryable = true,
                    ),
            ),
        )
        compose.mainClock.advanceTimeBy(10_000)
        compose.onNodeWithText("没有网络连接，请检查网络后重试。").assertExists()
        compose.onNodeWithText(str(R.string.action_retry)).performClick()
        assertEquals(ImportKind.DICTIONARY, retried)
        compose.onNodeWithText(str(R.string.action_dismiss)).performClick()
        assertEquals(true, dismissedDictionary)
    }

    @Test
    fun aFailureThatRetryCannotFixOffersOnlyClose() {
        show(
            SettingsUiState(
                engine = EngineStatus.Ready,
                ime = ImeStatus(enabled = true, selected = true),
                schemeImport = SchemeImportUiState.Failed(message = "文件太大。", retryable = false),
            ),
        )
        compose.onNodeWithText("文件太大。").assertExists()
        assertEquals(0, compose.onAllNodes(hasText(str(R.string.action_retry))).fetchSemanticsNodes().size)
    }

    @Test
    fun preparingWithoutAReportedPercentageSaysSoInsteadOfShowingZero() {
        show(SettingsUiState(engine = EngineStatus.Preparing(0), ime = ImeStatus(enabled = true, selected = true)))
        compose.onNodeWithText(str(R.string.home_engine_preparing_unknown)).assertExists()
        assertEquals(0, count("0%"))
    }

    @Test
    fun preparingWithAReportedPercentageShowsIt() {
        show(SettingsUiState(engine = EngineStatus.Preparing(40), ime = ImeStatus(enabled = true, selected = true)))
        compose.onNodeWithText(str(R.string.home_engine_preparing, 40)).assertExists()
    }

    @Test
    fun searchFindsASettingInsideAPageAndOpensThatPage() {
        showSearchable()
        // A prefix shorter than the title (a fixed three characters is the whole title in Chinese), so
        // the typed text itself is never mistaken for the result.
        compose
            .onNodeWithText(
                str(R.string.search_placeholder),
            ).performTextInput(str(R.string.features_fuzzy_title).dropLast(1))

        // The result names the page it lives on; the home rows are hidden while searching.
        compose.onNodeWithText(str(R.string.features_fuzzy_title)).assertExists()
        assertEquals(0, count(str(R.string.section_keyboard)))
        compose.onNodeWithText(str(R.string.features_fuzzy_title)).performClick()
        assertEquals(listOf(SettingsRoutes.FEATURES), opened)
    }

    @Test
    fun searchWithoutAMatchSaysSoAndClearingShowsTheListAgain() {
        showSearchable()
        compose.onNodeWithText(str(R.string.search_placeholder)).performTextInput("zzzz不存在")
        compose.onNodeWithText(str(R.string.search_empty_title)).assertExists()

        compose.onNodeWithContentDescription(str(R.string.search_clear)).performClick()
        compose.onNodeWithText(str(R.string.row_schemes_title)).assertExists()
        assertEquals(0, count(str(R.string.search_empty_title)))
    }
}
