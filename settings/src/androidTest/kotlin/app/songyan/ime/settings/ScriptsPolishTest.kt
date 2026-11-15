@file:OptIn(ExperimentalTestApi::class)

package app.songyan.ime.settings

import android.content.ClipboardManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.script.ScriptRunOutcome
import app.songyan.ime.script.ScriptSettingField
import app.songyan.ime.script.ScriptSettingKind
import app.songyan.ime.script.ScriptSourceOrigin
import app.songyan.ime.settings.ui.ScriptsViewModel
import app.songyan.ime.settings.ui.screens.ScriptConfirmScreen
import app.songyan.ime.settings.ui.screens.ScriptDetailScreen
import app.songyan.ime.settings.ui.screens.ScriptsScreen
import app.songyan.ime.settings.ui.theme.SongyanTheme
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The polish pass on the three script pages: a list row that ends with the switch and a badge, a
 * confirmation form that prefills saved values and keeps secrets unless the user clears them, a
 * pinned confirm button, and the detail page's overflow (order, edit, delete).
 */
class ScriptsPolishTest {
    @get:Rule
    val compose = createComposeRule()

    @Composable
    private fun PolishFlow(viewModel: ScriptsViewModel) {
        var route by rememberSaveable { mutableStateOf("list") }
        var scriptId by rememberSaveable { mutableStateOf<String?>(null) }
        when (route) {
            "detail" ->
                ScriptDetailScreen(
                    scriptId = scriptId.orEmpty(),
                    onBack = { route = "list" },
                    onOpenConfirmation = {
                        scriptId = it
                        route = "confirm"
                    },
                    viewModel = viewModel,
                )

            "confirm" ->
                ScriptConfirmScreen(
                    scriptId = scriptId.orEmpty(),
                    onDone = { route = "list" },
                    viewModel = viewModel,
                )

            else ->
                ScriptsScreen(
                    onBack = {},
                    onOpen = {
                        scriptId = it
                        route = "detail"
                    },
                    onOpenConfirmation = {
                        scriptId = it
                        route = "confirm"
                    },
                    viewModel = viewModel,
                )
        }
    }

    private fun show(registry: FakeScriptRegistry): ScriptsViewModel {
        val viewModel = ScriptsViewModel(registry, Dispatchers.Unconfined)
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                PolishFlow(viewModel)
            }
        }
        // The activity can take a moment to draw its first frame on a cold emulator.
        compose.waitForIdle()
        return viewModel
    }

    private fun waitFor(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            // A composition that is not read yet has no hierarchy; that is a retry, not a failure.
            runCatching { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
                .getOrDefault(false)
        }
    }

    @Test
    fun theListRowEndsWithTheSwitchAndShowsThePendingBadge() {
        val registry =
            FakeScriptRegistry().apply {
                store(
                    declaredEntry(pending = false, enabled = false),
                    declaredEntry(name = "待确认脚本", pending = true, enabled = false).copy(id = "script-2", order = 1),
                )
            }
        show(registry)
        waitFor(SCRIPT_NAME)

        // One control per row, and it is the switch; a script still waiting for confirmation has
        // none, because it cannot be turned on before it is confirmed.
        compose.onAllNodes(isToggleable()).assertCountEquals(1)
        compose.onNodeWithText(str(R.string.scripts_pending_badge)).assertExists()
        compose.onNodeWithContentDescription(str(R.string.scripts_enable_script, SCRIPT_NAME)).assertExists()
        compose.onNodeWithContentDescription(str(R.string.scripts_delete)).assertDoesNotExist()
        compose.onNodeWithContentDescription(str(R.string.scripts_move_up)).assertDoesNotExist()
    }

    @Test
    fun theSwitchLabelFollowsItsState() {
        val registry = FakeScriptRegistry().apply { store(declaredEntry(pending = false, enabled = false)) }
        show(registry)
        waitFor(SCRIPT_NAME)

        compose.onNodeWithContentDescription(str(R.string.scripts_enable_script, SCRIPT_NAME)).performClick()
        compose.onNodeWithContentDescription(str(R.string.scripts_disable_script, SCRIPT_NAME)).assertExists()
    }

    @Test
    fun theConfirmationPagePrefillsSavedValuesAndSendsOnlyWhatChanged() {
        val registry =
            FakeScriptRegistry().apply {
                store(declaredEntry(pending = true))
                savedValues =
                    mapOf(
                        "script-1" to
                            mapOf(
                                DECLARED_SETTINGS[1].key to "https://saved.example/v1",
                                DECLARED_SETTINGS[2].key to "随意",
                            ),
                    )
            }
        show(registry)
        waitFor(SCRIPT_NAME)
        compose.onNodeWithText(SCRIPT_NAME).performClick()

        waitFor(str(R.string.scripts_confirm_action))
        // The saved non-secret values arrive from the proxy and fill the form; wait for them so the
        // submit sees the prefilled map rather than the empty first frame.
        compose.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                compose.onAllNodes(hasText("https://saved.example/v1")).fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        // The pinned bar leaves less height; the last field lives below the first viewport.
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(DECLARED_SETTINGS[2].label))
        // A saved option that is not the default stays selected.
        compose.onNodeWithText("随意").assertExists()
        compose.onNodeWithText(str(R.string.scripts_confirm_action)).performClick()

        val sent = registry.confirms.single().second
        assertEquals("https://saved.example/v1", sent[DECLARED_SETTINGS[1].key])
        assertEquals("随意", sent[DECLARED_SETTINGS[2].key])
        // The untouched secret never travels: a blank secret keeps the stored one.
        assertEquals(listOf(emptySet<String>()), registry.clearedSecrets.map { it.second })
    }

    @Test
    fun theClearCheckboxIsTheExplicitWayToDeleteAStoredSecret() {
        val registry =
            FakeScriptRegistry().apply {
                store(declaredEntry(pending = true))
                savedValues = mapOf("script-1" to mapOf(DECLARED_SETTINGS[1].key to "https://saved.example/v1"))
            }
        show(registry)
        waitFor(SCRIPT_NAME)
        compose.onNodeWithText(SCRIPT_NAME).performClick()

        waitFor(str(R.string.scripts_secret_clear))
        compose.onNodeWithText(str(R.string.scripts_secret_clear)).performClick()
        compose.onNodeWithText(str(R.string.scripts_confirm_action)).performClick()
        assertEquals(setOf(DECLARED_SETTINGS[0].key), registry.clearedSecrets.single().second)
    }

    @Test
    fun theConfirmButtonStaysVisibleUnderALongForm() {
        val manySettings =
            (1..8).map { ScriptSettingField("field" + it, ScriptSettingKind.TEXT, "Field " + it, secret = false) }
        val registry =
            FakeScriptRegistry().apply {
                store(declaredEntry(pending = true, settings = manySettings))
            }
        show(registry)
        waitFor(SCRIPT_NAME)
        compose.onNodeWithText(SCRIPT_NAME).performClick()

        // No scrolling: the pinned bar keeps the action on screen even with a long form.
        compose.onNodeWithText(str(R.string.scripts_confirm_action)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.scripts_confirm_action)).assertHasClickAction()
    }

    @Test
    fun theDetailOverflowHostsOrderEditAndDelete() {
        val link = ScriptSourceOrigin.Link(LINK, LINK)
        val registry =
            FakeScriptRegistry().apply {
                store(
                    declaredEntry(name = SCRIPT_NAME, pending = false, enabled = true, origin = link),
                    declaredEntry(name = "另一个脚本", pending = false, enabled = true).copy(id = "script-2", order = 1),
                )
            }
        show(registry)
        waitFor(SCRIPT_NAME)
        compose.onNodeWithText(SCRIPT_NAME).performClick()

        waitFor(str(R.string.scripts_test_section))
        compose.onNodeWithContentDescription(str(R.string.scripts_more_actions)).performClick()
        compose.onNodeWithText(str(R.string.scripts_check_update)).assertExists()
        compose.onNodeWithText(str(R.string.scripts_edit)).assertExists()
        compose.onNodeWithText(str(R.string.scripts_export)).assertExists()
        compose.onNodeWithText(str(R.string.scripts_move_down)).assertExists()
        compose.onNodeWithText(str(R.string.scripts_delete)).performClick()
        compose.onNodeWithText(str(R.string.scripts_delete_body, SCRIPT_NAME)).assertExists()
    }

    @Test
    fun theSettingsDialogSavesEditedValuesAndKeepsUntouchedSecrets() {
        val registry =
            FakeScriptRegistry().apply {
                store(declaredEntry(pending = false, enabled = true))
                savedValues =
                    mapOf(
                        "script-1" to
                            mapOf(
                                DECLARED_SETTINGS[1].key to "https://saved.example/v1",
                                DECLARED_SETTINGS[2].key to "随意",
                            ),
                    )
            }
        show(registry)
        waitFor(SCRIPT_NAME)
        compose.onNodeWithText(SCRIPT_NAME).performClick()

        waitFor(str(R.string.scripts_settings_fields))
        compose.onNodeWithContentDescription(str(R.string.scripts_settings_edit)).performClick()
        waitFor(str(R.string.scripts_settings_title))
        compose.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                compose
                    .onAllNodes(hasSetTextAction() and hasText("https://saved.example/v1"))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNode(hasSetTextAction() and hasText("https://saved.example/v1")).performTextClearance()
        compose.onNode(hasSetTextAction() and hasText("https://saved.example/v1")).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.scripts_editor_save)).performClick()

        val saved = registry.confirms.single().second
        assertEquals("", saved[DECLARED_SETTINGS[1].key])
        assertEquals(listOf(emptySet<String>()), registry.clearedSecrets.map { it.second })
    }

    @Test
    fun aConfirmationWithoutSitesStatesItUnderItsOwnHeading() {
        val registry =
            FakeScriptRegistry().apply {
                store(
                    declaredEntry(
                        pending = true,
                        connects = emptyList(),
                        settings = DECLARED_SETTINGS.filter { it.kind != ScriptSettingKind.URL },
                    ),
                )
            }
        show(registry)
        waitFor(SCRIPT_NAME)
        compose.onNodeWithText(SCRIPT_NAME).performClick()

        // The heading is always there, and the empty case is a plain statement below it rather
        // than a site row that happens to be called "No sites declared".
        waitFor(str(R.string.scripts_confirm_section_connects))
        compose.onNodeWithText(str(R.string.scripts_confirm_connects_none)).assertExists()
        compose.onNodeWithText(str(R.string.scripts_confirm_connects_none_body)).assertExists()
    }

    @Test
    fun aScriptWithOnlyAnAddressSettingIsNotCalledOffline() {
        val registry = FakeScriptRegistry().apply { store(declaredEntry(pending = true, connects = emptyList())) }
        show(registry)
        waitFor(SCRIPT_NAME)
        compose.onNodeWithText(SCRIPT_NAME).performClick()

        // It sends text to whatever address the user types, so it must not claim to stay offline.
        waitFor(str(R.string.scripts_connects_typed_below))
        compose.onNodeWithText(str(R.string.scripts_confirm_connects_none)).assertDoesNotExist()
    }

    @Test
    fun aFileOriginShowsTheFileNameOrTheGenericLabel() {
        val registry =
            FakeScriptRegistry().apply {
                store(
                    declaredEntry(
                        name = "有文件名",
                        pending = false,
                        origin = ScriptSourceOrigin.File("my-script.js"),
                    ),
                    declaredEntry(
                        name = "旧记录",
                        pending = false,
                        origin = ScriptSourceOrigin.File("msf:42"),
                    ).copy(id = "script-2", order = 1),
                )
            }
        show(registry)
        waitFor("有文件名")

        compose.onNodeWithText(str(R.string.scripts_origin_file, "my-script.js")).assertExists()
        // The old opaque picker id is dropped, not shown as a file name.
        compose.onNodeWithText(str(R.string.scripts_origin_file_unknown)).assertExists()
        compose.onNodeWithText("msf:42").assertDoesNotExist()
    }

    @Test
    fun theDetailPageSummarizesTheStatusAndWhatItReads() {
        val registry = FakeScriptRegistry().apply { store(declaredEntry(pending = false, enabled = true)) }
        show(registry)
        waitFor(SCRIPT_NAME)
        compose.onNodeWithText(SCRIPT_NAME).performClick()

        // The About section sits below the trial box, so scroll to it before asserting.
        waitFor(str(R.string.scripts_test_section))
        val page = hasScrollToIndexAction()
        compose.onNode(page).performScrollToNode(hasText(str(R.string.scripts_status)))
        compose.onNodeWithText(str(R.string.scripts_status_enabled)).assertExists()
        compose.onNodeWithText(str(R.string.scripts_confirm_reads_body)).assertExists()
    }

    @Test
    fun theTrialResultCanBeCopied() {
        val registry =
            FakeScriptRegistry().apply {
                store(declaredEntry(pending = false, enabled = true))
                trialOutcome =
                    ScriptRunOutcome.Success(
                        outputs = listOf("结果一", "结果二"),
                        consoleLines = emptyList(),
                        consoleTruncated = false,
                    )
            }
        show(registry)
        waitFor(SCRIPT_NAME)
        compose.onNodeWithText(SCRIPT_NAME).performClick()

        waitFor(str(R.string.scripts_test_section))
        compose.onNode(hasSetTextAction()).performTextInput("输入")
        compose.onNodeWithText(str(R.string.scripts_test_run)).performClick()
        waitFor(str(R.string.scripts_test_outputs))
        // What was typed is what the run received, and the box keeps it for the next run.
        assertEquals(listOf("script-1" to "输入"), registry.trials)
        compose.onNode(hasSetTextAction()).assert(hasText("输入"))
        compose.onNodeWithText(str(R.string.scripts_test_copy)).performClick()
        waitFor(str(R.string.scripts_test_copied))

        val clipboard =
            InstrumentationRegistry
                .getInstrumentation()
                .context
                .getSystemService(ClipboardManager::class.java)
        assertEquals(
            "结果一\n结果二",
            clipboard.primaryClip
                ?.getItemAt(0)
                ?.text
                ?.toString(),
        )
    }
}
