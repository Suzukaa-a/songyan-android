// SPDX-License-Identifier: GPL-3.0-only
package app.songyan.ime.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.songyan.ime.settings.state.AddonCallInfo
import app.songyan.ime.settings.state.AddonCallKind
import app.songyan.ime.settings.state.AddonCompatibility
import app.songyan.ime.settings.state.AddonInfo
import app.songyan.ime.settings.state.AddonSettingsGateway
import app.songyan.ime.settings.state.AddonToolInfo
import app.songyan.ime.settings.ui.ExtensionsViewModel
import app.songyan.ime.settings.ui.screens.ExtensionsScreen
import app.songyan.ime.settings.ui.theme.SongyanTheme
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * 设置 → 扩展 as the user sees it after the card redesign: one card per extension with identity,
 * switch and a compatibility badge, capability/tool/permission chips, a collapsible identity block,
 * the last call as secondary information, and the extension's own settings page as a button.
 */
@OptIn(ExperimentalTestApi::class)
class ExtensionsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private class FakeGateway(
        initial: List<AddonInfo>,
    ) : AddonSettingsGateway {
        override val available: Boolean = true
        var extensions: List<AddonInfo> = initial
        val toggles: MutableList<Pair<String, Boolean>> = mutableListOf()

        override suspend fun list(): List<AddonInfo> = extensions

        override suspend fun rescan(): List<AddonInfo> = extensions

        override suspend fun setEnabled(
            packageName: String,
            enabled: Boolean,
        ): Boolean {
            toggles += packageName to enabled
            extensions = extensions.map { if (it.packageName == packageName) it.copy(enabled = enabled) else it }
            return true
        }
    }

    private class HangingGateway : AddonSettingsGateway {
        override val available: Boolean = true

        override suspend fun list(): List<AddonInfo> = awaitCancellation()

        override suspend fun rescan(): List<AddonInfo> = awaitCancellation()

        override suspend fun setEnabled(
            packageName: String,
            enabled: Boolean,
        ): Boolean = false
    }

    private fun show(
        gateway: AddonSettingsGateway,
        openSettings: ((String) -> Unit)? = null,
    ): ExtensionsViewModel {
        val model = ExtensionsViewModel(gateway)
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                ExtensionsScreen(onBack = {}, model = model, openSettings = openSettings)
            }
        }
        return model
    }

    private val settingsComponent = "app.songyan.ime.addon.sample/.SampleSettingsActivity"

    private fun sampleAddon(
        packageName: String = "app.songyan.ime.addon.sample",
        label: String = "Sample Addon",
        enabled: Boolean = false,
        compatibility: AddonCompatibility = AddonCompatibility.COMPATIBLE,
        settingsComponent: String? = this.settingsComponent,
        tools: List<AddonToolInfo> =
            listOf(
                AddonToolInfo("uppercase", "Uppercase"),
                AddonToolInfo("crash", "Crash the addon (debug only)"),
            ),
    ): AddonInfo =
        AddonInfo(
            packageName = packageName,
            label = label,
            versionName = "0.1.0",
            signerSha256 = "0123456789abcdef".repeat(4),
            permissions = listOf("Microphone"),
            enabled = enabled,
            compatibility = compatibility,
            incompatibleReason =
                if (compatibility == AddonCompatibility.INCOMPATIBLE) "no common protocol version" else null,
            tools = tools,
            supportsPanel = true,
            supportsVoice = true,
            settingsComponent = settingsComponent,
            lastCall =
                AddonCallInfo(
                    kind = AddonCallKind.TEXT,
                    atEpochMs = 1_700_000_000_000,
                    durationMs = 1_200,
                    error = "boom",
                ),
        )

    private fun waitForCard() {
        compose.waitUntilExactlyOneExists(
            androidx.compose.ui.test
                .hasText("Sample Addon"),
            timeoutMillis = 5_000,
        )
    }

    @Test
    fun theEmptyPageUsesTheSharedStateMessageAndOffersARescan() {
        show(FakeGateway(emptyList()))
        compose.waitUntilExactlyOneExists(
            androidx.compose.ui.test
                .hasText(str(R.string.extensions_empty_title)),
            timeoutMillis = 5_000,
        )
        compose.onNodeWithText(str(R.string.extensions_body)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.extensions_empty_body)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.extensions_rescan)).assertIsDisplayed()
    }

    @Test
    fun aCardShowsIdentityBadgeCapabilitiesPermissionsAndLastCall() {
        show(FakeGateway(listOf(sampleAddon())))
        waitForCard()
        // A working extension carries no badge; the package name waits in the details.
        compose.onNodeWithText(str(R.string.extensions_unknown)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.extensions_incompatible)).assertDoesNotExist()
        compose.onNodeWithText("app.songyan.ime.addon.sample").assertDoesNotExist()
        // Capability and tool chips
        compose.onNodeWithText("Uppercase").assertIsDisplayed()
        compose.onNodeWithText(str(R.string.extensions_panel)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.extensions_voice)).assertIsDisplayed()
        // Permission chip and section
        compose.onNodeWithText(str(R.string.extensions_permissions_section)).assertIsDisplayed()
        compose.onNodeWithText("Microphone").assertIsDisplayed()
        // Last call is secondary information, not a body line per identity field
        compose
            .onNodeWithText(
                str(R.string.extensions_last_call_error, str(R.string.extensions_call_kind_text), "1.2", "boom"),
            ).performScrollTo()
            .assertIsDisplayed()
        // Version/signature stay folded until asked for
        compose.onNodeWithText(str(R.string.extensions_details)).assertIsDisplayed()
        compose
            .onNodeWithText(str(R.string.extensions_version, "0.1.0"))
            .assertDoesNotExist()
        compose.onNodeWithText(str(R.string.extensions_details)).performClick()
        compose.onNodeWithText("app.songyan.ime.addon.sample").assertIsDisplayed()
        compose.onNodeWithText(str(R.string.extensions_version, "0.1.0")).assertIsDisplayed()
        compose
            .onNodeWithContentDescription(str(R.string.extensions_switch, "Sample Addon"))
            .assertIsOff()
            .assertIsEnabled()
    }

    @Test
    fun anIncompatibleExtensionIsBadgedAndCannotBeTurnedOn() {
        show(FakeGateway(listOf(sampleAddon(compatibility = AddonCompatibility.INCOMPATIBLE))))
        compose.waitUntilExactlyOneExists(
            androidx.compose.ui.test
                .hasText(str(R.string.extensions_incompatible)),
            timeoutMillis = 5_000,
        )
        compose.onNodeWithText("no common protocol version").assertIsDisplayed()
        compose
            .onNodeWithContentDescription(str(R.string.extensions_switch, "Sample Addon"))
            .assertIsNotEnabled()
    }

    @Test
    fun anUnverifiedExtensionIsBadgedWithTheQuestionMark() {
        show(FakeGateway(listOf(sampleAddon(compatibility = AddonCompatibility.UNKNOWN))))
        compose.waitUntilExactlyOneExists(
            androidx.compose.ui.test
                .hasText(str(R.string.extensions_unknown)),
            timeoutMillis = 5_000,
        )
        compose.onNodeWithText(str(R.string.extensions_unknown)).assertIsDisplayed()
    }

    @Test
    fun theIdentityBlockExpandsAndTheSignatureCanBeCopied() {
        show(FakeGateway(listOf(sampleAddon())))
        waitForCard()
        compose.onNodeWithText(str(R.string.extensions_details)).performClick()
        compose.onNodeWithText(str(R.string.extensions_version, "0.1.0")).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.extensions_signer, "0123456789abcdef…")).assertIsDisplayed()
        compose.onNodeWithContentDescription(str(R.string.extensions_copy_signer)).performClick()
        compose.waitUntilExactlyOneExists(
            androidx.compose.ui.test
                .hasText(str(R.string.extensions_copied)),
            timeoutMillis = 5_000,
        )
    }

    @Test
    fun theExtensionSettingsEntryLaunchesTheDeclaredComponent() {
        val opened = mutableListOf<String>()
        show(FakeGateway(listOf(sampleAddon())), openSettings = { opened += it })
        waitForCard()
        compose.onNodeWithText(str(R.string.extensions_open_settings)).performClick()
        assertEquals(listOf(settingsComponent), opened)
    }

    @Test
    fun anExtensionWithoutASettingsPageHasNoSettingsEntry() {
        show(FakeGateway(listOf(sampleAddon(settingsComponent = null))))
        waitForCard()
        compose.onNodeWithText(str(R.string.extensions_open_settings)).assertDoesNotExist()
    }

    @Test
    fun developerOnlyToolsAreVisibleOnADebugHost() {
        // The compose tests run in a debuggable host, where the sample's debug tools are real; the
        // release filtering rule itself is covered by ExtensionsUiModelTest.
        show(FakeGateway(listOf(sampleAddon())))
        waitForCard()
        compose.onNodeWithText("Crash the addon (debug only)").assertIsDisplayed()
    }

    @Test
    fun theLoadingPageShowsTheFrameInsteadOfAnEmptyList() {
        show(HangingGateway())
        // The page frame (title) is drawn immediately; the empty state must not flash while loading.
        compose.waitUntilExactlyOneExists(
            androidx.compose.ui.test
                .hasText(str(R.string.extensions_title)),
            timeoutMillis = 5_000,
        )
        compose.onNodeWithText(str(R.string.extensions_empty_title)).assertDoesNotExist()
    }

    @Test
    fun turningAnExtensionOnCallsTheGateway() {
        val gateway = FakeGateway(listOf(sampleAddon()))
        show(gateway)
        waitForCard()
        compose.onNodeWithContentDescription(str(R.string.extensions_switch, "Sample Addon")).performClick()
        compose.waitUntil(timeoutMillis = 5_000) { gateway.toggles.isNotEmpty() }
        assertEquals(listOf("app.songyan.ime.addon.sample" to true), gateway.toggles.toList())
        compose.onNodeWithContentDescription(str(R.string.extensions_switch, "Sample Addon")).assertIsOn()
    }
}
