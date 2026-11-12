// SPDX-License-Identifier: GPL-3.0-only
package app.songyan.ime.settings.ui

import app.songyan.ime.settings.state.AddonCompatibility
import app.songyan.ime.settings.state.AddonInfo
import app.songyan.ime.settings.state.AddonSettingsGateway
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 设置 → 扩展: loading, the failed scan that keeps the last list, and the switch's rollback. */
@OptIn(ExperimentalCoroutinesApi::class)
class ExtensionsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun addon(
        packageName: String,
        enabled: Boolean = false,
        compatibility: AddonCompatibility = AddonCompatibility.COMPATIBLE,
    ): AddonInfo =
        AddonInfo(
            packageName = packageName,
            label = packageName,
            versionName = "1.0",
            signerSha256 = null,
            enabled = enabled,
            compatibility = compatibility,
        )

    private class FakeGateway(
        initial: List<AddonInfo>,
    ) : AddonSettingsGateway {
        override val available: Boolean = true
        var extensions: List<AddonInfo> = initial
        var failList: Boolean = false
        var failSetEnabled: Boolean = false

        /** The gateway's own write failed without throwing: nothing was saved. */
        var refusedWrite: Boolean = false
        val toggles: MutableList<Pair<String, Boolean>> = mutableListOf()

        override suspend fun list(): List<AddonInfo> {
            if (failList) error("scan failed")
            return extensions
        }

        override suspend fun rescan(): List<AddonInfo> = list()

        override suspend fun setEnabled(
            packageName: String,
            enabled: Boolean,
        ): Boolean {
            if (failSetEnabled) error("refused")
            if (refusedWrite) return false
            toggles += packageName to enabled
            extensions = extensions.map { if (it.packageName == packageName) it.copy(enabled = enabled) else it }
            return true
        }
    }

    @Test
    fun theListIsReadWhenThePageOpens() =
        runTest(dispatcher) {
            val gateway = FakeGateway(listOf(addon("app.one", enabled = true), addon("app.two")))
            val model = ExtensionsViewModel(gateway)
            advanceUntilIdle()
            val state = model.uiState.value
            assertFalse(state.loading)
            assertFalse(state.scanFailed)
            assertEquals(listOf("app.one", "app.two"), state.extensions.map { it.packageName })
            assertTrue(state.extensions.first().enabled)
        }

    @Test
    fun aFailedScanKeepsThePreviousListAndWarnsOnce() =
        runTest(dispatcher) {
            val gateway = FakeGateway(listOf(addon("app.one")))
            val model = ExtensionsViewModel(gateway)
            advanceUntilIdle()
            gateway.failList = true
            model.refresh()
            advanceUntilIdle()
            assertEquals(
                listOf("app.one"),
                model.uiState.value.extensions
                    .map { it.packageName },
            )
            assertTrue(model.uiState.value.scanFailed)
            model.consumeScanFailed()
            assertFalse(model.uiState.value.scanFailed)
        }

    @Test
    fun togglingAnExtensionGoesThroughTheGateway() =
        runTest(dispatcher) {
            val gateway = FakeGateway(listOf(addon("app.one")))
            val model = ExtensionsViewModel(gateway)
            advanceUntilIdle()
            model.setEnabled("app.one", true)
            advanceUntilIdle()
            assertEquals(listOf("app.one" to true), gateway.toggles)
            assertTrue(
                model.uiState.value.extensions
                    .single()
                    .enabled,
            )
        }

    @Test
    fun aRefusedToggleRollsTheSwitchBack() =
        runTest(dispatcher) {
            val gateway = FakeGateway(listOf(addon("app.one")))
            val model = ExtensionsViewModel(gateway)
            advanceUntilIdle()
            gateway.failSetEnabled = true
            model.setEnabled("app.one", true)
            advanceUntilIdle()
            assertFalse(
                model.uiState.value.extensions
                    .single()
                    .enabled,
            )
            assertTrue(model.uiState.value.actionFailed)
        }

    @Test
    fun aSwitchThatCouldNotBeSavedRollsBackAndWarns() =
        runTest(dispatcher) {
            val gateway = FakeGateway(listOf(addon("app.one")))
            val model = ExtensionsViewModel(gateway)
            advanceUntilIdle()
            gateway.refusedWrite = true
            model.setEnabled("app.one", true)
            advanceUntilIdle()
            assertFalse(
                model.uiState.value.extensions
                    .single()
                    .enabled,
            )
            assertTrue(model.uiState.value.actionFailed)
            model.consumeActionFailed()
            assertFalse(model.uiState.value.actionFailed)
        }

    @Test
    fun aMissingGatewayIsReportedAsUnavailable() =
        runTest(dispatcher) {
            val gateway =
                object : AddonSettingsGateway {
                    override val available: Boolean = false

                    override suspend fun list(): List<AddonInfo> = emptyList()

                    override suspend fun rescan(): List<AddonInfo> = emptyList()

                    override suspend fun setEnabled(
                        packageName: String,
                        enabled: Boolean,
                    ): Boolean = false
                }
            val model = ExtensionsViewModel(gateway)
            advanceUntilIdle()
            assertFalse(model.uiState.value.available)
        }
}
