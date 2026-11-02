package app.songyan.ime.settings.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import app.songyan.ime.settings.state.DictionaryImportGateway
import app.songyan.ime.settings.state.DictionaryImportResult
import app.songyan.ime.settings.state.ImeStatus
import app.songyan.ime.settings.state.ImeStatusSource
import app.songyan.ime.settings.state.InMemorySettingsAppStateSource
import app.songyan.ime.settings.state.InMemorySettingsPreferences
import app.songyan.ime.settings.state.SchemeImportGateway
import app.songyan.ime.settings.state.SchemeImportResult
import app.songyan.ime.settings.state.StoredSourcesHolder
import app.songyan.ime.settings.state.TransferProgress
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsImportRecoveryTest {
    private val dispatcher = StandardTestDispatcher()
    private val appState = InMemorySettingsAppStateSource()
    private val schemes = Schemes()
    private val dictionaries = Dictionaries()
    private lateinit var tasks: SettingsImportTasks

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        tasks = SettingsImportTasks(appState, dictionaries, schemes, StoredSourcesHolder.current, dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() =
        SettingsViewModel(
            application = Application(),
            appStateSource = appState,
            imeStatusSource = ImeStatusSource { ImeStatus(true, true) },
            preferences = InMemorySettingsPreferences(),
            dictionaryGateway = dictionaries,
            schemeGateway = schemes,
            ioDispatcher = dispatcher,
            importTasks = tasks,
        )

    private fun close(viewModel: SettingsViewModel) {
        ViewModelStore().apply {
            put("settings", viewModel)
            clear()
        }
    }

    @Test
    fun replacementScreenKeepsProgressAndCancelsTheOriginalRequest() =
        runTest(dispatcher) {
            val first = viewModel()
            first.importSchemeFromLink("https://example.com/package.zip")
            runCurrent()
            schemes.progress(TransferProgress(25, 100))
            close(first)

            val replacement = viewModel()
            assertEquals(SchemeImportUiState.Running(TransferProgress(25, 100)), replacement.uiState.value.schemeImport)
            replacement.importSchemeFromLink("https://example.com/other.zip")
            replacement.importSchemeFromLink("invalid")
            replacement.retrySchemeImport()
            runCurrent()
            assertEquals(listOf("https://example.com/package.zip"), schemes.links)

            replacement.cancelSchemeImport()
            assertTrue((tasks.schemeImport.value as SchemeImportUiState.Running).cancelling)
            replacement.cancelSchemeImport()
            advanceUntilIdle()
            assertEquals(1, schemes.cancellations)
            assertEquals(SchemeImportUiState.Failed(null), replacement.uiState.value.schemeImport)
        }

    @Test
    fun resultAndRetrySurviveAClosedScreenWithoutReplayingOnRecreation() =
        runTest(dispatcher) {
            val first = viewModel()
            first.updateScheme("scheme-original")
            runCurrent()
            close(first)
            schemes.result.complete(SchemeImportResult.Failure("offline", retryable = true))
            advanceUntilIdle()

            val replacement = viewModel()
            assertEquals(SchemeImportUiState.Failed("offline", true), replacement.uiState.value.schemeImport)
            assertEquals(listOf("scheme-original"), schemes.updates)
            schemes.result = CompletableDeferred(SchemeImportResult.Success(null))
            replacement.retrySchemeImport()
            advanceUntilIdle()
            assertEquals(listOf("scheme-original", "scheme-original"), schemes.updates)
            assertEquals(SchemeImportUiState.Succeeded(null, updated = true), replacement.uiState.value.schemeImport)

            close(replacement)
            val third = viewModel()
            assertEquals(SchemeImportUiState.Succeeded(null, updated = true), third.uiState.value.schemeImport)
            third.dismissSchemeImport()
            runCurrent()
            assertEquals(SchemeImportUiState.Idle, third.uiState.value.schemeImport)
        }

    @Test
    fun dictionaryRetryRetainsItsTargetAfterTheOriginalScreenCloses() =
        runTest(dispatcher) {
            val first = viewModel()
            first.importDictionaryFromUrl("https://example.com/words.dict.yaml", "scheme-target")
            runCurrent()
            close(first)
            val replacement = viewModel()
            assertTrue(replacement.uiState.value.dictionaryImport is DictionaryImportUiState.Running)
            replacement.cancelDictionaryImport()
            advanceUntilIdle()
            assertEquals(1, dictionaries.cancellations)

            dictionaries.result = CompletableDeferred(DictionaryImportResult.Success(null))
            replacement.retryDictionaryImport()
            advanceUntilIdle()
            assertEquals(listOf("scheme-target", "scheme-target"), dictionaries.targets)
            assertTrue(replacement.uiState.value.dictionaryImport is DictionaryImportUiState.Succeeded)
        }

    private class Schemes : SchemeImportGateway {
        override val available = true
        val links = mutableListOf<String>()
        val updates = mutableListOf<String>()
        var result = CompletableDeferred<SchemeImportResult>()
        var progress: (TransferProgress) -> Unit = {}
        var cancellations = 0

        override suspend fun importFromUri(uri: Uri): SchemeImportResult = result.await()

        override suspend fun importFromLink(
            url: String,
            progress: (TransferProgress) -> Unit,
        ): SchemeImportResult {
            links += url
            this.progress = progress
            return result.await()
        }

        override suspend fun update(
            sourceId: String,
            progress: (TransferProgress) -> Unit,
        ): SchemeImportResult {
            updates += sourceId
            return result.await()
        }

        override fun cancelRunning() {
            cancellations++
            result.complete(SchemeImportResult.Failure(null))
        }
    }

    private class Dictionaries : DictionaryImportGateway {
        override val available = true
        val targets = mutableListOf<String?>()
        var result = CompletableDeferred<DictionaryImportResult>()
        var cancellations = 0

        override suspend fun importFromUris(
            uris: List<Uri>,
            target: String?,
        ): DictionaryImportResult = result.await()

        override suspend fun importFromUrl(
            url: String,
            target: String?,
        ): DictionaryImportResult {
            targets += target
            return result.await()
        }

        override suspend fun removeImportedDictionary(target: String?): DictionaryImportResult = result.await()

        override fun cancelRunning() {
            cancellations++
            result.complete(DictionaryImportResult.Failure(null, retryable = true))
        }
    }
}
