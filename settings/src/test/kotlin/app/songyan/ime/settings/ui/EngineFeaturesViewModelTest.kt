package app.songyan.ime.settings.ui

import android.net.Uri
import app.songyan.ime.packages.EngineFeatureReport.Availability
import app.songyan.ime.settings.state.EngineFeaturesGateway
import app.songyan.ime.settings.state.EngineFeaturesSnapshot
import app.songyan.ime.settings.state.FeatureChangeResult
import app.songyan.ime.settings.state.GrammarSelection
import app.songyan.ime.settings.state.TransferProgress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EngineFeaturesViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun showingAnInputMethodLoadsWhatTheBuildRuns() =
        runTest(dispatcher) {
            val gateway = FakeGateway()
            val viewModel = EngineFeaturesViewModel(gateway)
            viewModel.show("quanpin")
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertFalse(state.loading)
            assertEquals("quanpin", state.snapshot?.schemaId)
            assertTrue(state.snapshot!!.typoCorrectionOn)
        }

    @Test
    fun aChangeShowsTheRepublishedBuildAfterwards() =
        runTest(dispatcher) {
            val gateway = FakeGateway()
            val viewModel = EngineFeaturesViewModel(gateway)
            viewModel.show("quanpin")
            advanceUntilIdle()
            viewModel.setFuzzyPinyin(setOf("z-zh"))
            advanceUntilIdle()
            assertEquals(listOf("fuzzy quanpin [z-zh]"), gateway.calls)
            val state = viewModel.uiState.value
            assertFalse(state.saving)
            assertFalse(state.failed)
            assertEquals(setOf("z-zh"), state.snapshot?.fuzzyPairs)
        }

    @Test
    fun aFailedChangeKeepsTheBuildAndSurfacesTheMessageOnce() =
        runTest(dispatcher) {
            val gateway = FakeGateway(result = FeatureChangeResult.Failure("no"))
            val viewModel = EngineFeaturesViewModel(gateway)
            viewModel.show("quanpin")
            advanceUntilIdle()
            viewModel.setTypoCorrection(false)
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertTrue(state.failed)
            assertEquals("no", state.failure)
            assertTrue("the build did not change", state.snapshot!!.typoCorrectionOn)
            viewModel.consumeFailure()
            assertFalse(viewModel.uiState.value.failed)
            assertNull(viewModel.uiState.value.failure)
        }

    @Test
    fun aSecondChangeWhileOneIsSavingIsDropped() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val gateway = FakeGateway(gate = gate)
            val viewModel = EngineFeaturesViewModel(gateway)
            viewModel.show("quanpin")
            advanceUntilIdle()
            viewModel.setTypoCorrection(false)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.saving)
            viewModel.setGrammar(GrammarSelection.Off)
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf("typo false"), gateway.calls)
            assertFalse(viewModel.uiState.value.saving)
        }

    @Test
    fun aLinkImportIsCancellableWhileItRunsAndStopsBeingSoAfterwards() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val gateway = FakeGateway(gate = gate)
            val viewModel = EngineFeaturesViewModel(gateway)
            viewModel.show("quanpin")
            advanceUntilIdle()
            viewModel.importGrammarModelFromUrl("https://example.com/m.gram")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.cancellable)
            viewModel.cancelDownload()
            assertEquals(1, gateway.cancels)
            gate.complete(Unit)
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.cancellable)
            assertEquals(listOf("download grammar https://example.com/m.gram"), gateway.calls)
        }

    @Test
    fun aFileChangeIsNeverOfferedACancel() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val viewModel = EngineFeaturesViewModel(FakeGateway(gate = gate))
            viewModel.show("quanpin")
            advanceUntilIdle()
            viewModel.setTypoCorrection(false)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.saving)
            assertFalse(viewModel.uiState.value.cancellable)
            gate.complete(Unit)
            advanceUntilIdle()
        }

    @Test
    fun aFailedDownloadKeepsItsReasonAndRetryRunsTheSameLinkAgain() =
        runTest(dispatcher) {
            val gateway = FakeGateway(result = FeatureChangeResult.Failure("No network.", retryable = true))
            val viewModel = EngineFeaturesViewModel(gateway)
            viewModel.show("quanpin")
            advanceUntilIdle()
            viewModel.importPredictionDatabaseFromUrl("https://example.com/p.db")
            advanceUntilIdle()
            var state = viewModel.uiState.value
            assertTrue(state.failed && state.retryable)
            assertEquals("No network.", state.failure)

            viewModel.retry()
            advanceUntilIdle()
            assertEquals(
                listOf("download prediction https://example.com/p.db", "download prediction https://example.com/p.db"),
                gateway.calls,
            )
            state = viewModel.uiState.value
            assertTrue("the reason is shown again, it does not vanish", state.failed)
        }

    @Test
    fun aFailureThatRetryCannotFixOffersNoRetry() =
        runTest(dispatcher) {
            val gateway = FakeGateway(result = FeatureChangeResult.Failure("Not a database.", retryable = false))
            val viewModel = EngineFeaturesViewModel(gateway)
            viewModel.show("quanpin")
            advanceUntilIdle()
            viewModel.importPredictionDatabaseFromUrl("https://example.com/p.db")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.failed)
            assertFalse(viewModel.uiState.value.retryable)
        }

    @Test
    fun aStoredGrammarModelIsReportedUntilTheNextChangeOrTheCardIsClosed() =
        runTest(dispatcher) {
            val viewModel = EngineFeaturesViewModel(FakeGateway())
            viewModel.importGrammarModelFromUrl("https://example.com/m.gram")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.grammarModelImported)

            viewModel.setTypoCorrection(false)
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.grammarModelImported)

            viewModel.importGrammarModelFromUrl("https://example.com/n.gram")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.grammarModelImported)
            viewModel.consumeGrammarModelImported()
            assertFalse(viewModel.uiState.value.grammarModelImported)
        }

    @Test
    fun aGrammarModelThatFailedToStoreIsNotReportedAsImported() =
        runTest(dispatcher) {
            val viewModel = EngineFeaturesViewModel(FakeGateway(result = FeatureChangeResult.Failure("no")))
            viewModel.importGrammarModelFromUrl("https://example.com/m.gram")
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.grammarModelImported)
            assertTrue(viewModel.uiState.value.failed)
        }

    @Test
    fun otherFeatureFilesAreNeverReportedAsAGrammarModel() =
        runTest(dispatcher) {
            val viewModel = EngineFeaturesViewModel(FakeGateway())
            viewModel.importPredictionDatabaseFromUrl("https://example.com/p.db")
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.grammarModelImported)
        }

    private class FakeGateway(
        private val result: FeatureChangeResult = FeatureChangeResult.Success,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : EngineFeaturesGateway {
        val calls = mutableListOf<String>()
        private var typo = true
        private var pairs = emptySet<String>()

        override val available = true

        override suspend fun load(schemaId: String) =
            EngineFeaturesSnapshot(
                schemaId = schemaId,
                typoCorrection = Availability.AVAILABLE,
                typoCorrectionOn = typo,
                fuzzyPinyin = Availability.AVAILABLE,
                fuzzyPairs = pairs,
            )

        private suspend fun record(
            call: String,
            apply: () -> Unit,
        ): FeatureChangeResult {
            calls += call
            gate?.await()
            if (result == FeatureChangeResult.Success) apply()
            return result
        }

        override suspend fun setTypoCorrection(enabled: Boolean) = record("typo $enabled") { typo = enabled }

        override suspend fun setFuzzyPinyin(
            schemaId: String,
            pairs: Set<String>,
        ) = record("fuzzy $schemaId ${pairs.sorted()}") { this.pairs = pairs }

        override suspend fun setPrediction(enabled: Boolean) = record("prediction $enabled") {}

        override suspend fun importPredictionDatabase(uri: Uri) = record("import prediction") {}

        override suspend fun setLuaComponent(
            schemaId: String,
            prescription: String,
            enabled: Boolean,
        ) = record("lua $prescription $enabled") {}

        override suspend fun importGrammarModel(uri: Uri) = record("import grammar") {}

        override suspend fun importPredictionDatabaseFromUrl(
            url: String,
            progress: (TransferProgress) -> Unit,
        ) = record("download prediction $url") {}

        override suspend fun importGrammarModelFromUrl(
            url: String,
            progress: (TransferProgress) -> Unit,
        ) = record("download grammar $url") {}

        var cancels = 0

        override fun cancelRunning() {
            cancels++
        }

        override suspend fun setGrammar(
            schemaId: String,
            selection: GrammarSelection,
        ) = record("grammar $selection") {}
    }
}
