package app.songyan.ime.settings

import android.app.Application
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songyan.ime.content.BuiltinToolDefinitions
import app.songyan.ime.content.OpenCCToolConfig
import app.songyan.ime.content.ToolDefinition
import app.songyan.ime.content.ToolStore
import app.songyan.ime.settings.state.IncomingToolHolder
import app.songyan.ime.settings.state.toolNameTags
import app.songyan.ime.settings.ui.KeyboardToolsViewModel
import app.songyan.ime.settings.ui.screens.KeyboardToolEditorScreen
import app.songyan.ime.settings.ui.screens.KeyboardToolsScreen
import app.songyan.ime.settings.ui.screens.toolMenuTag
import app.songyan.ime.settings.ui.screens.toolNameFieldTag
import app.songyan.ime.settings.ui.screens.toolSwitchTag
import app.songyan.ime.settings.ui.theme.SongyanTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * The 键盘工具 page against a real store: enabling, pinning, ordering, delete-with-undo, a broken
 * import and the export/import round trip all go through [ToolStore] on the device file system, so
 * the test sees the files the keyboard reads rather than a fake model.
 */
@RunWith(AndroidJUnit4::class)
class KeyboardToolsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumented =
        androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation()
    private val application: Application = ApplicationProvider.getApplicationContext()
    private lateinit var root: File
    private lateinit var builtins: List<ToolDefinition>
    private lateinit var store: ToolStore
    private lateinit var model: KeyboardToolsViewModel

    @Before
    fun freshStore() {
        IncomingToolHolder.clear()
        root = File(application.cacheDir, "keyboard-tools-" + UUID.randomUUID())
        builtins = BuiltinToolDefinitions.all(OpenCCToolConfig.S2T)
        store = ToolStore(root.toPath(), builtins)
        model = KeyboardToolsViewModel(application) { store }
    }

    @After
    fun clearPendingFile() {
        IncomingToolHolder.clear()
    }

    private fun catalog() = runBlocking { store.load() }

    private fun tags() = toolNameTags(instrumented.targetContext.resources.configuration.locales)

    private fun widthTool() = catalog().tools.first { it.id == BuiltinToolDefinitions.WIDTH_ID }

    /** The editor's page list is taller than the viewport; a row has to be scrolled to before it is composed. */
    private fun scrollToText(text: String) {
        compose.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText(text))
    }

    private fun scrollToTop() {
        compose.onAllNodes(hasScrollAction())[0].performScrollToIndex(0)
    }

    private fun show() {
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                KeyboardToolsScreen(onBack = {}, onEditTool = {}, viewModel = model)
            }
        }
        compose.waitUntil(10_000) {
            model.uiState.value.tools
                .isNotEmpty() &&
                !model.uiState.value.loading
        }
    }

    private fun openActions(id: String) {
        compose.onNodeWithTag(toolMenuTag(id)).performClick()
        compose.waitForIdle()
    }

    private fun pin(id: String) {
        openActions(id)
        compose.onNodeWithText(str(R.string.tools_pin)).performClick()
        compose.waitForIdle()
    }

    @Test
    fun enablingAToolWritesTheKeyboardStateAndDisablingTakesItBack() {
        show()
        compose.onNodeWithTag(toolSwitchTag(BuiltinToolDefinitions.WIDTH_ID)).performClick()
        compose.waitUntil(10_000) { widthTool().metadata.enabled.not() }
        compose.onNodeWithTag(toolSwitchTag(BuiltinToolDefinitions.WIDTH_ID)).performClick()
        compose.waitUntil(10_000) { widthTool().metadata.enabled }
    }

    @Test
    fun twoToolsCanBePinnedAndTheThirdIsRefusedWithTheLimit() {
        show()
        pin(BuiltinToolDefinitions.WIDTH_ID)
        pin(BuiltinToolDefinitions.LETTER_CASE_ID)
        compose.waitUntil(10_000) { catalog().pinnedTools.size == 2 }

        pin(BuiltinToolDefinitions.SCRIPT_CONVERSION_ID)
        compose.waitUntil(10_000) {
            compose
                .onAllNodes(hasText(str(R.string.tools_pin_limit, ToolStore.MAXIMUM_PINNED)))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        val pinned = catalog().pinnedTools.map { it.id }
        assertEquals(
            listOf(BuiltinToolDefinitions.WIDTH_ID, BuiltinToolDefinitions.LETTER_CASE_ID),
            pinned,
        )
    }

    /** The menu was anchored to the whole row, so it opened at the row's start, far from ⋮. */
    @Test
    fun theActionsMenuOpensAtItsButton() {
        show()
        // The menu is a popup window, so both spans are taken on screen rather than in a root.
        val span = { node: SemanticsNode -> node.positionOnScreen.x..(node.positionOnScreen.x + node.size.width) }
        val button = span(compose.onNodeWithTag(toolMenuTag(BuiltinToolDefinitions.WIDTH_ID)).fetchSemanticsNode())
        openActions(BuiltinToolDefinitions.WIDTH_ID)
        val item = span(compose.onNodeWithText(str(R.string.tools_export)).fetchSemanticsNode())
        assertTrue("$item does not reach $button", item.start < button.endInclusive && item.endInclusive > button.start)
    }

    @Test
    fun movingAToolDownUsesTheNewOrderTheToolbarReads() {
        show()
        openActions(BuiltinToolDefinitions.WIDTH_ID)
        compose.onNodeWithText(str(R.string.tools_move_down)).performClick()
        compose.waitUntil(10_000) {
            catalog().tools.take(3).map { it.id } ==
                listOf(
                    BuiltinToolDefinitions.SCRIPT_CONVERSION_ID,
                    BuiltinToolDefinitions.LETTER_CASE_ID,
                    BuiltinToolDefinitions.WIDTH_ID,
                )
        }
    }

    @Test
    fun deletingAUserToolCanBeUndoneFromTheSnackbar() {
        runBlocking { store.copyExample(BuiltinToolDefinitions.width(), "user-delete") }
        show()
        openActions("user-delete")
        compose.onNodeWithText(str(R.string.tools_delete)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.tools_delete)).performClick()
        compose.waitUntil(10_000) { catalog().tools.none { it.id == "user-delete" } }

        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText(str(R.string.tools_undo))).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(str(R.string.tools_undo)).performClick()
        compose.waitUntil(10_000) { catalog().tools.any { it.id == "user-delete" } }
    }

    @Test
    fun aBrokenSharedFileIsExplainedAndNothingIsStored() {
        val broken = File(application.cacheDir, "broken-" + UUID.randomUUID() + TOOL_SUFFIX)
        broken.writeText("{\"formatVersion\":1}")
        val before = catalog().tools.size
        IncomingToolHolder.offer(Uri.fromFile(broken))
        show()

        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText(str(R.string.tools_shared_title))).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(str(R.string.tools_import_action)).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText(str(R.string.tools_error_invalid_shape))).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("nothing is imported without a valid definition", before, catalog().tools.size)
    }

    @Test
    fun exportingAndImportingAToolKeepsItsNamesAndSteps() {
        val saved = runBlocking { store.copyExample(BuiltinToolDefinitions.width(), "user-roundtrip") }
        show()
        val bytes = runBlocking { model.exportedBytes(saved.id) }
        val exported = ToolDefinition.decode(bytes)
        assertEquals(saved.definition, exported)

        val file = File(application.cacheDir, "roundtrip-" + UUID.randomUUID() + TOOL_SUFFIX)
        file.writeBytes(bytes)
        // Remove it first, so the import has to recreate the file rather than update it.
        runBlocking { store.delete(saved.id) }
        assertTrue(catalog().tools.none { it.id == saved.id })

        IncomingToolHolder.offer(Uri.fromFile(file))
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText(str(R.string.tools_shared_title))).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(str(R.string.tools_import_action)).performClick()
        compose.waitUntil(10_000) { catalog().tools.any { it.id == saved.id } }
        assertEquals(
            exported,
            catalog().tools.first { it.id == saved.id }.definition,
        )
        compose.waitUntil(10_000) {
            compose
                .onAllNodes(hasText(str(R.string.tools_imported, exported.displayName(tags()))))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    @Test
    fun aCopiedExampleCanBeRenamedAndSavedThroughTheEditor() {
        runBlocking { store.copyExample(BuiltinToolDefinitions.width(), "user-rename") }
        var back = false
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                KeyboardToolEditorScreen(toolId = "user-rename", onBack = { back = true }, viewModel = model)
            }
        }
        compose.waitUntil(10_000) { model.uiState.value.editor != null }
        val field = compose.onNodeWithTag(toolNameFieldTag("zh-Hans"))
        field.performTextClearance()
        field.performTextInput("我的宽度工具")
        compose.onNodeWithText(str(R.string.tools_editor_save)).performClick()
        compose.waitUntil(10_000) { back }
        val saved = catalog().tools.first { it.id == "user-rename" }
        assertEquals("我的宽度工具", saved.definition.name["zh-Hans"])
        assertEquals(BuiltinToolDefinitions.width().steps, saved.definition.steps)
    }

    @Test
    fun aDraftThatWouldBeAnInvalidFileIsRefusedWithTheSameReason() {
        runBlocking { store.copyExample(BuiltinToolDefinitions.width(), "user-broken-draft") }
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                KeyboardToolEditorScreen(toolId = "user-broken-draft", onBack = {}, viewModel = model)
            }
        }
        compose.waitUntil(10_000) { model.uiState.value.editor != null }
        // A find-and-replace step needs a pattern; an empty one is refused by the format.
        val addStep = str(R.string.tools_editor_add_step)
        scrollToText(addStep)
        compose.onNodeWithText(addStep).performClick()
        compose.onNodeWithText(str(R.string.tools_step_type_replace)).performClick()
        compose.onNodeWithText(str(R.string.tools_editor_save)).performClick()
        scrollToTop()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText(str(R.string.tools_error_invalid_step, 2))).fetchSemanticsNodes().isNotEmpty()
        }
        assertNotNull("the draft stays open after a refusal", model.uiState.value.editor)
        assertTrue(
            "nothing was stored",
            catalog()
                .tools
                .first { it.id == "user-broken-draft" }
                .definition.steps.size == 1,
        )
    }

    @Test
    fun theEditorOffersEveryStepTypeAndRespectsTheStepLimit() {
        runBlocking { store.copyExample(BuiltinToolDefinitions.letterCase(), "user-steps") }
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                KeyboardToolEditorScreen(toolId = "user-steps", onBack = {}, viewModel = model)
            }
        }
        compose.waitUntil(10_000) { model.uiState.value.editor != null }
        val addStep = str(R.string.tools_editor_add_step)
        for (index in 1 until app.songyan.ime.content.ToolDefinitionLimits.MAXIMUM_STEPS) {
            scrollToText(addStep)
            compose.onNodeWithText(addStep).performClick()
            compose.onNodeWithText(str(R.string.tools_step_type_opencc)).performClick()
        }
        compose.waitUntil(10_000) {
            model.uiState.value.editor
                ?.draft
                ?.steps
                ?.size == app.songyan.ime.content.ToolDefinitionLimits.MAXIMUM_STEPS
        }
        val limit = str(R.string.tools_editor_step_limit, app.songyan.ime.content.ToolDefinitionLimits.MAXIMUM_STEPS)
        scrollToText(limit)
        compose.onNodeWithText(limit).assertExists()
        assertFalse(
            model.uiState.value.editor!!
                .canAddStep,
        )
    }

    private companion object {
        const val TOOL_SUFFIX = ".songyantool.json"
    }
}
