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
import app.songyan.ime.settings.state.AddonCallInfo
import app.songyan.ime.settings.state.AddonCallKind
import app.songyan.ime.settings.state.AddonCompatibility
import app.songyan.ime.settings.state.AddonInfo
import app.songyan.ime.settings.state.AddonSettingsGateway
import app.songyan.ime.settings.state.AddonToolInfo
import app.songyan.ime.settings.ui.ExtensionsViewModel
import app.songyan.ime.settings.ui.screens.ExtensionsScreen
import app.songyan.ime.settings.ui.theme.SongyanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * 设置 → 扩展 as the user sees it: where extensions come from, who an installed one is (package,
 * version, signing certificate, permissions), whether Songyan can talk to it, what it last did,
 * and the switch that turns it on.
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

    private fun show(gateway: FakeGateway): ExtensionsViewModel {
        val model = ExtensionsViewModel(gateway)
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                ExtensionsScreen(onBack = {}, model = model)
            }
        }
        return model
    }

    private fun sampleAddon(
        packageName: String = "app.songyan.ime.addon.sample",
        label: String = "Sample Addon",
        enabled: Boolean = false,
        compatibility: AddonCompatibility = AddonCompatibility.COMPATIBLE,
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
                if (compatibility ==
                    AddonCompatibility.INCOMPATIBLE
                ) {
                    "no common protocol version"
                } else {
                    null
                },
            tools = listOf(AddonToolInfo("uppercase", "Uppercase")),
            supportsPanel = true,
            supportsVoice = true,
            lastCall =
                AddonCallInfo(
                    kind = AddonCallKind.TEXT,
                    atEpochMs = 1_700_000_000_000,
                    durationMs = 12,
                    error = "boom",
                ),
        )

    @Test
    fun theEmptyPageExplainsWhereExtensionsComeFrom() {
        show(FakeGateway(emptyList()))
        compose.waitUntilExactlyOneExists(
            androidx.compose.ui.test
                .hasText(str(R.string.extensions_empty_title)),
            timeoutMillis = 5_000,
        )
        compose.onNodeWithText(str(R.string.extensions_body)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.extensions_empty_body)).assertIsDisplayed()
        compose.onNodeWithContentDescription(str(R.string.extensions_rescan)).assertIsDisplayed()
    }

    @Test
    fun anInstalledExtensionShowsItsIdentityPermissionsAndLastCall() {
        show(FakeGateway(listOf(sampleAddon())))
        compose.waitUntilExactlyOneExists(
            androidx.compose.ui.test
                .hasText("Sample Addon"),
            timeoutMillis = 5_000,
        )
        compose.onNodeWithText("app.songyan.ime.addon.sample").assertIsDisplayed()
        compose.onNodeWithText(str(R.string.extensions_version, "0.1.0")).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.extensions_signer, "0123456789abcdef…")).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.extensions_permissions, "Microphone")).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.extensions_tools, "Uppercase")).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.extensions_panel)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.extensions_voice)).assertIsDisplayed()
        compose
            .onNodeWithText(
                str(R.string.extensions_last_call_error, str(R.string.extensions_call_kind_text), 12L, "boom"),
            ).assertIsDisplayed()
        compose
            .onNodeWithContentDescription(str(R.string.extensions_switch, "Sample Addon"))
            .assertIsOff()
            .assertIsEnabled()
    }

    @Test
    fun anIncompatibleExtensionIsMarkedAndCannotBeTurnedOn() {
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
    fun turningAnExtensionOnCallsTheGateway() {
        val gateway = FakeGateway(listOf(sampleAddon()))
        show(gateway)
        compose.waitUntilExactlyOneExists(
            androidx.compose.ui.test
                .hasText("Sample Addon"),
            timeoutMillis = 5_000,
        )
        compose.onNodeWithContentDescription(str(R.string.extensions_switch, "Sample Addon")).performClick()
        compose.waitUntil(timeoutMillis = 5_000) { gateway.toggles.isNotEmpty() }
        assertEquals(listOf("app.songyan.ime.addon.sample" to true), gateway.toggles.toList())
        compose.onNodeWithContentDescription(str(R.string.extensions_switch, "Sample Addon")).assertIsOn()
    }
}
