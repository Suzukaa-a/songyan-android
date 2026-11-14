@file:OptIn(ExperimentalTestApi::class)

package app.songyan.ime.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.songyan.ime.script.ScriptSourceOrigin
import app.songyan.ime.settings.ui.ScriptsViewModel
import app.songyan.ime.settings.ui.screens.ScriptConfirmScreen
import app.songyan.ime.settings.ui.screens.ScriptDetailScreen
import app.songyan.ime.settings.ui.screens.ScriptsScreen
import app.songyan.ime.settings.ui.theme.SongyanTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * 设置 → 脚本 as the user sees it: the empty page that starts the flow, the four ways a script can
 * come in, the confirmation page with what the script declares, and the update check that sends
 * the user back to confirmation when the declarations changed.
 *
 * The screens take their ViewModel from the caller and the ViewModel takes the registry, so the
 * package-level [FakeScriptRegistry] replaces the proxy process: no engine, no network, no storage.
 */
class ScriptsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun showFlow(registry: FakeScriptRegistry = FakeScriptRegistry()): ScriptsViewModel {
        val viewModel = ScriptsViewModel(registry, Dispatchers.Unconfined)
        compose.setContent {
            ScriptsFlow(viewModel)
        }
        return viewModel
    }

    private fun showConfirmation(
        registry: FakeScriptRegistry,
        scriptId: String = "script-1",
    ) {
        val viewModel = ScriptsViewModel(registry, Dispatchers.Unconfined)
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                ScriptConfirmScreen(scriptId = scriptId, onDone = {}, viewModel = viewModel)
            }
        }
    }

    private fun addSheet(): SemanticsMatcher = hasAnyAncestor(isDialog())

    private fun dialogTextField(): SemanticsMatcher = hasSetTextAction() and addSheet()

    /** The add sheet's own "Add script" is not in a dialog body with a click action; the import
     *  dialogs' confirm button is. */
    private fun importDialogConfirm(): SemanticsMatcher =
        hasText(str(R.string.scripts_add)) and hasClickAction() and addSheet()

    private fun waitForOne(matcher: SemanticsMatcher) {
        compose.waitUntilExactlyOneExists(matcher, timeoutMillis = 5_000)
    }

    /** The update check moved from the top bar into the overflow; open it and tap the entry. */
    private fun checkForUpdateThroughTheOverflow() {
        compose.onNodeWithContentDescription(str(R.string.scripts_more_actions)).performClick()
        waitForOne(hasText(str(R.string.scripts_check_update)))
        compose.onNodeWithText(str(R.string.scripts_check_update)).performClick()
    }

    private fun waitUntilGone(matcher: SemanticsMatcher) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty()
        }
    }

    // --- the empty page and the ways in ---

    @Test
    fun theEmptyPageOffersTheGuideForAiAndTheMinimalExample() {
        showFlow()
        waitForOne(hasText(str(R.string.scripts_empty_title)))
        compose.onNodeWithText(str(R.string.scripts_empty_body)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.scripts_copy_guide)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.scripts_add)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.scripts_example_title)).assertExists()
    }

    @Test
    fun theAddSheetOffersEveryWayToAddAScript() {
        showFlow()
        waitForOne(hasText(str(R.string.scripts_empty_title)))
        compose.onNodeWithText(str(R.string.scripts_add)).performClick()

        compose.onNodeWithText(str(R.string.scripts_add_paste)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.scripts_add_file)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.scripts_add_link)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.scripts_add_editor)).assertIsDisplayed()
    }

    @Test
    fun pastingAScriptOpensTheConfirmationPageWithWhatItDeclares() {
        val registry = FakeScriptRegistry().apply { imported = declaredEntry() }
        showFlow(registry)

        waitForOne(hasText(str(R.string.scripts_empty_title)))
        compose.onNodeWithText(str(R.string.scripts_add)).performClick()
        compose.onNodeWithText(str(R.string.scripts_add_paste)).performClick()
        waitForOne(hasText(str(R.string.scripts_add_paste_title)))
        compose.onNodeWithText(str(R.string.scripts_add_paste_label)).assertExists()
        compose.onNode(dialogTextField()).performTextInput(SCRIPT_SOURCE)
        compose.onNode(importDialogConfirm()).assertIsEnabled().performClick()

        waitForOne(hasText(str(R.string.scripts_confirm_title)))
        val pasted = registry.adds.single().first
        assertEquals(SCRIPT_SOURCE, pasted.toString(Charsets.UTF_8))
        // Name, what it reads, which sites it may reach, and the settings it asks for.
        compose.onNodeWithText(str(R.string.scripts_confirm_name)).assertExists()
        compose.onNodeWithText(SCRIPT_NAME).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.scripts_confirm_reads)).assertExists()
        compose.onNodeWithText(str(R.string.scripts_confirm_section_connects)).assertExists()
        DECLARED_SITES.forEach { compose.onNodeWithText(it).assertExists() }
        // The url setting is a site too: the user's own address is disclosed with the declared ones.
        compose.onNodeWithText(str(R.string.scripts_connects_typed)).assertExists()
        compose.onNodeWithText(str(R.string.scripts_confirm_section_settings)).assertExists()
        // The pinned confirm bar leaves less list height, and a LazyColumn only composes what is
        // near the viewport: scroll to each field before asserting it.
        val form = hasScrollToIndexAction()
        compose
            .onNode(form)
            .performScrollToNode(hasText(DECLARED_SETTINGS[0].label + str(R.string.scripts_secret_suffix)))
        compose.onNodeWithContentDescription(str(R.string.scripts_secret_show)).assertExists()
        compose.onNode(form).performScrollToNode(hasText(DECLARED_SETTINGS[1].label))
        compose.onNodeWithText(str(R.string.scripts_setting_kind_url)).assertExists()
        compose.onNode(form).performScrollToNode(hasText(DECLARED_SETTINGS[2].label))
        compose.onNodeWithText(DECLARED_SETTINGS[1].label).assertExists()
        compose.onNodeWithText(DECLARED_SETTINGS[2].label).assertExists()
        // A choice is a menu of its declared options, starting on the first one.
        compose.onNodeWithText("正式").assertExists().performClick()
        compose.onNodeWithText("随意").assertExists().performClick()
        compose.onNodeWithText("随意").assertExists()
        // An address the script could not reach blocks confirming.
        compose.onNode(form).performScrollToNode(hasText(DECLARED_SETTINGS[1].label))
        compose
            .onNode(
                hasText(DECLARED_SETTINGS[1].label) and hasSetTextAction(),
            ).performTextInput("http://llm.example.com")
        compose.onNodeWithText(str(R.string.scripts_setting_url_invalid)).assertExists()
        compose.onNodeWithText(str(R.string.scripts_confirm_action)).assertIsNotEnabled()
    }

    @Test
    fun aLinkIsAddedThroughItsOwnDialog() {
        val registry = FakeScriptRegistry().apply { imported = declaredEntry(name = "链接脚本") }
        showFlow(registry)

        waitForOne(hasText(str(R.string.scripts_empty_title)))
        compose.onNodeWithText(str(R.string.scripts_add)).performClick()
        compose.onNodeWithText(str(R.string.scripts_add_link)).performClick()

        waitForOne(hasText(str(R.string.scripts_add_link_title)))
        compose.onNodeWithText(str(R.string.scripts_add_link_label)).assertExists()
        compose.onNodeWithText(str(R.string.scripts_add_link_body)).assertIsDisplayed()
        val confirm = compose.onNode(importDialogConfirm())
        confirm.assertIsNotEnabled()
        compose.onNode(dialogTextField()).performTextInput(LINK)
        confirm.assertIsEnabled().performClick()

        waitForOne(hasText(str(R.string.scripts_confirm_title)))
        assertEquals(listOf(LINK), registry.links)
        compose.onNodeWithText("链接脚本").assertIsDisplayed()
    }

    @Test
    fun theEditorEntryOpensTheInAppEditor() {
        showFlow()
        waitForOne(hasText(str(R.string.scripts_empty_title)))
        compose.onNodeWithText(str(R.string.scripts_add)).performClick()
        compose.onNodeWithText(str(R.string.scripts_add_editor)).performClick()

        waitForOne(hasText(str(R.string.scripts_editor_new_title)))
        compose.onNodeWithText(str(R.string.scripts_editor_body)).assertExists()
    }

    // --- confirmation and the list it comes back to ---

    @Test
    fun confirmingAScriptStoresItsSettingsAndBringsItBackToTheListToEnable() {
        val gate = CompletableDeferred<Unit>()
        val registry =
            FakeScriptRegistry().apply {
                imported = declaredEntry()
                confirmGate = gate
            }
        showFlow(registry)

        waitForOne(hasText(str(R.string.scripts_empty_title)))
        compose.onNodeWithText(str(R.string.scripts_add)).performClick()
        compose.onNodeWithText(str(R.string.scripts_add_paste)).performClick()
        waitForOne(hasText(str(R.string.scripts_add_paste_title)))
        compose.onNode(dialogTextField()).performTextInput(SCRIPT_SOURCE)
        compose.onNode(importDialogConfirm()).performClick()

        waitForOne(hasText(str(R.string.scripts_confirm_title)))
        compose.onNode(hasSetTextAction() and passwordField()).performTextInput(SECRET)
        compose.onNodeWithText(str(R.string.scripts_confirm_action)).performClick()

        // The confirmation takes a moment: the page waits, then closes by itself. A first import
        // arrives already enabled, without the user or the screen flipping the switch.
        compose.onNodeWithText(str(R.string.scripts_confirm_action)).assertIsNotEnabled()
        gate.complete(Unit)
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(isToggleable()).fetchSemanticsNodes().isNotEmpty()
        }
        // Only the typed field travels; the blank non-secret settings keep whatever was stored
        // (nothing, on a first confirmation) and the secret is the one value the user typed.
        assertEquals(mapOf(DECLARED_SETTINGS[0].key to SECRET), registry.confirms.single().second)
        assertEquals(listOf(emptySet<String>()), registry.clearedSecrets.map { it.second })
        compose.onNodeWithText(str(R.string.scripts_empty_title)).assertDoesNotExist()
        compose.onNode(isToggleable()).assertIsOn()
        assertEquals(emptyList<Pair<String, Boolean>>(), registry.enabled)

        // The switch in the list still works after that.
        compose.onNode(isToggleable()).performClick()
        compose.onNode(isToggleable()).assertIsOff()
        assertEquals(listOf("script-1" to false), registry.enabled)
    }

    @Test
    fun aTextScriptConfirmationSaysWhatItReads() {
        val registry = FakeScriptRegistry().apply { store(declaredEntry(pending = true)) }
        showConfirmation(registry)

        waitForOne(hasText(str(R.string.scripts_confirm_reads_body)))
        compose.onNodeWithText(str(R.string.scripts_confirm_reads_audio)).assertDoesNotExist()
    }

    @Test
    fun aVoiceScriptConfirmationSaysItRecords() {
        val registry = FakeScriptRegistry().apply { store(declaredEntry(pending = true, needsAudio = true)) }
        showConfirmation(registry)

        waitForOne(hasText(str(R.string.scripts_confirm_reads_audio)))
        compose.onNodeWithText(str(R.string.scripts_confirm_reads_body)).assertDoesNotExist()
    }

    @Test
    fun aSecretFieldIsAMaskedInputWithARevealToggle() {
        val registry =
            FakeScriptRegistry().apply {
                store(declaredEntry(settings = listOf(DECLARED_SETTINGS[0])))
            }
        showConfirmation(registry)

        waitForOne(hasText(DECLARED_SETTINGS[0].label + str(R.string.scripts_secret_suffix)))
        // A masked field is what the platform (autofill, accessibility) is told it is; the
        // reveal button is the affordance the user has for looking at what they typed.
        compose.onNode(hasSetTextAction() and passwordField()).assertExists()
        // The reveal control names what it will do next: show while masked, hide while shown.
        compose
            .onNodeWithContentDescription(str(R.string.scripts_secret_show))
            .assertHasClickAction()
            .performClick()
        compose.onNodeWithContentDescription(str(R.string.scripts_secret_hide)).assertExists()
    }

    // --- the update check ---

    @Test
    fun anUpdateWithChangedDeclarationsSendsTheUserBackToConfirmation() {
        val link = ScriptSourceOrigin.Link(LINK, LINK)
        val before =
            declaredEntry(
                name = "链接脚本",
                enabled = true,
                pending = false,
                connects = listOf("https://a.test"),
                origin = link,
            )
        val gate = CompletableDeferred<Unit>()
        val registry =
            FakeScriptRegistry().apply {
                store(before)
                checkedUpdate = before.copy(connects = listOf("https://b.test"), confirmationPending = true)
                confirmGate = gate
            }
        showFlow(registry)

        waitForOne(hasText("链接脚本"))
        compose.onNodeWithText("链接脚本").performClick()

        // The script's own page carries the update check for a link origin, behind the overflow.
        waitForOne(hasText(str(R.string.scripts_test_section)))
        checkForUpdateThroughTheOverflow()

        // New declarations are unconfirmed again: the page says so and leads back to confirmation.
        waitForOne(hasText(str(R.string.scripts_pending_title)))
        compose.onNodeWithText(str(R.string.scripts_confirm_action)).performClick()
        waitForOne(hasText(str(R.string.scripts_confirm_title)))
        compose.onNodeWithText("https://b.test").assertExists()
        compose.onNodeWithText(str(R.string.scripts_confirm_action)).performClick()
        compose.onNodeWithText(str(R.string.scripts_confirm_action)).assertIsNotEnabled()
        gate.complete(Unit)

        // Confirming the new declarations closes the page and clears the pending banner.
        waitForOne(hasText(str(R.string.scripts_test_section)))
        compose.onNodeWithText(str(R.string.scripts_pending_title)).assertDoesNotExist()
        assertEquals(listOf("script-1"), registry.updates)
        assertEquals("script-1", registry.confirms.single().first)

        // A re-confirmation after an update keeps the switch as the user left it: still on, and
        // the screen never calls the enable action itself.
        compose.onNodeWithContentDescription(str(R.string.action_back)).performClick()
        waitForOne(hasText("链接脚本"))
        compose.onNode(isToggleable()).assertIsOn()
        assertEquals(emptyList<Pair<String, Boolean>>(), registry.enabled)

        // The same holds the other way: a script the user switched off comes back off after
        // another update is confirmed.
        compose.onNode(isToggleable()).performClick()
        compose.onNode(isToggleable()).assertIsOff()
        compose.onNodeWithText("链接脚本").performClick()
        waitForOne(hasText(str(R.string.scripts_test_section)))
        checkForUpdateThroughTheOverflow()
        waitForOne(hasText(str(R.string.scripts_pending_title)))
        compose.onNodeWithText(str(R.string.scripts_confirm_action)).performClick()
        waitForOne(hasText(str(R.string.scripts_confirm_title)))
        val secondGate = CompletableDeferred<Unit>()
        registry.confirmGate = secondGate
        compose.onNodeWithText(str(R.string.scripts_confirm_action)).performClick()
        compose.onNodeWithText(str(R.string.scripts_confirm_action)).assertIsNotEnabled()
        secondGate.complete(Unit)
        waitForOne(hasText(str(R.string.scripts_test_section)))
        compose.onNodeWithContentDescription(str(R.string.action_back)).performClick()
        waitForOne(hasText("链接脚本"))
        compose.onNode(isToggleable()).assertIsOff()
        // Only the user's own switch tap reached the enable action.
        assertEquals(listOf("script-1" to false), registry.enabled)
    }

    // --- the flow harness ---

    /** The pages the production graph navigates between, stacked the same way: the list opens a
     *  script or a confirmation, and each page goes back to the one before it. */
    @Composable
    private fun ScriptsFlow(viewModel: ScriptsViewModel) {
        var stack by remember { mutableStateOf(listOf(ScriptsPage.LIST)) }
        var scriptId by remember { mutableStateOf("") }
        // What SettingsNavHost does in production: an import asks for the confirmation page
        // through the shared state, and the host navigates once the request stands.
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        LaunchedEffect(state.pendingConfirmation, stack.last()) {
            val pending = state.pendingConfirmation ?: return@LaunchedEffect
            if (stack.last() == ScriptsPage.CONFIRM) {
                viewModel.consumePendingConfirmation()
            } else {
                scriptId = pending.id
                stack = stack + ScriptsPage.CONFIRM
            }
        }
        SongyanTheme(dynamicColor = false) {
            when (stack.last()) {
                ScriptsPage.LIST ->
                    ScriptsScreen(
                        onBack = null,
                        onOpen = { id ->
                            scriptId = id
                            stack = stack + ScriptsPage.DETAIL
                        },
                        onOpenConfirmation = { id ->
                            scriptId = id
                            stack = stack + ScriptsPage.CONFIRM
                        },
                        viewModel = viewModel,
                    )

                ScriptsPage.DETAIL ->
                    ScriptDetailScreen(
                        scriptId = scriptId,
                        onBack = { stack = stack.dropLast(1) },
                        onOpenConfirmation = { id ->
                            scriptId = id
                            stack = stack + ScriptsPage.CONFIRM
                        },
                        viewModel = viewModel,
                    )

                ScriptsPage.CONFIRM ->
                    ScriptConfirmScreen(
                        scriptId = scriptId,
                        onDone = { if (stack.size > 1) stack = stack.dropLast(1) },
                        viewModel = viewModel,
                    )
            }
        }
    }

    private enum class ScriptsPage { LIST, DETAIL, CONFIRM }

    private fun passwordField(): SemanticsMatcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)
}
