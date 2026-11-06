package app.songyan.ime.settings.ui

import android.app.Application
import app.songyan.ime.keyboard.engine.InputSchemeFamily
import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.KeyboardComposition
import app.songyan.ime.layout.KeyboardCompositionKey
import app.songyan.ime.layout.LayoutInput
import app.songyan.ime.layout.LayoutRegistry
import app.songyan.ime.settings.R
import app.songyan.ime.settings.state.CurrentMainLayout
import app.songyan.ime.settings.state.ImeStatus
import app.songyan.ime.settings.state.ImeStatusSource
import app.songyan.ime.settings.state.InMemoryLayoutLibrarySource
import app.songyan.ime.settings.state.InMemorySettingsAppStateSource
import app.songyan.ime.settings.state.InMemorySettingsPreferences
import app.songyan.ime.settings.state.InstalledScheme
import app.songyan.ime.settings.state.LayoutDisplayName
import app.songyan.ime.settings.state.SettingsAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The settings state names the keyboard in use by the main layout it actually draws. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelMainLayoutTest {
    private val dispatcher = StandardTestDispatcher()
    private val bopomofo = KeyboardCompositionKey("bopomofo_tw", LayoutInput.Builtin.QWERTY26)
    private val mine = BuiltinLayouts.zhuyin41.copyWithId("mine", "我的注音")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun registry(vararg compositions: Pair<KeyboardCompositionKey, String>): LayoutRegistry {
        var result = LayoutRegistry.create(layouts = BuiltinLayouts.all + mine)
        for ((key, mainLayoutId) in compositions) {
            result = result.withComposition(KeyboardComposition(mainLayoutID = mainLayoutId), key.rawValue)
        }
        return result
    }

    private fun viewModel(
        registry: LayoutRegistry,
        layoutId: String = "qwerty26",
    ): SettingsViewModel {
        val preferences = InMemorySettingsPreferences(schemaId = "bopomofo_tw", layoutId = layoutId)
        val source =
            InMemorySettingsAppStateSource(
                SettingsAppState(
                    schemes =
                        listOf(
                            InstalledScheme("bopomofo_tw", "注音", family = InputSchemeFamily.ZHUYIN),
                        ),
                    inUseSchemeId = "bopomofo_tw",
                ),
            )
        return SettingsViewModel(
            application = Application(),
            appStateSource = source,
            imeStatusSource = ImeStatusSource { ImeStatus(enabled = true, selected = true) },
            preferences = preferences,
            layoutLibrary = InMemoryLayoutLibrarySource(registry),
            ioDispatcher = dispatcher,
        )
    }

    @Test
    fun `a saved physical pairing names the keyboard`() =
        runTest(dispatcher) {
            val viewModel = viewModel(registry(bopomofo to "zhuyin41"))
            advanceUntilIdle()
            assertEquals(
                CurrentMainLayout("zhuyin41", LayoutDisplayName.Worded(R.string.layout_name_zhuyin41)),
                viewModel.uiState.value.mainLayout,
            )
        }

    @Test
    fun `without a saved pairing the family preferred board names the keyboard`() =
        runTest(dispatcher) {
            val viewModel = viewModel(LayoutRegistry.create())
            advanceUntilIdle()
            assertEquals(
                "zhuyin41",
                viewModel.uiState.value.mainLayout
                    ?.id,
            )
        }

    @Test
    fun `a saved 26-key pairing keeps the 26-key name`() =
        runTest(dispatcher) {
            val viewModel = viewModel(registry(bopomofo to "qwerty26"))
            advanceUntilIdle()
            assertEquals(
                CurrentMainLayout("qwerty26", LayoutDisplayName.Worded(R.string.layout_name_26)),
                viewModel.uiState.value.mainLayout,
            )
        }

    @Test
    fun `an author layout keeps its own name in the settings state`() =
        runTest(dispatcher) {
            val viewModel = viewModel(registry(bopomofo to "mine"))
            advanceUntilIdle()
            assertEquals(
                CurrentMainLayout("mine", LayoutDisplayName.Authored("我的注音")),
                viewModel.uiState.value.mainLayout,
            )
        }
}
