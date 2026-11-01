package app.songyan.ime.settings.ui

import android.content.Intent
import app.songyan.ime.settings.state.RimeConfigApplyResult
import app.songyan.ime.settings.state.RimeConfigEditResult
import app.songyan.ime.settings.state.RimeConfigFileResult
import app.songyan.ime.settings.state.RimeConfigGateway
import app.songyan.ime.settings.state.RimeConfigInfo
import app.songyan.ime.settings.state.RimeConfigSaveResult
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
        val applyCalls = mutableListOf<Boolean>()

        override val available = true

        override fun openFolderIntents(): List<Intent> = emptyList()

        override suspend fun info() = RimeConfigInfo(fileCount = 1, pending = true, unacknowledged = unacknowledged)

        override suspend fun apply(confirmScripts: Boolean): RimeConfigApplyResult {
            applyCalls += confirmScripts
            return if (scripts.isNotEmpty() && !confirmScripts) {
                RimeConfigApplyResult.NeedsConfirmation(scripts, firstTime)
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
                ScriptConfirmation(listOf("lua/date.lua"), firstTime = true),
                model.uiState.value.scriptConfirmation,
            )
            assertEquals("not applied", listOf(false), gateway.applyCalls)
            assertNull(model.uiState.value.message)

            model.cancelScripts()
            assertNull(model.uiState.value.scriptConfirmation)
            assertEquals("cancelling asks nothing more", listOf(false), gateway.applyCalls)

            model.apply()
            advanceUntilIdle()
            model.confirmScripts()
            advanceUntilIdle()
            assertEquals(listOf(false, false, true), gateway.applyCalls)
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
}
