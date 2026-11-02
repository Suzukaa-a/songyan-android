package app.songyan.ime.settings

import android.app.Application
import android.net.Uri
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import app.songyan.ime.keyboard.support.SettingsTarget
import app.songyan.ime.packages.EngineFeatureReport.Availability
import app.songyan.ime.settings.state.EngineFeaturesGateway
import app.songyan.ime.settings.state.EngineFeaturesHolder
import app.songyan.ime.settings.state.EngineFeaturesSnapshot
import app.songyan.ime.settings.state.EngineStatus
import app.songyan.ime.settings.state.FeatureChangeResult
import app.songyan.ime.settings.state.GrammarSelection
import app.songyan.ime.settings.state.ImeStatus
import app.songyan.ime.settings.state.InMemorySettingsAppStateSource
import app.songyan.ime.settings.state.InMemorySettingsPreferences
import app.songyan.ime.settings.state.InstalledScheme
import app.songyan.ime.settings.state.SettingsAppState
import app.songyan.ime.settings.state.TransferProgress
import app.songyan.ime.settings.state.UnavailableDictionaryImportGateway
import app.songyan.ime.settings.state.UnavailableEngineFeaturesGateway
import app.songyan.ime.settings.state.UnavailableSchemeImportGateway
import app.songyan.ime.settings.ui.SettingsNavHost
import app.songyan.ime.settings.ui.SettingsViewModel
import app.songyan.ime.settings.ui.theme.SongyanTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** First launch, targeted opens, and that a keyboard-originated open never lands on a stale page. */
class SettingsNavHostTest {
    @get:Rule
    val compose = createComposeRule()

    private val preferences = InMemorySettingsPreferences()

    private fun viewModel(
        ime: ImeStatus,
        appState: SettingsAppState,
    ) = SettingsViewModel(
        application = ApplicationProvider.getApplicationContext<Application>(),
        appStateSource = InMemorySettingsAppStateSource(appState),
        imeStatusSource = { ime },
        preferences = preferences,
        dictionaryGateway = UnavailableDictionaryImportGateway(),
        schemeGateway = UnavailableSchemeImportGateway(),
    )

    private fun show(
        ime: ImeStatus,
        target: SettingsTarget? = null,
        appState: SettingsAppState = SettingsAppState(engine = EngineStatus.Ready),
    ) {
        val model = viewModel(ime, appState)
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                SettingsNavHost(viewModel = model, versionName = "1", target = target)
            }
        }
    }

    private fun onOnboarding() =
        compose.onAllNodes(hasText(str(R.string.onboarding_intro))).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theGuideOpensByItselfOnTheFirstLaunch() {
        show(ImeStatus(enabled = false, selected = false))
        compose.waitUntil(5_000) { onOnboarding() }
        assertTrue(runBlocking { preferences.onboardingShown.first() })
    }

    @Test
    fun theGuideDoesNotOpenAgainOnTheNextLaunch() {
        runBlocking { preferences.setOnboardingShown() }
        show(ImeStatus(enabled = false, selected = false))
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.search_placeholder)).assertExists()
        assertEquals(false, onOnboarding())
    }

    @Test
    fun aKeyboardThatIsAlreadySetUpNeedsNoGuide() {
        show(ImeStatus(enabled = true, selected = true))
        compose.waitForIdle()
        assertEquals(false, onOnboarding())
        // It was still counted as shown, so a later un-selecting does not trigger it.
        compose.waitUntil(5_000) { runBlocking { preferences.onboardingShown.first() } }
    }

    @Test
    fun aRequestFromTheKeyboardLandsOnItsPageNotOnTheGuide() {
        show(ImeStatus(enabled = false, selected = false), target = SettingsTarget.QUICK_CONTENT)
        compose.waitUntil(5_000) {
            compose
                .onAllNodes(
                    hasText(str(R.string.quick_content_clipboard_section)),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        assertEquals(false, onOnboarding())
    }

    @Test
    fun theAddPhraseRequestOpensTheAddDialogDirectly() {
        show(ImeStatus(enabled = true, selected = true), target = SettingsTarget.ADD_PHRASE)
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** A grammar download that reports a quarter arrived and then waits until the test ends. */
    private class SlowDownloadGateway : EngineFeaturesGateway {
        val finish = CompletableDeferred<FeatureChangeResult>()
        override val available = true

        override suspend fun load(schemaId: String) =
            EngineFeaturesSnapshot(
                schemaId = schemaId,
                prediction = Availability.AVAILABLE,
                grammar = Availability.AVAILABLE,
            )

        override suspend fun setTypoCorrection(enabled: Boolean) = FeatureChangeResult.Success

        override suspend fun setFuzzyPinyin(
            schemaId: String,
            pairs: Set<String>,
        ) = FeatureChangeResult.Success

        override suspend fun setPrediction(enabled: Boolean) = FeatureChangeResult.Success

        override suspend fun importPredictionDatabase(uri: Uri) = FeatureChangeResult.Success

        override suspend fun setLuaComponent(
            schemaId: String,
            prescription: String,
            enabled: Boolean,
        ) = FeatureChangeResult.Success

        override suspend fun importGrammarModel(uri: Uri) = FeatureChangeResult.Success

        override suspend fun importPredictionDatabaseFromUrl(
            url: String,
            progress: (TransferProgress) -> Unit,
        ): FeatureChangeResult {
            progress(TransferProgress(QUARTER, 4 * QUARTER))
            return finish.await()
        }

        override suspend fun importGrammarModelFromUrl(
            url: String,
            progress: (TransferProgress) -> Unit,
        ) = importPredictionDatabaseFromUrl(url, progress)

        override suspend fun setGrammar(
            schemaId: String,
            selection: GrammarSelection,
        ) = FeatureChangeResult.Success
    }

    private fun tap(text: String) {
        compose.waitUntil(
            5_000,
        ) { compose.onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        val node = compose.onAllNodes(hasText(text) and hasClickAction())[0]
        // Dialog buttons have no scrolling parent; page rows may be below the fold.
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    @Test
    fun aDownloadStillShowsItsProgressAfterLeavingTheFeaturesPageAndComingBack() {
        val gateway = SlowDownloadGateway()
        EngineFeaturesHolder.install(gateway)
        try {
            runBlocking { preferences.setOnboardingShown() }
            show(
                ImeStatus(enabled = true, selected = true),
                appState =
                    SettingsAppState(
                        engine = EngineStatus.Ready,
                        schemes = listOf(InstalledScheme("quanpin", "全拼")),
                        inUseSchemeId = "quanpin",
                    ),
            )
            tap(str(R.string.row_options_title))
            tap(str(R.string.features_title))
            tap(str(R.string.features_link_import))
            compose.onNode(hasSetTextAction()).performTextInput("https://example.com/p.db")
            tap(str(R.string.features_link_confirm))
            val downloading = str(R.string.features_downloading)
            compose.waitUntil(5_000) { compose.onAllNodes(hasText(downloading)).fetchSemanticsNodes().isNotEmpty() }

            compose.onNodeWithContentDescription(str(R.string.action_back)).performClick()
            tap(str(R.string.features_title))
            compose.waitUntil(5_000) { compose.onAllNodes(hasText(downloading)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText(str(R.string.features_stop)) and hasClickAction()).assertExists()
        } finally {
            gateway.finish.complete(FeatureChangeResult.Success)
            EngineFeaturesHolder.install(UnavailableEngineFeaturesGateway())
        }
    }

    private companion object {
        const val QUARTER = 25L * 1024 * 1024
    }
}
