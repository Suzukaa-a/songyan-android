package app.songyan.ime.settings.ui

import android.content.Intent
import app.songyan.ime.settings.state.RimeConfigApplyResult
import app.songyan.ime.settings.state.RimeConfigEditResult
import app.songyan.ime.settings.state.RimeConfigFileResult
import app.songyan.ime.settings.state.RimeConfigGateway
import app.songyan.ime.settings.state.RimeConfigInfo
import app.songyan.ime.settings.state.RimeConfigSaveResult
import app.songyan.ime.settings.state.RimeEditTarget
import app.songyan.ime.settings.state.RimeScriptCreateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The notices around the user's changed files: told once, after applying, and acknowledged. */
class RimeConfigViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private class FakeGateway : RimeConfigGateway {
        var unacknowledged = listOf("lua/date.lua")
        val acknowledged = mutableListOf<String>()
        val restored = mutableListOf<String>()
        var applyResult: RimeConfigApplyResult = RimeConfigApplyResult.Applied
        var scripts = emptyList<String>()
        var firstTime = true
        val applyCalls = mutableListOf<List<String>>()

        override val available = true

        override fun openFolderIntents(): List<Intent> = emptyList()

        override suspend fun info() = RimeConfigInfo(fileCount = 1, pending = true, unacknowledged = unacknowledged)

        override suspend fun apply(confirmed: List<String>): RimeConfigApplyResult {
            applyCalls.add(confirmed)
            return if (scripts.isNotEmpty() && confirmed != scripts.map { "$it@hash" }) {
                RimeConfigApplyResult.NeedsConfirmation(scripts, firstTime, scripts.map { "$it@hash" })
            } else {
                applyResult
            }
        }

        override suspend fun restore(path: String) {
            restored += path
        }

        override suspend fun acknowledge(paths: List<String>) {
            acknowledged += paths
            unacknowledged = unacknowledged - paths.toSet()
        }

        override suspend fun loadFile(
            key: String,
            grammarExample: Boolean,
        ): RimeConfigFileResult = RimeConfigFileResult.Failure(null)

        override suspend fun createScript(name: String): RimeScriptCreateResult =
            if (name ==
                "taken"
            ) {
                RimeScriptCreateResult.Failure("taken")
            } else {
                RimeScriptCreateResult.Created(RimeEditTarget.scriptKey(name))
            }

        override suspend fun saveFile(
            key: String,
            text: String,
        ): RimeConfigSaveResult = RimeConfigSaveResult.Saved

        override suspend fun externalEditIntents(key: String): RimeConfigEditResult = RimeConfigEditResult.Failure(null)
    }

    private val gateway = FakeGateway()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun theUnseenOverridesAreToldRightAfterApplyingAndOnlyOnce() =
        runTest(dispatcher) {
            val model = RimeConfigViewModel(gateway)
            model.refresh()
            advanceUntilIdle()
            assertNull("not before applying", model.uiState.value.overrideNotice)

            model.apply()
            advanceUntilIdle()
            assertEquals(listOf("lua/date.lua"), model.uiState.value.overrideNotice)

            model.dismissOverrideNotice()
            advanceUntilIdle()
            assertEquals(listOf("lua/date.lua"), gateway.acknowledged)
            assertNull(model.uiState.value.overrideNotice)

            model.apply()
            advanceUntilIdle()
            assertNull("told once per file", model.uiState.value.overrideNotice)
        }

    @Test
    fun aFailedApplyTellsNothing() =
        runTest(dispatcher) {
            gateway.applyResult = RimeConfigApplyResult.Failure(null)
            val model = RimeConfigViewModel(gateway)
            model.refresh()
            advanceUntilIdle()

            model.apply()
            advanceUntilIdle()

            assertNull(model.uiState.value.overrideNotice)
        }

    @Test
    fun newOrChangedScriptsWaitForAYesAndCancelAppliesNothing() =
        runTest(dispatcher) {
            gateway.scripts = listOf("lua/date.lua")
            val model = RimeConfigViewModel(gateway)
            model.refresh()
            advanceUntilIdle()

            model.apply()
            advanceUntilIdle()
            assertEquals(
                ScriptConfirmation(listOf("lua/date.lua"), firstTime = true, keys = listOf("lua/date.lua@hash")),
                model.uiState.value.scriptConfirmation,
            )
            assertEquals("not applied", listOf(emptyList<String>()), gateway.applyCalls)
            assertNull(model.uiState.value.message)

            model.cancelScripts()
            assertNull(model.uiState.value.scriptConfirmation)
            assertEquals("cancelling asks nothing more", listOf(emptyList<String>()), gateway.applyCalls)

            model.apply()
            advanceUntilIdle()
            model.confirmScripts()
            advanceUntilIdle()
            assertEquals(listOf(emptyList(), emptyList(), listOf("lua/date.lua@hash")), gateway.applyCalls)
            assertNull(model.uiState.value.scriptConfirmation)
            assertEquals(RimeConfigMessage.Applied, model.uiState.value.message)

            // Once the risk was accepted, a later change is a short question.
            gateway.firstTime = false
            model.apply()
            advanceUntilIdle()
            assertEquals(
                false,
                model.uiState.value.scriptConfirmation
                    ?.firstTime,
            )
        }

    @Test
    fun restoringAFileAsksTheGateway() =
        runTest(dispatcher) {
            val model = RimeConfigViewModel(gateway)
            model.restore("lua/date.lua")
            advanceUntilIdle()
            assertEquals(listOf("lua/date.lua"), gateway.restored)
        }

    @Test
    fun aNewScriptOpensInTheEditorAndABadNameDoesNothing() =
        runTest(dispatcher) {
            val model = RimeConfigViewModel(gateway)

            model.createScript("Bad Name")
            advanceUntilIdle()
            assertNull(model.uiState.value.openEditor)

            model.createScript("taken")
            advanceUntilIdle()
            assertEquals(RimeConfigMessage.Failure("taken"), model.uiState.value.message)

            model.createScript("my_filter")
            advanceUntilIdle()
            assertEquals("script:my_filter", model.uiState.value.openEditor)
            model.editorOpened()
            assertNull(model.uiState.value.openEditor)
        }
}
