package app.songyan.ime.settings.ui

import app.songyan.ime.layout.ShuangpinHintPreset
import app.songyan.ime.layout.ShuangpinHintPresets
import app.songyan.ime.layout.ShuangpinHintProblem
import app.songyan.ime.layout.ShuangpinHintSnapshot
import app.songyan.ime.settings.state.InMemorySettingsPreferences
import app.songyan.ime.settings.state.LinkDownloadGateway
import app.songyan.ime.settings.state.LinkDownloadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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

class ShuangpinHintViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val mine = ShuangpinHintPreset(id = "mine", name = "我的", hints = mapOf('q' to "iq"))

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun turningHintsOnStoresADocumentTheKeyboardReads() =
        runTest(dispatcher) {
            val preferences = InMemorySettingsPreferences()
            val viewModel = ShuangpinHintViewModel(preferences, ioDispatcher = dispatcher)
            advanceUntilIdle()
            assertNull(preferences.shuangpinHintDocument.first())
            viewModel.setEnabled(true)
            advanceUntilIdle()
            val stored = ShuangpinHintSnapshot.decode(preferences.shuangpinHintDocument.first()!!)
            assertEquals(ShuangpinHintPresets.builtinXiaohe.hints, stored.activePreset?.hints)
            assertTrue(viewModel.uiState.value.snapshot.enabled)
        }

    @Test
    fun aSavedPresetCanBeChosenAndDeletingItTurnsHintsOff() =
        runTest(dispatcher) {
            val preferences = InMemorySettingsPreferences()
            val viewModel = ShuangpinHintViewModel(preferences, ioDispatcher = dispatcher)
            advanceUntilIdle()
            viewModel.save(mine)
            viewModel.select("mine")
            viewModel.setEnabled(true)
            advanceUntilIdle()
            assertEquals(
                mapOf('q' to "iq"),
                viewModel.uiState.value.snapshot.activePreset
                    ?.hints,
            )
            viewModel.delete("mine")
            advanceUntilIdle()
            val after = viewModel.uiState.value.snapshot
            assertFalse(after.enabled)
            assertEquals(ShuangpinHintPresets.BUILTIN_ID, after.selectedPresetId)
            assertEquals(listOf(ShuangpinHintPresets.BUILTIN_ID), after.presets.map { it.id })
        }

    @Test
    fun aRefusedEditWritesNothingAndSaysWhy() =
        runTest(dispatcher) {
            val preferences = InMemorySettingsPreferences()
            val viewModel = ShuangpinHintViewModel(preferences, ioDispatcher = dispatcher)
            advanceUntilIdle()
            viewModel.save(mine.copy(hints = mapOf('q' to "x".repeat(17))))
            advanceUntilIdle()
            assertNull(preferences.shuangpinHintDocument.first())
            val message = viewModel.message.value as ShuangpinHintMessage.Refused
            assertEquals(ShuangpinHintProblem.HINT_TOO_LONG, message.error.problem)
        }

    @Test
    fun damagedSettingsAreKeptUntilAnExplicitReset() =
        runTest(dispatcher) {
            val preferences = InMemorySettingsPreferences()
            preferences.setShuangpinHintDocument("{\"broken\": true}")
            val viewModel = ShuangpinHintViewModel(preferences, ioDispatcher = dispatcher)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.damaged)
            viewModel.setEnabled(true)
            advanceUntilIdle()
            assertEquals("{\"broken\": true}", preferences.shuangpinHintDocument.first())
            viewModel.reset()
            advanceUntilIdle()
            assertNull(preferences.shuangpinHintDocument.first())
            assertFalse(viewModel.uiState.value.damaged)
        }

    @Test
    fun importingIsAdditiveAndATakenIdComesInAsACopy() {
        val base = ShuangpinHintSnapshot(presets = listOf(ShuangpinHintPresets.builtinXiaohe, mine))
        val (next, imported) = ShuangpinHintEdits.import(base, mine.encoded(), "（导入）")
        assertEquals(3, next.presets.size)
        assertEquals("我的（导入）", imported.name)
        assertTrue(imported.id != mine.id)
        assertEquals(mine.hints, imported.hints)
    }

    @Test
    fun theBuiltinPresetCannotBeDeleted() {
        val base = ShuangpinHintSnapshot()
        assertEquals(base, ShuangpinHintEdits.delete(base, ShuangpinHintPresets.BUILTIN_ID))
    }

    private class Downloads(
        private val result: LinkDownloadResult,
    ) : LinkDownloadGateway {
        val links = mutableListOf<String>()
        override val available = true

        override suspend fun download(
            url: String,
            maxBytes: Long,
        ): LinkDownloadResult {
            links += url
            return result
        }
    }

    @Test
    fun aPresetFromALinkIsAddedLikeAPickedFile() =
        runTest(dispatcher) {
            val preferences = InMemorySettingsPreferences()
            val downloads = Downloads(LinkDownloadResult.Downloaded(mine.encoded().encodeToByteArray()))
            val viewModel = ShuangpinHintViewModel(preferences, downloads, dispatcher)
            advanceUntilIdle()
            viewModel.importLink(" https://example.com/mine.json ", "（导入）")
            assertTrue(viewModel.uiState.value.downloading)
            advanceUntilIdle()
            assertEquals(listOf("https://example.com/mine.json"), downloads.links)
            assertFalse(viewModel.uiState.value.downloading)
            assertEquals(ShuangpinHintMessage.Imported("我的"), viewModel.message.value)
            assertEquals(
                mine.hints,
                viewModel.uiState.value.snapshot
                    .preset("mine")
                    ?.hints,
            )
        }

    @Test
    fun aLinkThatIsNotHttpsIsNotFetchedAndAnOversizedFileIsRefused() =
        runTest(dispatcher) {
            val downloads = Downloads(LinkDownloadResult.Failure(null, tooLarge = true))
            val viewModel = ShuangpinHintViewModel(InMemorySettingsPreferences(), downloads, dispatcher)
            advanceUntilIdle()
            viewModel.importLink("http://example.com/mine.json", "（导入）")
            assertEquals(ShuangpinHintMessage.LinkInvalid, viewModel.message.value)
            assertTrue(downloads.links.isEmpty())

            viewModel.consumeMessage()
            viewModel.importLink("https://example.com/huge.json", "（导入）")
            advanceUntilIdle()
            val refused = viewModel.message.value as ShuangpinHintMessage.Refused
            assertEquals(ShuangpinHintProblem.TOO_LARGE, refused.error.problem)
        }
}
