package app.songyan.ime.settings.ui

import android.app.Application
import app.songyan.ime.settings.state.ImeStatus
import app.songyan.ime.settings.state.ImeStatusSource
import app.songyan.ime.settings.state.InMemorySettingsAppStateSource
import app.songyan.ime.settings.state.InMemorySettingsPreferences
import app.songyan.ime.settings.state.SettingsAppState
import app.songyan.ime.settings.state.StoredSourceInfo
import app.songyan.ime.settings.state.StoredSourceKind
import app.songyan.ime.settings.state.StoredSourceResult
import app.songyan.ime.settings.state.StoredSourcesGateway
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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

/** Using and deleting stored input schemes from the settings screen. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelStoredSourcesTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeGateway(
        val gate: CompletableDeferred<StoredSourceResult>? = null,
    ) : StoredSourcesGateway {
        override val available: Boolean = true
        val used = mutableListOf<String>()
        val deleted = mutableListOf<String>()

        override suspend fun use(sourceId: String): StoredSourceResult {
            used += sourceId
            return gate?.await() ?: StoredSourceResult.Success
        }

        override suspend fun delete(sourceId: String): StoredSourceResult {
            deleted += sourceId
            return gate?.await() ?: StoredSourceResult.Failure("没能删除")
        }
    }

    private fun viewModel(
        gateway: StoredSourcesGateway,
        source: InMemorySettingsAppStateSource = InMemorySettingsAppStateSource(),
    ) = SettingsViewModel(
        application = Application(),
        appStateSource = source,
        imeStatusSource = ImeStatusSource { ImeStatus(enabled = true, selected = true) },
        preferences = InMemorySettingsPreferences(),
        sourcesGateway = gateway,
        ioDispatcher = dispatcher,
    )

    @Test
    fun theStoredSourcesComeFromAppState() =
        runTest(dispatcher) {
            val sources =
                listOf(
                    StoredSourceInfo("songyan-builtin", "松烟内置", StoredSourceKind.BUILTIN, inUse = false),
                    StoredSourceInfo("scheme-1", "雾凇", StoredSourceKind.SCHEME, inUse = true),
                )
            val viewModel =
                viewModel(FakeGateway(), InMemorySettingsAppStateSource(SettingsAppState(sources = sources)))
            runCurrent()

            assertEquals(sources, viewModel.uiState.value.sources)
            assertFalse("the bundled package cannot be deleted", sources[0].deletable)
            assertTrue(sources[1].deletable)
        }

    @Test
    fun everyStoredPreferenceLoads() =
        runTest(dispatcher) {
            // A screen behind this flag spins until it is true; a reader count that drifts keeps it false.
            val viewModel = viewModel(FakeGateway())
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.preferencesLoaded)
        }

    @Test
    fun hidingTheIdleToolbarIsStoredAndShown() =
        runTest(dispatcher) {
            val viewModel = viewModel(FakeGateway())
            advanceUntilIdle()
            assertFalse("the toolbar shows by default", viewModel.uiState.value.keyboardFeel.hideIdleToolbar)
            viewModel.setHideIdleToolbar(true)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.keyboardFeel.hideIdleToolbar)
        }

    @Test
    fun usingASourceRunsOnceAndReturnsToIdle() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<StoredSourceResult>()
            val gateway = FakeGateway(gate)
            val viewModel = viewModel(gateway)

            viewModel.useSource("scheme-1")
            viewModel.useSource("scheme-2")
            runCurrent()
            assertEquals(StoredSourceUiState.Running("scheme-1"), viewModel.uiState.value.sourceAction)
            assertEquals(listOf("scheme-1"), gateway.used)

            gate.complete(StoredSourceResult.Success)
            advanceUntilIdle()
            assertEquals(StoredSourceUiState.Idle, viewModel.uiState.value.sourceAction)
        }

    @Test
    fun aFailedDeleteShowsItsMessageUntilDismissed() =
        runTest(dispatcher) {
            val gateway = FakeGateway()
            val viewModel = viewModel(gateway)

            viewModel.deleteSource("scheme-1")
            advanceUntilIdle()
            assertEquals(StoredSourceUiState.Failed("没能删除"), viewModel.uiState.value.sourceAction)

            viewModel.dismissSourceAction()
            runCurrent()
            assertEquals(StoredSourceUiState.Idle, viewModel.uiState.value.sourceAction)
        }
}
