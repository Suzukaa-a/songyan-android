package app.songyan.ime.settings.ui

import android.app.Application
import app.songyan.ime.settings.state.ImeStatus
import app.songyan.ime.settings.state.ImeStatusSource
import app.songyan.ime.settings.state.ImportedSchemeInfo
import app.songyan.ime.settings.state.InMemorySettingsAppStateSource
import app.songyan.ime.settings.state.InMemorySettingsPreferences
import app.songyan.ime.settings.state.RecommendedSchemes
import app.songyan.ime.settings.state.SchemeImportGateway
import app.songyan.ime.settings.state.SchemeImportResult
import app.songyan.ime.settings.state.SettingsAppState
import app.songyan.ime.settings.state.StoredSourceInfo
import app.songyan.ime.settings.state.StoredSourceKind
import app.songyan.ime.settings.state.StoredSourceResult
import app.songyan.ime.settings.state.StoredSourceStatus
import app.songyan.ime.settings.state.StoredSourcesGateway
import app.songyan.ime.settings.state.TransferProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/** Scheme packages from a link, their updates and undoing a deletion (P4-1, P4-2). */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelSchemeLinkTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class Schemes : SchemeImportGateway {
        override val available: Boolean = true
        val links = mutableListOf<String>()
        val updates = mutableListOf<String>()
        var result: SchemeImportResult = SchemeImportResult.Success(null)

        override suspend fun importFromUri(uri: android.net.Uri): SchemeImportResult = result

        override suspend fun importFromLink(
            url: String,
            progress: (TransferProgress) -> Unit,
        ): SchemeImportResult {
            links += url
            return result
        }

        override suspend fun update(
            sourceId: String,
            progress: (TransferProgress) -> Unit,
        ): SchemeImportResult {
            updates += sourceId
            return result
        }
    }

    private class Sources(
        var deleteResult: StoredSourceResult = StoredSourceResult.Success,
    ) : StoredSourcesGateway {
        override val available: Boolean = true
        val restored = mutableListOf<String>()

        override suspend fun use(sourceId: String): StoredSourceResult = StoredSourceResult.Success

        override suspend fun delete(sourceId: String): StoredSourceResult = deleteResult

        override suspend fun restore(sourceId: String): StoredSourceResult {
            restored += sourceId
            return StoredSourceResult.Success
        }
    }

    private val sources =
        listOf(
            StoredSourceInfo("scheme-1", "雾凇拼音", StoredSourceKind.SCHEME, inUse = false, updatable = true),
            StoredSourceInfo(
                "scheme-2",
                "已损坏的输入方案",
                StoredSourceKind.SCHEME,
                inUse = false,
                status = StoredSourceStatus.DAMAGED,
            ),
        )

    private fun viewModel(
        schemes: Schemes = Schemes(),
        stored: Sources = Sources(),
    ) = SettingsViewModel(
        application = Application(),
        appStateSource = InMemorySettingsAppStateSource(SettingsAppState(sources = sources)),
        imeStatusSource = ImeStatusSource { ImeStatus(enabled = true, selected = true) },
        preferences = InMemorySettingsPreferences(),
        schemeGateway = schemes,
        sourcesGateway = stored,
        ioDispatcher = dispatcher,
    )

    @Test
    fun aLinkIsTrimmedCheckedAndHandedOverOnlyWhenItIsHttps() =
        runTest(dispatcher) {
            val schemes = Schemes()
            val viewModel = viewModel(schemes)

            viewModel.importSchemeFromLink("http://example.com/p.zip")
            viewModel.importSchemeFromLink("https://user:secret@example.com/p.zip")
            advanceUntilIdle()
            assertEquals(emptyList<String>(), schemes.links)
            assertEquals(SchemeImportUiState.Failed(null), viewModel.uiState.value.schemeImport)

            viewModel.dismissSchemeImport()
            viewModel.importSchemeFromLink("  https://github.com/iDvel/rime-ice  ")
            advanceUntilIdle()
            assertEquals(listOf("https://github.com/iDvel/rime-ice"), schemes.links)
        }

    @Test
    fun anUpdateReportsTheUpdatedPackageAndRetryRunsItAgain() =
        runTest(dispatcher) {
            val schemes = Schemes()
            val info = ImportedSchemeInfo("雾凇拼音", listOf("rime_ice"), inUse = true)
            schemes.result = SchemeImportResult.Failure("连接超时。", retryable = true)
            val viewModel = viewModel(schemes)

            viewModel.updateScheme("scheme-1")
            advanceUntilIdle()
            assertEquals(SchemeImportUiState.Failed("连接超时。", retryable = true), viewModel.uiState.value.schemeImport)

            schemes.result = SchemeImportResult.Success(info)
            viewModel.retrySchemeImport()
            advanceUntilIdle()

            assertEquals(listOf("scheme-1", "scheme-1"), schemes.updates)
            assertEquals(SchemeImportUiState.Succeeded(info, updated = true), viewModel.uiState.value.schemeImport)
        }

    @Test
    fun anUpdateWithNothingNewSaysSo() =
        runTest(dispatcher) {
            val schemes = Schemes()
            val info = ImportedSchemeInfo("雾凇拼音", listOf("rime_ice"), inUse = false)
            schemes.result = SchemeImportResult.UpToDate(info)
            val viewModel = viewModel(schemes)

            viewModel.updateScheme("scheme-1")
            advanceUntilIdle()

            assertEquals(SchemeImportUiState.UpToDate(info), viewModel.uiState.value.schemeImport)
        }

    @Test
    fun aDeletedUsableSchemeCanBeTakenBack() =
        runTest(dispatcher) {
            val stored = Sources()
            val viewModel = viewModel(stored = stored)

            viewModel.deleteSource("scheme-1")
            advanceUntilIdle()
            assertEquals(StoredSourceUiState.Deleted("scheme-1", "雾凇拼音"), viewModel.uiState.value.sourceAction)

            viewModel.dismissSourceAction()
            viewModel.restoreSource("scheme-1")
            advanceUntilIdle()

            assertEquals(listOf("scheme-1"), stored.restored)
            assertEquals(StoredSourceUiState.Idle, viewModel.uiState.value.sourceAction)
        }

    @Test
    fun aDamagedSchemeIsRemovedWithoutAnUndoOffer() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            viewModel.deleteSource("scheme-2")
            advanceUntilIdle()

            assertEquals(StoredSourceUiState.Idle, viewModel.uiState.value.sourceAction)
            assertFalse(sources[1].usable)
            assertTrue(sources[1].deletable)
        }

    @Test
    fun aFailedDeleteOffersNoUndo() =
        runTest(dispatcher) {
            val viewModel = viewModel(stored = Sources(StoredSourceResult.Failure("没能删除")))

            viewModel.deleteSource("scheme-1")
            advanceUntilIdle()

            assertEquals(StoredSourceUiState.Failed("没能删除"), viewModel.uiState.value.sourceAction)
        }

    @Test
    fun schemeLinksKeepToHttpsWithoutCredentials() {
        assertEquals("https://github.com/o/r", normalizeSchemeUrl(" https://github.com/o/r "))
        assertNull(normalizeSchemeUrl("https://a:b@github.com/o/r"))
        assertNull(normalizeSchemeUrl("https://github.com@evil.example/o/r"))
        assertNull(normalizeSchemeUrl("http://github.com/o/r"))
        assertNull(normalizeSchemeUrl("github.com/o/r"))
    }

    @Test
    fun everyRecommendedArchiveIsAnHttpsLinkWithoutCredentials() {
        val catalog = RecommendedSchemes.catalog
        assertTrue(catalog.all { scheme -> scheme.archives.all { normalizeSchemeUrl(it.url) == it.url } })
    }
}
