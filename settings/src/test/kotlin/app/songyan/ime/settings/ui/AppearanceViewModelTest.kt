package app.songyan.ime.settings.ui

import app.songyan.ime.candidate.CandidateBarDefinition
import app.songyan.ime.settings.state.AppearanceFailure
import app.songyan.ime.settings.state.AppearanceSettingsSource
import app.songyan.ime.settings.state.AppearanceSettingsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

class AppearanceViewModelTest {
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
    fun selectingAnAppearanceWritesThroughTheSource() =
        runTest(dispatcher) {
            val source = FakeSource()
            val viewModel = AppearanceViewModel(source)
            viewModel.select("builtin-bamboo")
            advanceUntilIdle()
            assertEquals("builtin-bamboo", source.selected)
            assertNull(viewModel.failure.value)
        }

    @Test
    fun aRejectedSelectionSurfacesAFailureOnce() =
        runTest(dispatcher) {
            val source = FakeSource(selectSucceeds = false)
            val viewModel = AppearanceViewModel(source)
            viewModel.select("missing")
            advanceUntilIdle()
            assertEquals(AppearanceFailure.APPLY_FAILED, viewModel.failure.value)
            viewModel.consumeFailure()
            assertNull(viewModel.failure.value)
        }

    @Test
    fun theWallpaperSwitchIsStoredThroughTheSource() =
        runTest(dispatcher) {
            val source = FakeSource()
            val viewModel = AppearanceViewModel(source)
            viewModel.setDynamicColor(true)
            advanceUntilIdle()
            assertEquals(true, source.dynamicColor)
            assertNull(viewModel.failure.value)
        }

    @Test
    fun refreshAsksTheSourceToReload() =
        runTest(dispatcher) {
            val source = FakeSource()
            val viewModel = AppearanceViewModel(source)
            viewModel.refresh()
            advanceUntilIdle()
            assertEquals(1, source.refreshes)
        }

    @Test
    fun theUiStateIsTheSourceState() =
        runTest(dispatcher) {
            val source = FakeSource()
            val viewModel = AppearanceViewModel(source)
            assertTrue(viewModel.uiState.value.available)
            assertFalse(viewModel.uiState.value.dynamicColor)
        }

    @Test
    fun aCandidateBarEditGoesThroughTheSourceAndAFailureIsReportedOnce() =
        runTest(dispatcher) {
            val source = FakeSource(candidateBarSucceeds = false)
            val viewModel = AppearanceViewModel(source)
            val bar = CandidateBarDefinition.systemDefault
            viewModel.updateCandidateBar { bar }
            advanceUntilIdle()
            assertEquals(bar, source.candidateBar)
            assertEquals(AppearanceFailure.APPLY_FAILED, viewModel.failure.value)
        }

    @Test
    fun consecutiveCandidateBarEditsPreserveBothChanges() =
        runTest(dispatcher) {
            val source = FakeSource()
            val viewModel = AppearanceViewModel(source)
            viewModel.updateCandidateBar { it.copy(collapsed = it.collapsed.copy(height = 56.0)) }
            viewModel.updateCandidateBar { it.copy(expanded = it.expanded.copy(columns = 4)) }
            advanceUntilIdle()
            assertEquals(56.0, viewModel.uiState.value.candidateBar.collapsed.height, 0.0)
            assertEquals(4, viewModel.uiState.value.candidateBar.expanded.columns)
        }

    @Test
    fun aFailedExportOrDeleteHasItsOwnFailure() =
        runTest(dispatcher) {
            val source = FakeSource(fileActionsSucceed = false)
            val viewModel = AppearanceViewModel(source)
            viewModel.delete("mine")
            advanceUntilIdle()
            assertEquals(AppearanceFailure.DELETE_FAILED, viewModel.failure.value)
            assertEquals("mine", source.deleted)
            viewModel.consumeFailure()
            assertNull(viewModel.failure.value)
        }

    private class FakeSource(
        private val candidateBarSucceeds: Boolean = true,
        private val fileActionsSucceed: Boolean = true,
        private val selectSucceeds: Boolean = true,
        private val setDynamicColorSucceeds: Boolean = true,
    ) : AppearanceSettingsSource {
        val mutable = MutableStateFlow(AppearanceSettingsState(available = true))
        override val state: StateFlow<AppearanceSettingsState> = mutable.asStateFlow()

        var refreshes: Int = 0
        var selected: String? = null
        var dynamicColor: Boolean? = null
        var candidateBar: CandidateBarDefinition? = null
        var deleted: String? = null

        override suspend fun updateCandidateBar(bar: CandidateBarDefinition): Boolean {
            candidateBar = bar
            if (candidateBarSucceeds) {
                delay(1)
                mutable.value = mutable.value.copy(candidateBar = bar)
            }
            return candidateBarSucceeds
        }

        override suspend fun delete(id: String): Boolean {
            deleted = id
            return fileActionsSucceed
        }

        override fun refresh() {
            refreshes++
        }

        override suspend fun select(id: String): Boolean {
            selected = id
            return selectSucceeds
        }

        override suspend fun setDynamicColor(enabled: Boolean): Boolean {
            dynamicColor = enabled
            return setDynamicColorSucceeds
        }
    }
}
