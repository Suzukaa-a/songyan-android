package app.songyan.ime.settings.ui

import android.app.Application
import android.net.Uri
import app.songyan.ime.settings.state.DictionaryImportGateway
import app.songyan.ime.settings.state.DictionaryImportResult
import app.songyan.ime.settings.state.ImeStatus
import app.songyan.ime.settings.state.ImeStatusSource
import app.songyan.ime.settings.state.ImportedDictionaryInfo
import app.songyan.ime.settings.state.InMemorySettingsPreferences
import app.songyan.ime.settings.state.SettingsAppState
import app.songyan.ime.settings.state.SettingsAppStateSource
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
 * The dictionary import state machine: the gateway runs off the main thread, its result becomes a
 * one-shot screen state and a successful request re-reads app state.
 *
 * Picking a file cannot be covered here because `android.net.Uri` cannot be instantiated in a JVM
 * unit test; it shares [SettingsViewModel.runDictionaryRequest] with the URL path tested below.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelDictionaryImportTest {
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
    fun importedDictionaryComesFromAppState() =
        runTest(dispatcher) {
            val info = ImportedDictionaryInfo(name = "我的词库", entryCount = 12_000, inUse = true)
            val source = FakeSource(SettingsAppState(importedDictionary = info))
            val viewModel = viewModel(source = source)
            assertEquals(info, viewModel.uiState.value.importedDictionary)
        }

    @Test
    fun theChosenSchemePackageReachesTheGatewayForImportAndRemoval() =
        runTest(dispatcher) {
            val stacked =
                ImportedDictionaryInfo("我的词库", 3, inUse = true, targetSourceId = "scheme-1", targetName = "雾凇拼音")
            val gateway = FakeGateway()
            val viewModel =
                viewModel(
                    source = FakeSource(SettingsAppState(stackedDictionaries = listOf(stacked))),
                    gateway = gateway,
                )
            assertEquals(listOf(stacked), viewModel.uiState.value.stackedDictionaries)

            viewModel.importDictionaryFromUrl("https://example.com/words.dict.yaml", target = "scheme-1")
            advanceUntilIdle()
            viewModel.removeImportedDictionary("scheme-1")
            advanceUntilIdle()
            viewModel.removeImportedDictionary()
            advanceUntilIdle()

            assertEquals(listOf("scheme-1", "scheme-1", null), gateway.targets)
        }

    @Test
    fun importFromUrlShowsRunningThenSucceededAndRefreshes() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<DictionaryImportResult>()
            val gateway = FakeGateway(gate = gate)
            val source = FakeSource()
            val viewModel = viewModel(source = source, gateway = gateway)
            val seen = watch(viewModel)

            viewModel.importDictionaryFromUrl("  https://example.com/words.dict.yaml  ")
            runCurrent()
            assertEquals(DictionaryImportUiState.Running(DictionaryImportAction.IMPORT), seen.last())
            // The gateway receives the trimmed link, not the raw field text.
            assertEquals(listOf("https://example.com/words.dict.yaml"), gateway.urls)

            gate.complete(DictionaryImportResult.Success(null))
            advanceUntilIdle()
            assertEquals(DictionaryImportUiState.Succeeded(DictionaryImportAction.IMPORT, null), seen.last())
            assertEquals(1, source.refreshes)
        }

    @Test
    fun aGatewayFailureKeepsItsLocalizedMessageAndDoesNotRefresh() =
        runTest(dispatcher) {
            val gateway = FakeGateway(result = DictionaryImportResult.Failure("词库文件不完整。"))
            val source = FakeSource()
            val viewModel = viewModel(source = source, gateway = gateway)

            viewModel.importDictionaryFromUrl("https://example.com/words.dict.yaml")
            advanceUntilIdle()

            assertEquals(
                DictionaryImportUiState.Failed(
                    DictionaryImportAction.IMPORT,
                    DictionaryImportFailure.GATEWAY,
                    "词库文件不完整。",
                ),
                viewModel.uiState.value.dictionaryImport,
            )
            assertEquals(0, source.refreshes)
        }

    @Test
    fun aFailureWithoutAMessageLeavesTheGenericCopyToTheScreen() =
        runTest(dispatcher) {
            val gateway = FakeGateway(result = DictionaryImportResult.Failure(null))
            val viewModel = viewModel(gateway = gateway)

            viewModel.importDictionaryFromUrl("https://example.com/words.dict.yaml")
            advanceUntilIdle()

            assertEquals(
                DictionaryImportUiState.Failed(DictionaryImportAction.IMPORT, DictionaryImportFailure.GATEWAY, null),
                viewModel.uiState.value.dictionaryImport,
            )
        }

    @Test
    fun linksThatAreNotHttpsNeverReachTheGateway() =
        runTest(dispatcher) {
            val gateway = FakeGateway()
            val viewModel = viewModel(gateway = gateway)

            for (input in listOf("", "   ", "http://example.com/words.dict.yaml", "example.com/words.dict.yaml")) {
                viewModel.importDictionaryFromUrl(input)
                runCurrent()
                assertEquals(
                    DictionaryImportUiState.Failed(
                        DictionaryImportAction.IMPORT,
                        DictionaryImportFailure.INVALID_URL,
                        null,
                    ),
                    viewModel.uiState.value.dictionaryImport,
                )
            }
            assertTrue(gateway.urls.isEmpty())
        }

    @Test
    fun aSecondRequestIsIgnoredWhileTheFirstOneRuns() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<DictionaryImportResult>()
            val gateway = FakeGateway(gate = gate)
            val viewModel = viewModel(gateway = gateway)

            viewModel.importDictionaryFromUrl("https://example.com/a.dict.yaml")
            viewModel.importDictionaryFromUrl("https://example.com/b.dict.yaml")
            runCurrent()
            assertEquals(1, gateway.urls.size)

            gate.complete(DictionaryImportResult.Success(null))
            advanceUntilIdle()
        }

    @Test
    fun removingTheDictionaryUsesTheGatewayAndRefreshes() =
        runTest(dispatcher) {
            val gateway = FakeGateway(result = DictionaryImportResult.Success(null))
            val source = FakeSource()
            val viewModel = viewModel(source = source, gateway = gateway)

            viewModel.removeImportedDictionary()
            advanceUntilIdle()

            assertEquals(1, gateway.removals)
            assertEquals(
                DictionaryImportUiState.Succeeded(DictionaryImportAction.REMOVE, null),
                viewModel.uiState.value.dictionaryImport,
            )
            assertEquals(1, source.refreshes)
        }

    @Test
    fun dismissingAFailureReturnsToIdle() =
        runTest(dispatcher) {
            val gateway = FakeGateway(result = DictionaryImportResult.Failure(null))
            val viewModel = viewModel(gateway = gateway)
            viewModel.importDictionaryFromUrl("https://example.com/words.dict.yaml")
            advanceUntilIdle()

            viewModel.dismissDictionaryImport()
            runCurrent()

            assertEquals(DictionaryImportUiState.Idle, viewModel.uiState.value.dictionaryImport)
        }

    @Test
    fun aRunningRequestCannotBeDismissed() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<DictionaryImportResult>()
            val gateway = FakeGateway(gate = gate)
            val viewModel = viewModel(gateway = gateway)
            viewModel.importDictionaryFromUrl("https://example.com/words.dict.yaml")
            runCurrent()

            viewModel.dismissDictionaryImport()
            runCurrent()

            assertEquals(
                DictionaryImportUiState.Running(DictionaryImportAction.IMPORT),
                viewModel.uiState.value.dictionaryImport,
            )
            gate.complete(DictionaryImportResult.Success(null))
            advanceUntilIdle()
        }

    @Test
    fun anUninstalledGatewayIsReportedAndNeverCalled() =
        runTest(dispatcher) {
            val gateway = FakeGateway(available = false)
            val viewModel = viewModel(gateway = gateway)
            assertFalse(viewModel.uiState.value.dictionaryImportAvailable)

            viewModel.importDictionaryFromUrl("https://example.com/words.dict.yaml")
            runCurrent()

            assertEquals(
                DictionaryImportUiState.Failed(DictionaryImportAction.IMPORT, DictionaryImportFailure.GATEWAY, null),
                viewModel.uiState.value.dictionaryImport,
            )
            assertTrue(gateway.urls.isEmpty())
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
            dictionaryGateway = gateway,
            ioDispatcher = dispatcher,
        )

    private fun TestScope.watch(viewModel: SettingsViewModel): List<DictionaryImportUiState> {
        val seen = mutableListOf<DictionaryImportUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { seen += it.dictionaryImport }
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
        private val result: DictionaryImportResult = DictionaryImportResult.Success(null),
        private val gate: CompletableDeferred<DictionaryImportResult>? = null,
    ) : DictionaryImportGateway {
        val urls = mutableListOf<String>()
        val targets = mutableListOf<String?>()
        var removals: Int = 0
            private set

        override suspend fun importFromUris(
            uris: List<Uri>,
            target: String?,
        ): DictionaryImportResult = reply()

        override suspend fun importFromUrl(
            url: String,
            target: String?,
        ): DictionaryImportResult {
            urls += url
            targets += target
            return reply()
        }

        override suspend fun removeImportedDictionary(target: String?): DictionaryImportResult {
            removals++
            targets += target
            return reply()
        }

        private suspend fun reply(): DictionaryImportResult = gate?.await() ?: result
    }
}
