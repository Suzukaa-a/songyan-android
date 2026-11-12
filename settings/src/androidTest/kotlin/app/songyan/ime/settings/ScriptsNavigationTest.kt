@file:OptIn(ExperimentalTestApi::class)

package app.songyan.ime.settings

import android.app.Application
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import app.songyan.ime.settings.state.EngineStatus
import app.songyan.ime.settings.state.ImeStatus
import app.songyan.ime.settings.state.InMemorySettingsAppStateSource
import app.songyan.ime.settings.state.InMemorySettingsPreferences
import app.songyan.ime.settings.state.SettingsAppState
import app.songyan.ime.settings.state.UnavailableDictionaryImportGateway
import app.songyan.ime.settings.state.UnavailableSchemeImportGateway
import app.songyan.ime.settings.ui.ScriptsViewModel
import app.songyan.ime.settings.ui.SettingsNavHost
import app.songyan.ime.settings.ui.SettingsRoutes
import app.songyan.ime.settings.ui.SettingsViewModel
import app.songyan.ime.settings.ui.theme.SongyanTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The 脚本 pages inside the real settings navigation graph, with the one [ScriptsViewModel] the
 * host now hoists for all of them. This is the path the screen tests cannot see: there every page
 * shares the view model handed to the harness, while the graph used to build a fresh one per
 * destination, so the confirmation page started loading and closed itself before it had the entry.
 */
class ScriptsNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var registry: FakeScriptRegistry
    private lateinit var navigation: NavHostController
    private lateinit var scripts: ScriptsViewModel

    private fun showScripts(prepare: (FakeScriptRegistry) -> Unit = {}) {
        registry = FakeScriptRegistry().apply(prepare)
        scripts = ScriptsViewModel(registry, Dispatchers.Unconfined)
        val settings =
            SettingsViewModel(
                application = ApplicationProvider.getApplicationContext<Application>(),
                appStateSource = InMemorySettingsAppStateSource(SettingsAppState(engine = EngineStatus.Ready)),
                imeStatusSource = { ImeStatus(enabled = true, selected = true) },
                preferences = InMemorySettingsPreferences(),
                dictionaryGateway = UnavailableDictionaryImportGateway(),
                schemeGateway = UnavailableSchemeImportGateway(),
            )
        compose.setContent {
            navigation = rememberNavController()
            SongyanTheme(dynamicColor = false) {
                SettingsNavHost(
                    viewModel = settings,
                    versionName = "1",
                    navController = navigation,
                    scriptsViewModel = scripts,
                )
            }
        }
        compose.runOnIdle { navigation.navigate(SettingsRoutes.SCRIPTS) }
        compose.waitUntil(5_000) {
            navigation.currentDestination?.route == SettingsRoutes.SCRIPTS &&
                compose.onAllNodes(hasText(str(R.string.scripts_title))).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun addSheet(): SemanticsMatcher = hasAnyAncestor(isDialog())

    private fun dialogTextField(): SemanticsMatcher = hasSetTextAction() and addSheet()

    private fun importDialogConfirm(): SemanticsMatcher =
        hasText(str(R.string.scripts_add)) and hasClickAction() and addSheet()

    /** A paste import through the dialogs, exactly as the user does it. */
    private fun pasteScript() {
        compose.onNodeWithText(str(R.string.scripts_add)).performClick()
        compose.waitUntilExactlyOneExists(hasText(str(R.string.scripts_add_paste)), 5_000)
        compose.onNodeWithText(str(R.string.scripts_add_paste)).performClick()
        compose.waitUntilExactlyOneExists(hasText(str(R.string.scripts_add_paste_title)), 5_000)
        compose.onNode(dialogTextField()).performTextInput(SCRIPT_SOURCE)
        compose.onNode(importDialogConfirm()).performClick()
    }

    @Test
    fun aPasteImportOpensTheConfirmationPageAndItStays() {
        showScripts()
        // The real proxy answers the import after the paste dialog has already closed; hold the
        // answer so the test sees exactly that moment, which is where the request used to be lost.
        val importGate = CompletableDeferred<Unit>()
        registry.addGate = importGate
        pasteScript()

        // Dialog gone, import still in flight: the list is back on screen and nothing navigates.
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.scripts_confirm_title)).assertDoesNotExist()
        assertEquals(SettingsRoutes.SCRIPTS, navigation.currentDestination?.route)
        assertEquals(1, registry.adds.size)

        // The import lands now: the host opens the confirmation page instead of leaving the
        // pending row in the list.
        compose.runOnIdle { importGate.complete(Unit) }
        compose.waitUntilExactlyOneExists(hasText(str(R.string.scripts_confirm_title)), 5_000)
        compose.waitForIdle()

        // The page is still there after the graph settled: nothing popped it back to the list.
        compose.onNodeWithText(str(R.string.scripts_confirm_title)).assertExists()
        compose.onNodeWithText(str(R.string.scripts_confirm_action)).assertExists()
        assertEquals(SettingsRoutes.SCRIPT_CONFIRM, navigation.currentDestination?.route)
        assertNull(
            "the request is consumed once the confirmation page arrived",
            scripts.uiState.value.pendingConfirmation,
        )
        assertTrue("the stored script is waiting for confirmation", registry.entries.single().confirmationPending)

        // Confirming the page finishes the flow and leaves the script in the list. The registry
        // answers after a moment, like the proxy: the page shows it is busy, then closes itself.
        val gate = CompletableDeferred<Unit>()
        registry.confirmGate = gate
        compose.onNodeWithText(str(R.string.scripts_confirm_action)).performClick()
        compose.onNodeWithText(str(R.string.scripts_confirm_action)).assertIsNotEnabled()
        compose.runOnIdle { gate.complete(Unit) }
        compose.waitUntil(5_000) { navigation.currentDestination?.route == SettingsRoutes.SCRIPTS }
        assertEquals(1, registry.confirms.size)
    }

    @Test
    fun aPendingRowOpensTheConfirmationPage() {
        showScripts { it.store(declaredEntry(pending = true)) }

        compose.waitUntilExactlyOneExists(hasText(SCRIPT_NAME), 5_000)
        compose.onNodeWithText(SCRIPT_NAME).performClick()

        compose.waitUntilExactlyOneExists(hasText(str(R.string.scripts_confirm_title)), 5_000)
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.scripts_confirm_action)).assertExists()
        assertEquals(SettingsRoutes.SCRIPT_CONFIRM, navigation.currentDestination?.route)
    }

    @Test
    fun aConfirmedRowStillOpensItsDetailPage() {
        showScripts { it.store(declaredEntry(pending = false, enabled = true)) }

        compose.waitUntilExactlyOneExists(hasText(SCRIPT_NAME), 5_000)
        compose.onNodeWithText(SCRIPT_NAME).performClick()

        compose.waitUntil(5_000) { navigation.currentDestination?.route == SettingsRoutes.SCRIPT_DETAIL }
        compose.waitForIdle()
        assertEquals(SettingsRoutes.SCRIPT_DETAIL, navigation.currentDestination?.route)
        assertNotEquals(SettingsRoutes.SCRIPT_CONFIRM, navigation.currentDestination?.route)
        // The detail page is the one with the trial run; the confirmation page has no such section.
        compose.onNodeWithText(str(R.string.scripts_test_section)).assertExists()
    }
}
