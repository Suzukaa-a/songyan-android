package app.songyan.ime.settings.ui

import app.songyan.ime.layout.T9QuickSymbolStore
import app.songyan.ime.settings.state.InMemorySettingsPreferences
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

class T9QuickSymbolsViewModelTest {
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
    fun theRulesMatchTheIosEditor() {
        assertEquals(T9QuickSymbolsProblem.INVALID, T9QuickSymbolsDraft.check(""))
        assertEquals(T9QuickSymbolsProblem.INVALID, T9QuickSymbolsDraft.check("，。 ？"))
        assertEquals(T9QuickSymbolsProblem.INVALID, T9QuickSymbolsDraft.check("一二三四五六七八九十甲乙丙"))
        assertEquals(T9QuickSymbolsProblem.DUPLICATE, T9QuickSymbolsDraft.check("，。，"))
        assertNull(T9QuickSymbolsDraft.check("一二三四五六七八九十甲乙"))
        assertNull(T9QuickSymbolsDraft.check("，"))
    }

    @Test
    fun thePreviewSplitsTheListIntoTheSideRailAndTheFirstKeyCycle() {
        val (rail, cycle) = T9QuickSymbolsDraft.preview("；：")
        assertEquals(listOf("；", "：", "，"), rail)
        assertEquals("。？！、…", cycle.take(5).joinToString(""))
    }

    @Test
    fun aValidListIsStoredAndAnInvalidOneIsNot() =
        runTest(dispatcher) {
            val preferences = InMemorySettingsPreferences()
            val viewModel = T9QuickSymbolsViewModel(preferences)
            advanceUntilIdle()

            assertTrue(viewModel.save("；：，"))
            advanceUntilIdle()
            assertEquals("；：，", preferences.t9QuickSymbols.first())
            assertEquals("；：，", viewModel.uiState.value.effective)

            assertFalse(viewModel.save("，，"))
            advanceUntilIdle()
            assertEquals("；：，", preferences.t9QuickSymbols.first())
        }

    @Test
    fun savingTheDefaultSequenceStoresNothingAndRestoreClearsTheChoice() =
        runTest(dispatcher) {
            val preferences = InMemorySettingsPreferences()
            val viewModel = T9QuickSymbolsViewModel(preferences)
            advanceUntilIdle()
            assertEquals(T9QuickSymbolStore.DEFAULT_SYMBOLS, viewModel.uiState.value.effective)

            viewModel.save("；")
            advanceUntilIdle()
            viewModel.save(T9QuickSymbolStore.DEFAULT_SYMBOLS)
            advanceUntilIdle()
            assertEquals("", preferences.t9QuickSymbols.first())

            viewModel.save("；")
            advanceUntilIdle()
            viewModel.restoreDefault()
            advanceUntilIdle()
            assertEquals("", preferences.t9QuickSymbols.first())
            assertEquals(T9QuickSymbolStore.DEFAULT_SYMBOLS, viewModel.uiState.value.effective)
        }
}
