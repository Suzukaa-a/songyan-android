package app.songyan.ime.settings.ui

import app.songyan.ime.script.ScriptEntry
import app.songyan.ime.script.ScriptHeader
import app.songyan.ime.script.ScriptRegistry
import app.songyan.ime.script.ScriptRegistryUnavailable
import app.songyan.ime.script.ScriptRunOutcome
import app.songyan.ime.script.ScriptSourceOrigin
import app.songyan.ime.settings.state.InMemorySettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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

/**
 * 设置 → 语音输入: only stored scripts that declare audio input and can run on this host are offered,
 * and the switch and the pick go through the preferences the keyboard reads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceInputViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val registry = FakeRegistry()
    private val preferences = InMemorySettingsPreferences()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun script(
        id: String,
        needsAudio: Boolean = true,
        enabled: Boolean = true,
        pending: Boolean = false,
        available: Boolean = true,
        order: Int = 0,
    ): ScriptEntry =
        ScriptEntry(
            id = id,
            name = id,
            description = null,
            enabled = enabled,
            order = order,
            apiVersion = 1,
            connects = emptyList(),
            needsAudio = needsAudio,
            availableOnThisHost = available,
            confirmationPending = pending,
            settings = emptyList(),
            sourceOrigin = ScriptSourceOrigin.Pasted,
        )

    @Test
    fun onlyEnabledConfirmedAudioScriptsThatCanRunHereAreOffered() =
        runTest(dispatcher) {
            registry.store(
                script("keep", order = 2),
                script("first", order = 1),
                script("text-only", needsAudio = false),
                script("disabled", enabled = false),
                script("pending", pending = true),
                script("unavailable", available = false),
            )
            val model = VoiceInputViewModel(registry, preferences)

            advanceUntilIdle()

            assertFalse(model.uiState.value.loading)
            assertEquals(
                listOf("first", "keep"),
                model.uiState.value.scripts
                    .map { it.id },
            )
        }

    @Test
    fun theSwitchAndThePickReachTheSharedPreferences() =
        runTest(dispatcher) {
            registry.store(script("keep"))
            val model = VoiceInputViewModel(registry, preferences)
            advanceUntilIdle()

            model.setEnabled(true)
            model.selectScript("keep")
            advanceUntilIdle()

            assertEquals(true, preferences.voiceInputEnabled.first())
            assertEquals("keep", preferences.voiceInputScriptId.first())
            assertTrue(model.uiState.value.enabled)
            assertEquals("keep", model.uiState.value.selectedScriptId)
        }

    @Test
    fun aStoredPickIsShownWithoutTouchingTheSwitch() =
        runTest(dispatcher) {
            registry.store(script("keep"))
            preferences.setVoiceInputScriptId("keep")
            val model = VoiceInputViewModel(registry, preferences)

            advanceUntilIdle()

            assertFalse("voice input stays off until the user turns it on", model.uiState.value.enabled)
            assertEquals("keep", model.uiState.value.selectedScriptId)
        }

    /** The catalog stands in for the proxy: the page only reads entries and reloads them. */
    private class FakeRegistry : ScriptRegistry {
        private var stored: List<ScriptEntry> = emptyList()
        private var reloadCount = 0

        val reloads: Int get() = reloadCount

        fun store(vararg entries: ScriptEntry) {
            stored = entries.toList()
        }

        override val entries: List<ScriptEntry> get() = stored

        override val refreshing: Boolean = false

        override suspend fun reload() {
            reloadCount += 1
        }

        override suspend fun inspect(source: ByteArray): ScriptHeader = throw ScriptRegistryUnavailable()

        override suspend fun add(
            source: ByteArray,
            origin: ScriptSourceOrigin,
        ): ScriptEntry = throw ScriptRegistryUnavailable()

        override suspend fun addFromLink(url: String): ScriptEntry = throw ScriptRegistryUnavailable()

        override suspend fun confirm(
            id: String,
            settings: Map<String, String>,
        ): ScriptEntry = throw ScriptRegistryUnavailable()

        override suspend fun updateSettings(
            id: String,
            settings: Map<String, String>,
        ): ScriptEntry = throw ScriptRegistryUnavailable()

        override suspend fun setEnabled(
            id: String,
            enabled: Boolean,
        ): ScriptEntry = throw ScriptRegistryUnavailable()

        override suspend fun reorder(orderedIds: List<String>) = throw ScriptRegistryUnavailable()

        override suspend fun source(id: String): ByteArray? = null

        override suspend fun export(id: String): ByteArray? = null

        override suspend fun checkUpdate(id: String): ScriptEntry = throw ScriptRegistryUnavailable()

        override suspend fun delete(id: String) = throw ScriptRegistryUnavailable()

        override suspend fun runTrial(
            id: String,
            text: String,
        ): ScriptRunOutcome = ScriptRunOutcome.Failed("unavailable", "", 0)

        override suspend fun run(
            id: String,
            text: String,
        ): ScriptRunOutcome = ScriptRunOutcome.Failed("unavailable", "", 0)

        override suspend fun runAudio(
            id: String,
            wav: ByteArray,
        ): ScriptRunOutcome = ScriptRunOutcome.Failed("unavailable", "", 0)

        override suspend fun cancel(id: String) = Unit
    }
}
