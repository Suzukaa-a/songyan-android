package app.songyan.ime.settings.ui

import app.songyan.ime.keyboard.engine.EngineOptionKind
import app.songyan.ime.settings.state.EngineOptionSetting
import app.songyan.ime.settings.state.EngineOptionsGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EngineOptionsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class Fake(
        var saves: Boolean = true,
    ) : EngineOptionsGateway {
        val stored = mutableMapOf("luna" to mutableMapOf("traditionalize" to false, "emoji" to true))
        val sets = mutableListOf<String>()

        override suspend fun load(schemaId: String): List<EngineOptionSetting> =
            stored[schemaId]
                .orEmpty()
                .map { (name, on) ->
                    EngineOptionSetting(
                        name,
                        if (name == "emoji") EngineOptionKind.EMOJI else EngineOptionKind.TRADITIONAL,
                        on,
                    )
                }

        override suspend fun set(
            schemaId: String,
            name: String,
            on: Boolean,
        ): Boolean {
            sets += "$schemaId $name $on"
            if (saves) stored.getValue(schemaId)[name] = on
            return saves
        }
    }

    @Test
    fun selectingACharacterFormShowsOnlyThatChoiceAndRestoresItIfSavingFails() =
        runTest(dispatcher) {
            val names = listOf("s2s", "s2t")
            val original =
                names.mapIndexed { index, name ->
                    EngineOptionSetting(name, EngineOptionKind.TRADITIONAL, index == 0, names, name)
                }
            val gateway =
                object : EngineOptionsGateway {
                    override suspend fun load(schemaId: String) = original

                    override suspend fun set(
                        schemaId: String,
                        name: String,
                        on: Boolean,
                    ) = false
                }
            val model = EngineOptionsViewModel(gateway)
            model.show("pinyin")
            advanceUntilIdle()
            model.set("s2t", true)
            assertEquals(
                listOf("s2t"),
                model.uiState.value.options
                    .filter { it.on }
                    .map { it.name },
            )
            advanceUntilIdle()
            assertEquals(original, model.uiState.value.options)
            assertTrue(model.uiState.value.saveFailed)
        }

    @Test
    fun showingAnInputMethodListsItsSwitches() =
        runTest(dispatcher) {
            val model = EngineOptionsViewModel(Fake())
            model.show("luna")
            advanceUntilIdle()
            assertEquals(
                listOf("traditionalize", "emoji"),
                model.uiState.value.options
                    .map { it.name },
            )
        }

    @Test
    fun anInputMethodWithoutSwitchesShowsNone() =
        runTest(dispatcher) {
            val model = EngineOptionsViewModel(Fake())
            model.show("bopomofo")
            advanceUntilIdle()
            assertTrue(
                model.uiState.value.options
                    .isEmpty(),
            )
        }

    @Test
    fun aChangeIsStoredAndShown() =
        runTest(dispatcher) {
            val gateway = Fake()
            val model = EngineOptionsViewModel(gateway)
            model.show("luna")
            advanceUntilIdle()
            model.set("traditionalize", true)
            // Shown at once, before the store answers.
            assertTrue(
                model.uiState.value.options
                    .first { it.name == "traditionalize" }
                    .on,
            )
            advanceUntilIdle()
            assertEquals(listOf("luna traditionalize true"), gateway.sets)
            assertTrue(
                model.uiState.value.options
                    .first { it.name == "traditionalize" }
                    .on,
            )
            assertFalse(model.uiState.value.saveFailed)
        }

    @Test
    fun aChangeThatCouldNotBeSavedShowsWhatIsReallyStored() =
        runTest(dispatcher) {
            val model = EngineOptionsViewModel(Fake(saves = false))
            model.show("luna")
            advanceUntilIdle()
            model.set("traditionalize", true)
            advanceUntilIdle()
            assertFalse(
                model.uiState.value.options
                    .first { it.name == "traditionalize" }
                    .on,
            )
            assertTrue(model.uiState.value.saveFailed)
        }

    @Test
    fun anAnswerForAnInputMethodLeftBehindIsDropped() =
        runTest(dispatcher) {
            val model = EngineOptionsViewModel(Fake())
            model.show("luna")
            model.show("bopomofo")
            advanceUntilIdle()
            assertTrue(
                model.uiState.value.options
                    .isEmpty(),
            )
        }
}
