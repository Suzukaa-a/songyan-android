package app.songyan.ime.settings.ui

import android.app.Application
import app.songyan.ime.settings.state.ImeStatus
import app.songyan.ime.settings.state.ImeStatusSource
import app.songyan.ime.settings.state.ImportedSchemeInfo
import app.songyan.ime.settings.state.InMemorySettingsPreferences
import app.songyan.ime.settings.state.PackageLearningResult
import app.songyan.ime.settings.state.SchemeImportGateway
import app.songyan.ime.settings.state.SchemeImportResult
import app.songyan.ime.settings.state.SettingsAppState
import app.songyan.ime.settings.state.SettingsAppStateSource
import app.songyan.ime.settings.state.TransferProgress
import app.songyan.ime.settings.state.UnavailableSchemeImportGateway
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The scheme package import state machine: the gateway runs off the main thread, its result becomes
 * a one-shot screen state and a successful request re-reads app state.
 *
 * Picking a ZIP cannot go through [SettingsViewModel.importScheme] here because `android.net.Uri`
 * cannot be instantiated in a JVM unit test; that function only forwards the picked Uri into
 * [SettingsViewModel.runSchemeImport], which these tests exercise directly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelSchemeImportTest {
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
    fun theImportActionIsUnavailableUntilTheAppInstallsItsGateway() =
        runTest(dispatcher) {
            val viewModel = viewModel(gateway = FakeGateway(available = false))

            assertFalse(viewModel.uiState.value.schemeImportAvailable)
            assertEquals(SchemeImportUiState.Idle, viewModel.uiState.value.schemeImport)
        }

    @Test
    fun aFallbackGatewayReportsItselfUnavailable() {
        assertFalse(UnavailableSchemeImportGateway().available)
    }

    @Test
    fun theImportActionAppearsOnceTheGatewayIsAvailable() =
        runTest(dispatcher) {
            val viewModel = viewModel(gateway = FakeGateway(available = true))

            assertTrue(viewModel.uiState.value.schemeImportAvailable)
        }

    @Test
    fun importShowsRunningThenSucceededWithTheImportedScheme() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<SchemeImportResult>()
            val source = FakeSource()
            val viewModel = viewModel(source = source)
            val seen = watch(viewModel)
            var requests = 0

            viewModel.runSchemeImport {
                requests++
                gate.await()
            }
            runCurrent()
            assertEquals(SchemeImportUiState.Running(), seen.last())
            assertEquals(1, requests)

            val info = ImportedSchemeInfo(name = "双拼", schemaIds = listOf("double_pinyin"), inUse = false)
            gate.complete(SchemeImportResult.Success(info))
            advanceUntilIdle()
            assertEquals(SchemeImportUiState.Succeeded(info), seen.last())
            assertEquals(1, source.refreshes)
        }

    @Test
    fun aDownloadShowsHowMuchHasArrivedUntilItFinishes() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<SchemeImportResult>()
            val viewModel = viewModel(source = FakeSource())
            var report: ((TransferProgress) -> Unit)? = null

            viewModel.runSchemeImport { progress ->
                report = progress
                gate.await()
            }
            runCurrent()
            report!!(TransferProgress(3_000_000, 12_000_000))
            runCurrent()
            assertEquals(
                SchemeImportUiState.Running(TransferProgress(3_000_000, 12_000_000)),
                viewModel.uiState.value.schemeImport,
            )

            gate.complete(SchemeImportResult.Failure(null))
            advanceUntilIdle()
            // A late report from the finished request must not bring the running card back.
            report!!(TransferProgress(12_000_000, 12_000_000))
            assertEquals(SchemeImportUiState.Failed(null), viewModel.uiState.value.schemeImport)
        }

    @Test
    fun aGatewayFailureKeepsItsLocalizedMessageAndDoesNotRefresh() =
        runTest(dispatcher) {
            val source = FakeSource()
            val viewModel = viewModel(source = source)

            viewModel.runSchemeImport { SchemeImportResult.Failure("方案包不完整。") }
            advanceUntilIdle()

            assertEquals(
                SchemeImportUiState.Failed("方案包不完整。"),
                viewModel.uiState.value.schemeImport,
            )
            assertEquals(0, source.refreshes)
        }

    @Test
    fun aFailureWithoutAMessageLeavesTheGenericCopyToTheScreen() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            viewModel.runSchemeImport { SchemeImportResult.Failure(null) }
            advanceUntilIdle()

            assertEquals(SchemeImportUiState.Failed(null), viewModel.uiState.value.schemeImport)
        }

    @Test
    fun aSecondImportIsIgnoredWhileTheFirstOneRuns() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<SchemeImportResult>()
            val viewModel = viewModel()
            var requests = 0

            viewModel.runSchemeImport {
                requests++
                gate.await()
            }
            viewModel.runSchemeImport {
                requests++
                gate.await()
            }
            runCurrent()
            assertEquals(1, requests)

            gate.complete(SchemeImportResult.Success(null))
            advanceUntilIdle()
        }

    @Test
    fun dismissingAFailureReturnsToIdle() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.runSchemeImport { SchemeImportResult.Failure(null) }
            advanceUntilIdle()

            viewModel.dismissSchemeImport()
            runCurrent()

            assertEquals(SchemeImportUiState.Idle, viewModel.uiState.value.schemeImport)
        }

    @Test
    fun aRunningImportCannotBeDismissed() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<SchemeImportResult>()
            val viewModel = viewModel()
            viewModel.runSchemeImport { gate.await() }
            runCurrent()

            viewModel.dismissSchemeImport()
            runCurrent()

            assertEquals(SchemeImportUiState.Running(), viewModel.uiState.value.schemeImport)
            gate.complete(SchemeImportResult.Success(null))
            advanceUntilIdle()
        }

    @Test
    fun anUninstalledGatewayIsReportedAndNeverCalled() =
        runTest(dispatcher) {
            val viewModel = viewModel(gateway = FakeGateway(available = false))
            var requests = 0

            viewModel.runSchemeImport {
                requests++
                SchemeImportResult.Success(null)
            }
            runCurrent()

            assertEquals(SchemeImportUiState.Failed(null), viewModel.uiState.value.schemeImport)
            assertEquals(0, requests)
        }

    private val withWords =
        ImportedSchemeInfo(
            "双拼",
            listOf("double_pinyin"),
            inUse = false,
            sourceId = "scheme-1",
            learningDictionaries = 2,
        )

    @Test
    fun learnedWordsOfAPackageAreMergedOnlyAfterTheUserAgrees() =
        runTest(dispatcher) {
            val gateway = FakeGateway()
            val source = FakeSource()
            val viewModel = viewModel(source = source, gateway = gateway)
            val seen = watch(viewModel)
            viewModel.runSchemeImport { SchemeImportResult.Success(withWords) }
            advanceUntilIdle()

            assertEquals(SchemeImportUiState.Succeeded(withWords), seen.last())
            assertTrue((seen.last() as SchemeImportUiState.Succeeded).offersLearning)
            assertTrue("nothing merges before the answer", gateway.merged.isEmpty())

            viewModel.mergePackageLearning()
            advanceUntilIdle()
            assertTrue(SchemeImportUiState.MergingLearning in seen)

            assertEquals(listOf("scheme-1"), gateway.merged)
            assertEquals(SchemeImportUiState.LearningMerged("已并入 2 份词库，共 5 条。"), seen.last())
            assertEquals("the package may have been put in use", 2, source.refreshes)
        }

    @Test
    fun decliningKeepsThePackageAndDropsTheWaitingWords() =
        runTest(dispatcher) {
            val gateway = FakeGateway()
            val viewModel = viewModel(gateway = gateway)
            val seen = watch(viewModel)
            viewModel.runSchemeImport { SchemeImportResult.Success(withWords) }
            advanceUntilIdle()

            viewModel.declinePackageLearning()
            advanceUntilIdle()

            assertEquals(listOf("scheme-1"), gateway.discarded)
            assertTrue(gateway.merged.isEmpty())
            val done = seen.last() as SchemeImportUiState.Succeeded
            assertFalse(done.offersLearning)
            assertEquals("双拼", done.info?.name)
        }

    @Test
    fun closingTheCardWithoutAnsweringIsTheSameAsDeclining() =
        runTest(dispatcher) {
            val gateway = FakeGateway()
            val viewModel = viewModel(gateway = gateway)
            val seen = watch(viewModel)
            viewModel.runSchemeImport { SchemeImportResult.Success(withWords) }
            advanceUntilIdle()

            viewModel.dismissSchemeImport()
            advanceUntilIdle()

            assertEquals(listOf("scheme-1"), gateway.discarded)
            assertEquals(SchemeImportUiState.Idle, seen.last())
        }

    @Test
    fun aFailedMergeSaysSoAndTheImportStaysDone() =
        runTest(dispatcher) {
            val gateway = FakeGateway(mergeResult = PackageLearningResult.Failure("没有并入"))
            val viewModel = viewModel(gateway = gateway)
            val seen = watch(viewModel)
            viewModel.runSchemeImport { SchemeImportResult.Success(withWords) }
            advanceUntilIdle()

            viewModel.mergePackageLearning()
            advanceUntilIdle()

            assertEquals(SchemeImportUiState.Failed("没有并入"), seen.last())
            // Asking again after the card closes is not possible: the words were dropped with the attempt.
            viewModel.mergePackageLearning()
            advanceUntilIdle()
            assertEquals(1, gateway.merged.size)
        }

    @Test
    fun aPackageWithoutLearnedWordsOffersNothingToMerge() =
        runTest(dispatcher) {
            val gateway = FakeGateway()
            val viewModel = viewModel(gateway = gateway)
            val seen = watch(viewModel)
            viewModel.runSchemeImport { SchemeImportResult.Success(withWords.copy(learningDictionaries = 0)) }
            advanceUntilIdle()

            viewModel.mergePackageLearning()
            advanceUntilIdle()

            assertTrue(gateway.merged.isEmpty())
            assertFalse((seen.last() as SchemeImportUiState.Succeeded).offersLearning)
        }

    private fun viewModel(
        source: FakeSource = FakeSource(),
        gateway: FakeGateway = FakeGateway(),
    ): SettingsViewModel =
        SettingsViewModel(
            application = Application(),
            appStateSource = source,
            imeStatusSource = ImeStatusSource { ImeStatus(enabled = true, selected = true) },
            preferences = InMemorySettingsPreferences(),
            schemeGateway = gateway,
            ioDispatcher = dispatcher,
        )

    private fun TestScope.watch(viewModel: SettingsViewModel): List<SchemeImportUiState> {
        val seen = mutableListOf<SchemeImportUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { seen += it.schemeImport }
        }
        return seen
    }

    private class FakeSource(
        initial: SettingsAppState = SettingsAppState(),
    ) : SettingsAppStateSource {
        private val mutable = MutableStateFlow(initial)
        override val state: StateFlow<SettingsAppState> = mutable.asStateFlow()

        var refreshes: Int = 0
            private set

        override fun refresh() {
            refreshes++
        }
    }

    private class FakeGateway(
        override val available: Boolean = true,
        private val mergeResult: PackageLearningResult = PackageLearningResult.Merged("已并入 2 份词库，共 5 条。"),
    ) : SchemeImportGateway {
        val merged = mutableListOf<String>()
        val discarded = mutableListOf<String>()

        override suspend fun importFromUri(uri: android.net.Uri): SchemeImportResult = SchemeImportResult.Success(null)

        override suspend fun mergePackageLearning(sourceId: String): PackageLearningResult {
            merged += sourceId
            return mergeResult
        }

        override fun discardPackageLearning(sourceId: String) {
            discarded += sourceId
        }
    }
}
