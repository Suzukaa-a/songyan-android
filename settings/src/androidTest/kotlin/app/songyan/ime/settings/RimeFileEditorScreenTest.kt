package app.songyan.ime.settings

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.songyan.ime.settings.state.RimeConfigApplyResult
import app.songyan.ime.settings.state.RimeConfigEditResult
import app.songyan.ime.settings.state.RimeConfigFileResult
import app.songyan.ime.settings.state.RimeConfigGateway
import app.songyan.ime.settings.state.RimeConfigInfo
import app.songyan.ime.settings.state.RimeConfigSaveResult
import app.songyan.ime.settings.ui.RimeFileEditorViewModel
import app.songyan.ime.settings.ui.screens.RIME_EDITOR_ERROR_TAG
import app.songyan.ime.settings.ui.screens.RIME_EDITOR_FIELD_TAG
import app.songyan.ime.settings.ui.screens.RIME_EDITOR_SCRIPTS_TAG
import app.songyan.ime.settings.ui.screens.RimeFileEditorScreen
import app.songyan.ime.settings.ui.theme.SongyanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The in-app editor for the user's configuration file. */
class RimeFileEditorScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private class Gateway(
        var text: String = "patch: {}\n",
        var save: RimeConfigSaveResult = RimeConfigSaveResult.Saved,
    ) : RimeConfigGateway {
        override val available = true

        override fun openFolderIntents(): List<Intent> = emptyList()

        override suspend fun info() = RimeConfigInfo(fileCount = 0, pending = false)

        override suspend fun apply(confirmScripts: Boolean): RimeConfigApplyResult = RimeConfigApplyResult.Applied

        override suspend fun restore(path: String) = Unit

        override suspend fun acknowledge(paths: List<String>) = Unit

        override suspend fun loadFile(
            key: String,
            grammarExample: Boolean,
        ): RimeConfigFileResult = RimeConfigFileResult.Loaded("内置全拼", "songyan_quanpin.custom.yaml", text)

        override suspend fun saveFile(
            key: String,
            text: String,
        ): RimeConfigSaveResult = save

        override suspend fun externalEditIntents(key: String): RimeConfigEditResult = RimeConfigEditResult.Failure(null)
    }

    private var closed = 0

    private fun show(gateway: Gateway) {
        val model = RimeFileEditorViewModel(gateway, "songyan_quanpin", false)
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                RimeFileEditorScreen(
                    key = "songyan_quanpin",
                    grammarExample = false,
                    onClose = { closed++ },
                    onApply = {},
                    viewModel = model,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun theFileShowsAndAnInvalidSaveNamesTheLineAndKeepsTheText() {
        show(Gateway(save = RimeConfigSaveResult.Invalid(3)))

        compose.onNodeWithText("内置全拼").assertExists()
        compose.onNodeWithTag(RIME_EDITOR_FIELD_TAG).performTextInput("x")
        compose.onNodeWithText(str(R.string.rime_editor_save)).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(RIME_EDITOR_ERROR_TAG).assertExists()
        compose.onNodeWithText(str(R.string.rime_editor_invalid_line, 3)).assertExists()
        assertEquals(0, closed)
    }

    @Test
    fun leavingWithUnsavedEditsAsksFirst() {
        show(Gateway())
        compose.onNodeWithTag(RIME_EDITOR_FIELD_TAG).performTextInput("x")

        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.rime_editor_discard_title)).assertExists()
        assertEquals(0, closed)
        compose.onNodeWithText(str(R.string.layout_editor_discard)).performClick()
        assertEquals(1, closed)
    }

    @Test
    fun aFileWithAScriptEntryShowsTheWarning() {
        show(Gateway(text = "patch:\n  engine/filters/+: [lua_filter@*x]\n"))

        compose.onNodeWithTag(RIME_EDITOR_SCRIPTS_TAG).assertExists()
        compose.onNodeWithText(str(R.string.rime_editor_scripts_banner)).assertExists()
    }

    @Test
    fun aPlainFileHasNoWarning() {
        show(Gateway(text = "# lua_filter@x is only a comment here\npatch: {}\n"))

        compose.onNodeWithTag(RIME_EDITOR_SCRIPTS_TAG).assertDoesNotExist()
    }
}
