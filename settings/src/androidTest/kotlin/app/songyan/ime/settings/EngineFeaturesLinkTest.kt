package app.songyan.ime.settings

import android.net.Uri
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import app.songyan.ime.packages.EngineFeatureReport.Availability
import app.songyan.ime.settings.state.EngineFeaturesGateway
import app.songyan.ime.settings.state.EngineFeaturesSnapshot
import app.songyan.ime.settings.state.FeatureChangeResult
import app.songyan.ime.settings.state.GrammarSelection
import app.songyan.ime.settings.state.TransferProgress
import app.songyan.ime.settings.ui.EngineFeaturesViewModel
import app.songyan.ime.settings.ui.screens.EngineFeaturesScreen
import app.songyan.ime.settings.ui.theme.SongyanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Grammar models and prediction databases can be downloaded from a link the user typed. */
class EngineFeaturesLinkTest {
    @get:Rule
    val compose = createComposeRule()

    private class Gateway(
        private val result: FeatureChangeResult,
    ) : EngineFeaturesGateway {
        val downloads = mutableListOf<String>()
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
            downloads += url
            return result
        }

        override suspend fun importGrammarModelFromUrl(
            url: String,
            progress: (TransferProgress) -> Unit,
        ): FeatureChangeResult {
            downloads += url
            return result
        }

        override suspend fun setGrammar(
            schemaId: String,
            selection: GrammarSelection,
        ) = FeatureChangeResult.Success
    }

    private fun show(gateway: Gateway) {
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                EngineFeaturesScreen(
                    schemaId = "quanpin",
                    schemaName = "Quanpin",
                    onBack = {},
                    viewModel =
                        viewModel(
                            factory =
                                object : ViewModelProvider.Factory {
                                    @Suppress("UNCHECKED_CAST")
                                    override fun <T : ViewModel> create(
                                        modelClass: Class<T>,
                                        extras: CreationExtras,
                                    ): T = EngineFeaturesViewModel(gateway) as T
                                },
                        ),
                )
            }
        }
    }

    private fun linkRows() = compose.onAllNodes(hasText(str(R.string.features_link_import)) and hasClickAction())

    private fun has(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun openLinkDialog() {
        compose.waitUntil(5_000) { linkRows().fetchSemanticsNodes().size == 2 }
        linkRows()[0].performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun theDialogOnlyAcceptsAnHttpsLink() {
        show(Gateway(FeatureChangeResult.Success))
        openLinkDialog()
        compose.onNode(hasSetTextAction()).performTextInput("http://example.com/p.db")
        compose.onNode(hasText(str(R.string.features_link_confirm)) and hasClickAction()).assertIsNotEnabled()
    }

    @Test
    fun aTypedLinkIsDownloadedOnlyAfterTheUserConfirms() {
        val gateway = Gateway(FeatureChangeResult.Success)
        show(gateway)
        openLinkDialog()
        compose.onNode(hasSetTextAction()).performTextInput("https://example.com/p.db")
        assertEquals("nothing is fetched before Import", emptyList<String>(), gateway.downloads)
        compose
            .onNode(
                hasText(str(R.string.features_link_confirm)) and hasClickAction(),
            ).assertIsEnabled()
            .performClick()
        compose.waitUntil(5_000) { gateway.downloads.isNotEmpty() }
        assertEquals(listOf("https://example.com/p.db"), gateway.downloads)
    }

    @Test
    fun aFailedDownloadStaysWithItsReasonAndOffersRetry() {
        val gateway = Gateway(FeatureChangeResult.Failure("No network connection.", retryable = true))
        show(gateway)
        openLinkDialog()
        compose.onNode(hasSetTextAction()).performTextInput("https://example.com/p.db")
        compose.onNode(hasText(str(R.string.features_link_confirm)) and hasClickAction()).performClick()
        compose.waitUntil(5_000) { has("No network connection.") }
        // It does not go away by itself.
        compose.mainClock.advanceTimeBy(10_000)
        compose.onNodeWithText("No network connection.").assertExists()
        compose.onNode(hasText(str(R.string.features_retry)) and hasClickAction()).performClick()
        compose.waitUntil(5_000) { gateway.downloads.size == 2 }
        compose.onNode(hasText(str(R.string.features_dismiss)) and hasClickAction()).performClick()
        compose.waitUntil(5_000) { !has("No network connection.") }
    }

    @Test
    fun aFailureThatRetryCannotFixHasOnlyClose() {
        val gateway = Gateway(FeatureChangeResult.Failure("That is not a database.", retryable = false))
        show(gateway)
        openLinkDialog()
        compose.onNode(hasSetTextAction()).performTextInput("https://example.com/p.db")
        compose.onNode(hasText(str(R.string.features_link_confirm)) and hasClickAction()).performClick()
        compose.waitUntil(5_000) { has("That is not a database.") }
        assertEquals(0, compose.onAllNodes(hasText(str(R.string.features_retry))).fetchSemanticsNodes().size)
    }
}
